import time

from fastapi import APIRouter, Depends, Query, Request
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.auth import optional_user
from app.config import settings
from app.db.session import get_db
from app.limiter import limiter
from app.models import Group, GroupsResponse
from app.routers.reports import (
    REPORT_COLUMNS,
    _compute_trust_for_report,
    _find_region_for_coords,
    _get_active_alerts,
)
from app.services.groups import calculate_groups

router = APIRouter()

# 30s cache: dict mapping cache_key -> (timestamp, GroupsResponse)
_groups_response_cache = {}


@router.get("/groups", response_model=GroupsResponse)
@limiter.limit("60/minute")
def get_groups(
    request: Request,
    lat: float = Query(...),
    lon: float = Query(...),
    radius_m: int = Query(3000, alias="radiusM"),
    user: dict | None = Depends(optional_user),
    db: Session = Depends(get_db),
):
    """GET /groups?lat&lon&radiusM (optional auth, cache 30s)"""

    now = int(time.time())
    cache_key = f"{round(lat, 3)}_{round(lon, 3)}_{radius_m}"

    if cache_key in _groups_response_cache:
        cached_ts, cached_resp = _groups_response_cache[cache_key]
        if now - cached_ts <= 30:
            return cached_resp

    region_id = _find_region_for_coords(lat, lon)
    if not region_id:
        # Not in any region, return empty
        return GroupsResponse(
            groups=[], beacons=[], min_size=int(getattr(settings, "GROUP_MIN_SIZE", 5))
        )

    now = int(time.time())

    # Delete presence rows older than 30 min
    db.execute(
        text("DELETE FROM presence WHERE updated_at < :cutoff_30m"), {"cutoff_30m": now - 30 * 60}
    )
    db.commit()

    # Presences from last 10 min
    cutoff_10m = now - 10 * 60
    presences = (
        db.execute(
            text("SELECT uid, lat, lon, updated_at FROM presence WHERE updated_at >= :cutoff"),
            {"cutoff": cutoff_10m},
        )
        .mappings()
        .all()
    )

    presences_list = [dict(p) for p in presences]

    # FL Reports from last 12 hours
    cutoff_12h = now - 12 * 3600
    reports = (
        db.execute(
            text(
                f"SELECT {REPORT_COLUMNS} FROM reports "
                "WHERE region_id = :region_id AND type = 'FL' AND created_at >= :cutoff"
            ),
            {"region_id": region_id, "cutoff": cutoff_12h},
        )
        .mappings()
        .all()
    )

    active_alerts = _get_active_alerts(db, region_id, now)

    fl_reports = []
    for r in reports:
        r_dict = dict(r)
        trust, label = _compute_trust_for_report(db, r_dict, now, active_alerts)
        r_dict["label"] = label
        fl_reports.append(r_dict)

    # Calculate groups using DBSCAN
    groups_data = calculate_groups(presences_list, region_id, fl_reports)

    out_groups = []
    for g in groups_data:
        # Distance filter for groups? The prompt says `radiusM`.
        # Oh, if it's within radius of the request!
        # The prompt says: `GET /groups?lat&lon&radiusM: DBSCAN ... Return {groups, beacons: [], minSize}`
        # The route should only return groups within radiusM?
        # CONTRACTS: "returned to everyone within radiusM." That was for Beacons.
        # But maybe Groups too? Let's check radius.
        from app.services.groups import _haversine_distance

        if _haversine_distance(lat, lon, g["lat"], g["lon"]) <= radius_m:
            out_groups.append(Group(**g))

    res = GroupsResponse(
        groups=out_groups, beacons=[], min_size=int(getattr(settings, "GROUP_MIN_SIZE", 5))
    )

    _groups_response_cache[cache_key] = (now, res)
    return res
