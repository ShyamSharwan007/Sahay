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
from app.models import AdminOverview, CamelModel, SimulateRequest
from app.routers.alerts import _generate_id, _get_region_bbox
from app.wire import build_alert, build_shelter

logger = logging.getLogger(__name__)
router = APIRouter()


class SimulateResponse(CamelModel):
    alerts: list[dict]
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

    return AdminOverview(
        devices=devices,
        reports=reports,
        presence=presence,
        groups=groups,
        beacons=beacons,
        sms_sent_today=0,
    )


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

    return SimulateResponse(alerts=alerts_data, shelter_changes=shelter_changes)


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
    db.execute(
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
    return {"status": "ok"}
