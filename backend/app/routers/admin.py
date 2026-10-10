"""Admin router (CONTRACTS §3)."""

import json
import logging
import sqlite3
import tempfile
import time
from pathlib import Path

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query, Request
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.auth import admin_user
from app.db.session import get_db
from app.limiter import limiter
from app.models import AdminOverview, AlertResponse, CamelModel, Report, SimulateRequest
from app.routers.alerts import _generate_id, _get_region_bbox
from app.wire import build_alert, build_shelter

logger = logging.getLogger(__name__)
router = APIRouter()


class SimulateResponse(CamelModel):
    alerts: list[AlertResponse]
    shelter_changes: list[dict]


@router.get("/admin/overview", response_model=AdminOverview)
@limiter.limit("30/minute")
def get_admin_overview(
    request: Request,
    region_id: str = Query(..., alias="regionId"),
    user: dict = Depends(admin_user),
    db: Session = Depends(get_db),
):
    """GET /admin/overview?regionId="""
    now = int(time.time())

    devices = (
        db.scalar(
            text("SELECT COUNT(*) FROM devices WHERE region_id = :region_id"),
            {"region_id": region_id},
        )
        or 0
    )

    reports = (
        db.scalar(
            text(
                "SELECT COUNT(*) FROM reports WHERE region_id = :region_id AND created_at >= :since"
            ),
            {"region_id": region_id, "since": now - 24 * 3600},
        )
        or 0
    )

    presence = (
        db.scalar(
            text("SELECT COUNT(*) FROM presence WHERE updated_at >= :since"),
            {"since": now - 10 * 60},
        )
        or 0
    )

    # We don't have a groups table, so returning 0 as per spec or mock it.
    groups = 0

    beacons = db.scalar(text("SELECT COUNT(*) FROM beacons WHERE active = TRUE")) or 0

    active_alerts = (
        db.scalar(
            text("SELECT COUNT(*) FROM alerts WHERE region_id = :r AND expires_at > :now"),
            {"r": region_id, "now": now},
        )
        or 0
    )
    pending_reviews = (
        db.scalar(
            text(
                "SELECT COUNT(*) FROM reports WHERE region_id = :r AND photo_url IS NOT NULL "
                "AND review_status = 'pending'"
            ),
            {"r": region_id},
        )
        or 0
    )
    sms_today = (
        db.scalar(
            text("SELECT COUNT(*) FROM sms_log WHERE direction = 'OUT' AND created_at >= :since"),
            {"since": now - 24 * 3600},
        )
        or 0
    )

    return AdminOverview(
        devices=devices,
        reports=reports,
        presence=presence,
        groups=groups,
        beacons=beacons,
        sms_sent_today=sms_today,
        active_alerts=active_alerts,
        pending_reviews=pending_reviews,
        people_in_groups=_people_in_groups(db, region_id, now),
    )


def _people_in_groups(db: Session, region_id: str, now: int) -> int:
    """People currently inside a cluster of the group finder. 0 if the clustering is unavailable."""
    try:
        from app.services.groups import calculate_groups

        rows = (
            db.execute(
                text("SELECT uid, lat, lon, updated_at FROM presence WHERE updated_at >= :cutoff"),
                {"cutoff": now - 10 * 60},
            )
            .mappings()
            .all()
        )
        return sum(int(g.get("size", 0)) for g in calculate_groups([dict(r) for r in rows], region_id, []))
    except Exception:
        logger.warning("Could not count people in groups", exc_info=True)
        return 0


def _get_sqlite_url(region_id: str) -> str | None:
    """Get the SQLite download URL for a region from packs.json (object keyed by regionId)."""
    packs_file = Path(__file__).resolve().parent.parent / "data" / "packs.json"
    if not packs_file.exists():
        return None
    try:
        packs = json.loads(packs_file.read_text())
        if isinstance(packs, dict):
            pack = packs.get(region_id)
            if pack:
                return pack.get("sqliteUrl")
    except Exception:
        pass
    return None


@router.post("/admin/simulate", response_model=SimulateResponse)
@limiter.limit("30/minute")
def simulate_scenario(
    req: SimulateRequest,
    request: Request,
    user: dict = Depends(admin_user),
    db: Session = Depends(get_db),
):
    """POST /admin/simulate"""
    if req.scenario != "cyclone":
        raise HTTPException(
            status_code=400,
            detail={
                "error": {"code": "BAD_REQUEST", "message": "Only cyclone scenario is supported"}
            },
        )

    bbox = _get_region_bbox(req.region_id)
    if not bbox:
        raise HTTPException(
            status_code=400, detail={"error": {"code": "BAD_REQUEST", "message": "Invalid region"}}
        )

    center_lon = (bbox[0] + bbox[2]) / 2.0
    center_lat = (bbox[1] + bbox[3]) / 2.0

    now = int(time.time())

    # Create CYC_WARN
    cyc_id = _generate_id()
    cyc_wire = build_alert(cyc_id, "CYC_WARN", 2, center_lat, center_lon, 8000, True, now)

    # Create FLD_EVAC
    fld_id = _generate_id()
    fld_wire = build_alert(fld_id, "FLD_EVAC", 3, center_lat, center_lon, 3000, True, now)

    alerts_data = [
        {
            "id": cyc_id,
            "template_code": "CYC_WARN",
            "severity": 2,
            "lat": center_lat,
            "lon": center_lon,
            "radius_m": 8000,
            "wire": cyc_wire,
        },
        {
            "id": fld_id,
            "template_code": "FLD_EVAC",
            "severity": 3,
            "lat": center_lat,
            "lon": center_lon,
            "radius_m": 3000,
            "wire": fld_wire,
        },
    ]

    expires_at = now + 6 * 3600

    for a in alerts_data:
        db.execute(
            text("""
                INSERT INTO alerts (id, region_id, template_code, severity, lat, lon, radius_m, is_simulation, issued_at, expires_at, wire, created_by)
                VALUES (:id, :region_id, :template_code, :severity, :lat, :lon, :radius_m, TRUE, :issued_at, :expires_at, :wire, :created_by)
            """),
            {
                "id": a["id"],
                "region_id": req.region_id,
                "template_code": a["template_code"],
                "severity": a["severity"],
                "lat": a["lat"],
                "lon": a["lon"],
                "radius_m": a["radius_m"],
                "issued_at": now,
                "expires_at": expires_at,
                "wire": a["wire"],
                "created_by": user.get("uid", "admin"),
            },
        )

    shelter_changes = []

    # Try to mark a shelter FULL
    sqlite_url = _get_sqlite_url(req.region_id)
    if sqlite_url:
        try:
            with httpx.Client() as client:
                resp = client.get(sqlite_url, timeout=10.0)
                if resp.status_code == 200:
                    with tempfile.NamedTemporaryFile(delete=False, suffix=".sqlite") as tmp:
                        tmp.write(resp.content)
                        tmp_path = tmp.name

                    conn = sqlite3.connect(tmp_path)
                    cur = conn.cursor()
                    cur.execute(
                        "SELECT id FROM poi WHERE type IN ('SHELTER', 'CANDIDATE_SHELTER') LIMIT 1"
                    )
                    row = cur.fetchone()
                    if row:
                        shelter_id = row[0]
                        wire = build_shelter(shelter_id, "F", now)
                        db.execute(
                            text("""
                                INSERT INTO shelter_status (shelter_id, region_id, status, updated_at, wire)
                                VALUES (:shelter_id, :region_id, 'FULL', :updated_at, :wire)
                                ON CONFLICT (shelter_id) DO UPDATE SET
                                    status = EXCLUDED.status,
                                    updated_at = EXCLUDED.updated_at,
                                    wire = EXCLUDED.wire
                            """),
                            {
                                "shelter_id": shelter_id,
                                "region_id": req.region_id,
                                "updated_at": now,
                                "wire": wire,
                            },
                        )
                        shelter_changes.append({"shelterId": shelter_id, "status": "FULL"})
                    conn.close()
                    Path(tmp_path).unlink(missing_ok=True)
        except Exception as e:
            logger.warning(f"Failed to fetch or query sqlite pack for simulation: {e}")

    db.commit()

    alerts_out = [
        AlertResponse(
            id=a["id"],
            region_id=req.region_id,
            template_code=a["template_code"],
            severity=a["severity"],
            lat=a["lat"],
            lon=a["lon"],
            radius_m=a["radius_m"],
            issued_at=now,
            expires_at=expires_at,
            is_simulation=True,
            wire=a["wire"],
        )
        for a in alerts_data
    ]
    return SimulateResponse(alerts=alerts_out, shelter_changes=shelter_changes)


class EndSimulationRequest(CamelModel):
    region_id: str


@router.post("/admin/simulate/end")
@limiter.limit("30/minute")
def end_simulation(
    req: EndSimulationRequest,
    request: Request,
    user: dict = Depends(admin_user),
    db: Session = Depends(get_db),
):
    """POST /admin/simulate/end"""
    now = int(time.time())
    result = db.execute(
        text("""
            UPDATE alerts
            SET expires_at = :now
            WHERE region_id = :region_id
              AND is_simulation = TRUE
              AND expires_at > :now
        """),
        {"now": now, "region_id": req.region_id},
    )
    db.commit()
    return {"status": "ok", "ended": result.rowcount}


class SmsLogEntry(CamelModel):
    id: str
    direction: str | None = None
    phone: str | None = None
    body: str | None = None
    created_at: int | None = None
    status: str | None = None


@router.get("/admin/sms-log", response_model=list[SmsLogEntry])
@limiter.limit("30/minute")
def get_sms_log(
    request: Request,
    limit: int = Query(100, ge=1, le=500),
    user: dict = Depends(admin_user),
    db: Session = Depends(get_db),
):
    """GET /admin/sms-log — newest SMS sent and received."""
    rows = (
        db.execute(text("SELECT * FROM sms_log ORDER BY created_at DESC LIMIT :n"), {"n": limit})
        .mappings()
        .all()
    )
    return [SmsLogEntry(**{k: r[k] for k in ("id", "direction", "phone", "body", "created_at", "status")}) for r in rows]


class AdminShelter(CamelModel):
    id: str
    name: str
    type: str
    lat: float
    lon: float
    status: str
    updated_at: int | None = None


_shelter_cache: dict[str, tuple[float, list[dict]]] = {}
_SHELTER_CACHE_SECONDS = 3600


def _load_pack_shelters(region_id: str) -> list[dict]:
    """Shelters (official and possible) from the region's trip pack. Cached for an hour; [] if unavailable."""
    cached = _shelter_cache.get(region_id)
    if cached and time.time() - cached[0] < _SHELTER_CACHE_SECONDS:
        return cached[1]
    url = _get_sqlite_url(region_id)
    shelters: list[dict] = []
    if url:
        tmp_path = None
        try:
            with httpx.Client(follow_redirects=True) as client:
                resp = client.get(url, timeout=10.0)
            if resp.status_code == 200:
                with tempfile.NamedTemporaryFile(delete=False, suffix=".sqlite") as tmp:
                    tmp.write(resp.content)
                    tmp_path = tmp.name
                conn = sqlite3.connect(tmp_path)
                try:
                    rows = conn.execute(
                        "SELECT id, name, type, lat, lon FROM poi WHERE type IN ('SHELTER', 'CANDIDATE_SHELTER') ORDER BY name"
                    ).fetchall()
                finally:
                    conn.close()
                shelters = [{"id": r[0], "name": r[1], "type": r[2], "lat": r[3], "lon": r[4]} for r in rows]
        except Exception:
            logger.warning("Could not read shelters of %s from its pack", region_id, exc_info=True)
        finally:
            if tmp_path:
                Path(tmp_path).unlink(missing_ok=True)
    if shelters:  # do not cache failures
        _shelter_cache[region_id] = (time.time(), shelters)
    return shelters


@router.get("/admin/shelters", response_model=list[AdminShelter])
@limiter.limit("30/minute")
def list_admin_shelters(
    request: Request,
    region_id: str = Query(..., alias="regionId"),
    user: dict = Depends(admin_user),
    db: Session = Depends(get_db),
):
    """GET /admin/shelters?regionId — the pack's shelters with their current status (UNKNOWN until set)."""
    status_rows = (
        db.execute(
            text("SELECT shelter_id, status, updated_at FROM shelter_status WHERE region_id = :r"),
            {"r": region_id},
        )
        .mappings()
        .all()
    )
    status = {r["shelter_id"]: r for r in status_rows}
    out = []
    for s in _load_pack_shelters(region_id):
        st = status.get(s["id"])
        out.append(
            AdminShelter(
                id=s["id"], name=s["name"], type=s["type"], lat=s["lat"], lon=s["lon"],
                status=st["status"] if st else "UNKNOWN", updated_at=st["updated_at"] if st else None,
            )
        )
    return out


class AdminReport(Report):
    region_id: str | None = None


@router.get("/admin/reports", response_model=list[AdminReport])
@limiter.limit("60/minute")
def list_admin_reports(
    request: Request,
    region_id: str = Query(..., alias="regionId"),
    since_hours: int = Query(72, alias="sinceHours", ge=1, le=720),
    user: dict = Depends(admin_user),
    db: Session = Depends(get_db),
):
    """GET /admin/reports?regionId= — reports of the last hours with trust and photo review state."""
    from app.routers.reports import (
        REPORT_COLUMNS,
        _compute_trust_for_report,
        _get_active_alerts,
        _review_status,
    )

    now = int(time.time())
    rows = (
        db.execute(
            text(
                f"SELECT {REPORT_COLUMNS} FROM reports WHERE region_id = :r AND created_at >= :since "
                "ORDER BY created_at DESC LIMIT 200"
            ),
            {"r": region_id, "since": now - since_hours * 3600},
        )
        .mappings()
        .all()
    )
    alerts = _get_active_alerts(db, region_id, now)
    out = []
    for row in rows:
        r = dict(row)
        score, label = _compute_trust_for_report(db, r, now, alerts)
        out.append(
            AdminReport(
                id=r["id"],
                type=r["type"],
                lat=r["lat"],
                lon=r["lon"],
                note=r["note"],
                photo_url=r["photo_url"],
                created_at=r["created_at"],
                trust_score=round(score, 2),
                label=label,
                mine=False,
                channel=r["channel"],
                review_status=_review_status(db, r, score),
                region_id=r["region_id"],
            )
        )
    return out


def _set_review(db: Session, report_id: str, status: str) -> dict:
    result = db.execute(
        text("UPDATE reports SET review_status = :s WHERE id = :id AND photo_url IS NOT NULL"),
        {"s": status, "id": report_id},
    )
    db.commit()
    if result.rowcount == 0:
        raise HTTPException(
            status_code=404,
            detail={"error": {"code": "NOT_FOUND", "message": "Report with a photo not found"}},
        )
    return {"id": report_id, "reviewStatus": status}


@router.post("/admin/reports/{report_id}/approve")
@limiter.limit("60/minute")
def approve_report_photo(
    report_id: str, request: Request, user: dict = Depends(admin_user), db: Session = Depends(get_db)
):
    return _set_review(db, report_id, "approved")


@router.post("/admin/reports/{report_id}/reject")
@limiter.limit("60/minute")
def reject_report_photo(
    report_id: str, request: Request, user: dict = Depends(admin_user), db: Session = Depends(get_db)
):
    return _set_review(db, report_id, "rejected")


class AdminAlert(AlertResponse):
    active: bool


@router.get("/admin/alerts", response_model=list[AdminAlert])
@limiter.limit("60/minute")
def list_admin_alerts(
    request: Request,
    region_id: str = Query(..., alias="regionId"),
    user: dict = Depends(admin_user),
    db: Session = Depends(get_db),
):
    """GET /admin/alerts?regionId= — newest 100 alerts of the region, expired ones included."""
    now = int(time.time())
    rows = (
        db.execute(
            text("SELECT * FROM alerts WHERE region_id = :r ORDER BY issued_at DESC LIMIT 100"),
            {"r": region_id},
        )
        .mappings()
        .all()
    )
    return [
        AdminAlert(
            id=r["id"],
            region_id=r["region_id"],
            template_code=r["template_code"],
            severity=r["severity"],
            lat=r["lat"],
            lon=r["lon"],
            radius_m=r["radius_m"],
            issued_at=r["issued_at"],
            expires_at=r["expires_at"] or 0,
            extra_text=r["extra_text"],
            is_simulation=bool(r["is_simulation"]),
            wire=r["wire"],
            active=(r["expires_at"] or 0) > now,
        )
        for r in rows
    ]


@router.delete("/admin/alerts/{alert_id}")
@limiter.limit("30/minute")
def delete_alert(
    alert_id: str,
    request: Request,
    user: dict = Depends(admin_user),
    db: Session = Depends(get_db),
):
    """DELETE /admin/alerts/{id} — removes the alert for everyone (apps drop it when it expires on their side)."""
    result = db.execute(text("DELETE FROM alerts WHERE id = :id"), {"id": alert_id})
    db.commit()
    if result.rowcount == 0:
        raise HTTPException(
            status_code=404,
            detail={"error": {"code": "NOT_FOUND", "message": "Alert not found"}},
        )
    return {"status": "ok"}
