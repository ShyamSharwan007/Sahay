import time
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.auth import current_user, optional_user
from app.db.session import get_db
from app.limiter import limiter
from app.models import Report, ReportCreate
from app.routers.alerts import _generate_id
from app.services.trust import (
    calculate_corroboration_score,
    calculate_history_score,
    calculate_official_match_score,
    calculate_proximity_score,
    calculate_recency_score,
    calculate_trust,
    get_trust_label,
)

router = APIRouter()

VALID_REPORT_TYPES = {"FL", "RB", "SF", "SO", "PL", "LS", "OT"}


def _get_active_alerts(db: Session, region_id: str, now: int) -> list[dict[str, Any]]:
    # Active alerts: issued in the last 48 hours
    cutoff = now - 48 * 3600
    rows = (
        db.execute(
            text("SELECT * FROM alerts WHERE region_id = :region_id AND issued_at >= :cutoff"),
            {"region_id": region_id, "cutoff": cutoff},
        )
        .mappings()
        .all()
    )
    return [dict(r) for r in rows]


def _compute_trust_for_report(
    db: Session,
    report: dict[str, Any],
    now: int,
    active_alerts: list[dict[str, Any]],
    evaluation_time: int | None = None,
) -> tuple[float, str]:
    """Computes the trust score and label for a report.
    If evaluation_time is provided, uses that as 'now' (useful for history).
    """
    eval_time = evaluation_time if evaluation_time is not None else now

    # P
    p_score = calculate_proximity_score(
        report["reporter_lat"] or 0.0,
        report["reporter_lon"] or 0.0,
        report["lat"],
        report["lon"],
    )

    # C
    # distinct other reporters, same type, within 300 m and 30 min (before or after)
    c_query = """
        SELECT uid, lat, lon
        FROM reports
        WHERE region_id = :region_id
          AND type = :type
          AND id != :id
          AND uid != :uid
          AND created_at BETWEEN :t_start AND :t_end
    """
    c_rows = (
        db.execute(
            text(c_query),
            {
                "region_id": report["region_id"],
                "type": report["type"],
                "id": report["id"],
                "uid": report["uid"],
                "t_start": report["created_at"] - 30 * 60,
                "t_end": report["created_at"] + 30 * 60,
            },
        )
        .mappings()
        .all()
    )

    c_uids = set()
    for row in c_rows:
        if (
            calculate_proximity_score(report["lat"], report["lon"], row["lat"], row["lon"]) >= 0.7
        ):  # 300m = 1 - 300/1000 = 0.7
            c_uids.add(row["uid"])

    c_score = calculate_corroboration_score(len(c_uids))

    # O
    o_score = calculate_official_match_score(
        report["type"], report["lat"], report["lon"], active_alerts
    )

    # E
    e_score = 0.0  # Evidence always 0 as per prompt (photoUrl null)

    # H
    # Past reports by this reporter
    past_query = """
        SELECT *
        FROM reports
        WHERE uid = :uid
          AND created_at < :created_at
    """
    past_rows = (
        db.execute(text(past_query), {"uid": report["uid"], "created_at": report["created_at"]})
        .mappings()
        .all()
    )

    likely_or_verified = 0
    for past_r in past_rows:
        p_past = calculate_proximity_score(
            past_r["reporter_lat"] or 0.0,
            past_r["reporter_lon"] or 0.0,
            past_r["lat"],
            past_r["lon"],
        )

        c_count_past_rows = (
            db.execute(
                text("""
                SELECT uid, lat, lon FROM reports
                WHERE region_id = :region_id AND type = :type AND id != :id AND uid != :uid
                  AND created_at BETWEEN :t_start AND :t_end
            """),
                {
                    "region_id": past_r["region_id"],
                    "type": past_r["type"],
                    "id": past_r["id"],
                    "uid": past_r["uid"],
                    "t_start": past_r["created_at"] - 30 * 60,
                    "t_end": past_r["created_at"] + 30 * 60,
                },
            )
            .mappings()
            .all()
        )

        c_uids_past = set()
        for row in c_count_past_rows:
            if (
                calculate_proximity_score(past_r["lat"], past_r["lon"], row["lat"], row["lon"])
                >= 0.7
            ):
                c_uids_past.add(row["uid"])

        c_past = calculate_corroboration_score(len(c_uids_past))

        # O for past report
        past_alerts = _get_active_alerts(db, past_r["region_id"], past_r["created_at"])
        o_past = calculate_official_match_score(
            past_r["type"], past_r["lat"], past_r["lon"], past_alerts
        )

        trust_past = calculate_trust(p_past, c_past, o_past, 0.0, 0.5, 1.0)  # D=1 at creation time
        if get_trust_label(trust_past) in ("VERIFIED", "LIKELY"):
            likely_or_verified += 1

    h_score = calculate_history_score(len(past_rows), likely_or_verified)

    # D
    d_score = calculate_recency_score(report["created_at"], eval_time)

    trust_score = calculate_trust(p_score, c_score, o_score, e_score, h_score, d_score)
    label = get_trust_label(trust_score)

    return trust_score, label


from app.routers.health import _REGIONS


def _find_region_for_coords(lat: float, lon: float) -> str | None:
    buffer = 0.045
    for r in _REGIONS:
        bbox = r["bbox"]
        min_lon, min_lat, max_lon, max_lat = bbox
        if (
            min_lat - buffer <= lat <= max_lat + buffer
            and min_lon - buffer <= lon <= max_lon + buffer
        ):
            return r["id"]
    return None


@router.post("/reports", response_model=Report)
@limiter.limit("10/minute")
def post_report(
    req: ReportCreate,
    request: Request,
    user: dict = Depends(current_user),
    db: Session = Depends(get_db),
):
    """POST /reports (user, 10/min per uid)"""
    if req.type not in VALID_REPORT_TYPES:
        raise HTTPException(
            status_code=400,
            detail={"error": {"code": "BAD_REQUEST", "message": "Invalid type code"}},
        )

    if req.note and len(req.note) > 140:
        raise HTTPException(
            status_code=400,
            detail={"error": {"code": "BAD_REQUEST", "message": "Note must be <= 140 chars"}},
        )

    region_id = _find_region_for_coords(req.lat, req.lon)
    if not region_id:
        raise HTTPException(
            status_code=400,
            detail={
                "error": {"code": "BAD_REQUEST", "message": "Coordinates outside known regions"}
            },
        )

    report_id = _generate_id()
    now = int(time.time())
    uid = user.get("uid", "unknown")

    # Insert
    db.execute(
        text("""
            INSERT INTO reports (id, uid, region_id, type, lat, lon, reporter_lat, reporter_lon, note, photo_url, created_at, channel)
            VALUES (:id, :uid, :region_id, :type, :lat, :lon, :reporter_lat, :reporter_lon, :note, :photo_url, :created_at, :channel)
        """),
        {
            "id": report_id,
            "uid": uid,
            "region_id": region_id,
            "type": req.type,
            "lat": req.lat,
            "lon": req.lon,
            "reporter_lat": req.reporter_lat,
            "reporter_lon": req.reporter_lon,
            "note": req.note,
            "photo_url": None,  # photoBase64 ignored
            "created_at": req.created_at,
            "channel": req.channel,
        },
    )
    db.commit()

    # Now we need to return it with trust score
    report_dict = {
        "id": report_id,
        "uid": uid,
        "region_id": region_id,
        "type": req.type,
        "lat": req.lat,
        "lon": req.lon,
        "reporter_lat": req.reporter_lat,
        "reporter_lon": req.reporter_lon,
        "note": req.note,
        "photo_url": None,
        "created_at": req.created_at,
        "channel": req.channel,
    }
    active_alerts = _get_active_alerts(db, region_id, now)
    trust_score, label = _compute_trust_for_report(db, report_dict, now, active_alerts)

    return Report(
        id=report_id,
        type=req.type,
        lat=req.lat,
        lon=req.lon,
        note=req.note,
        photo_url=None,
        created_at=req.created_at,
        trust_score=round(trust_score, 2),
        label=label,
        mine=True,
        channel=req.channel,
    )


@router.get("/reports", response_model=list[Report])
@limiter.limit("120/minute")
def get_reports(
    request: Request,
    region_id: str | None = Query(None, alias="regionId"),
    since_min: int | None = Query(180, alias="sinceMin"),
    user: dict | None = Depends(optional_user),
    db: Session = Depends(get_db),
):
    """GET /reports?regionId&sinceMin (<=720)"""
    since_min = min(since_min or 180, 720)
    now = int(time.time())
    cutoff = now - since_min * 60

    query = "SELECT * FROM reports WHERE created_at >= :cutoff"
    params: dict[str, Any] = {"cutoff": cutoff}
    if region_id:
        query += " AND region_id = :region_id"
        params["region_id"] = region_id

    rows = db.execute(text(query), params).mappings().all()

    uid = user.get("uid") if user else None
    out = []

    # Cache active alerts per region to save queries
    alerts_by_region = {}

    for r in rows:
        r_dict = dict(r)
        r_region = r_dict["region_id"]
        if r_region not in alerts_by_region:
            alerts_by_region[r_region] = _get_active_alerts(db, r_region, now)

        trust_score, label = _compute_trust_for_report(db, r_dict, now, alerts_by_region[r_region])

        out.append(
            Report(
                id=r_dict["id"],
                type=r_dict["type"],
                lat=r_dict["lat"],
                lon=r_dict["lon"],
                note=r_dict["note"],
                photo_url=r_dict["photo_url"],
                created_at=r_dict["created_at"],
                trust_score=round(trust_score, 2),
                label=label,
                mine=(uid is not None and r_dict["uid"] == uid),
                channel=r_dict["channel"],
            )
        )

    return out
