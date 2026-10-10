"""Alerts router (CONTRACTS §3)."""

import random
import string
import time
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.auth import admin_user
from app.db.session import get_db
from app.limiter import limiter
from app.models import AlertCreate, AlertResponse, CamelModel
from app.routers.health import _REGIONS
from app.services.templates import get_templates_for_lang
from app.wire import build_alert

router = APIRouter()


class AlertPostResponse(CamelModel):
    # Combines the alert data with the smsRecipients field for the POST response
    id: str
    region_id: str
    template_code: str
    severity: int
    lat: float
    lon: float
    radius_m: int
    issued_at: int
    extra_text: str | None = None
    is_simulation: bool
    wire: str
    sms_recipients: int


def _generate_id() -> str:
    # 6 random base36 chars
    chars = string.ascii_lowercase + string.digits
    return "".join(random.choices(chars, k=6))


def _get_region_bbox(region_id: str) -> list[float] | None:
    for r in _REGIONS:
        if r["id"] == region_id:
            return r["bbox"]
    return None


@router.get("/alert-templates")
@limiter.limit("120/minute")
def get_alert_templates(request: Request, lang: str = Query("en")):
    """GET /alert-templates?lang="""
    return get_templates_for_lang(lang)


@router.get("/alerts", response_model=list[AlertResponse])
@limiter.limit("120/minute")
def get_alerts(
    request: Request,
    region_id: str | None = Query(None, alias="regionId"),
    region: str | None = Query(None),
    since: int | None = Query(None),
    from_date: str | None = Query(None, alias="from"),
    to_date: str | None = Query(None, alias="to"),
    db: Session = Depends(get_db),
):
    """GET /alerts?region&from&to"""
    import datetime

    now = int(time.time())

    query = "SELECT * FROM alerts WHERE expires_at > :now"
    params: dict[str, Any] = {"now": now}

    target_region = region or region_id
    if target_region:
        query += " AND region_id = :region_id"
        params["region_id"] = target_region

    if since:
        query += " AND issued_at >= :since"
        params["since"] = since

    if from_date and to_date:
        try:
            from_dt = datetime.datetime.strptime(from_date, "%Y-%m-%d").replace(
                tzinfo=datetime.timezone.utc
            )
            to_dt = datetime.datetime.strptime(to_date, "%Y-%m-%d").replace(
                tzinfo=datetime.timezone.utc
            )
            # Add 23:59:59 to the end of to_date
            to_dt = to_dt + datetime.timedelta(days=1) - datetime.timedelta(seconds=1)

            query += " AND issued_at <= :to_ts AND expires_at >= :from_ts"
            params["from_ts"] = int(from_dt.timestamp())
            params["to_ts"] = int(to_dt.timestamp())
        except ValueError:
            raise HTTPException(
                status_code=400,
                detail={
                    "error": {
                        "code": "BAD_REQUEST",
                        "message": "Invalid date format, use YYYY-MM-DD",
                    }
                },
            )

    query += " ORDER BY issued_at DESC LIMIT 50"

    results = db.execute(text(query), params).mappings().all()

    out = []
    for r in results:
        out.append(
            AlertResponse(
                id=r["id"],
                region_id=r["region_id"],
                template_code=r["template_code"],
                severity=r["severity"],
                lat=r["lat"],
                lon=r["lon"],
                radius_m=r["radius_m"],
                issued_at=r["issued_at"],
                expires_at=r["expires_at"],
                extra_text=r["extra_text"],
                is_simulation=r["is_simulation"],
                wire=r["wire"],
            )
        )
    return out


@router.post("/alerts", response_model=AlertPostResponse)
@limiter.limit("30/minute")
def post_alert(
    req: AlertCreate,
    request: Request,
    user: dict = Depends(admin_user),
    db: Session = Depends(get_db),
):
    """POST /alerts (admin only)"""
    bbox = _get_region_bbox(req.region_id)
    if not bbox:
        raise HTTPException(
            status_code=400, detail={"error": {"code": "BAD_REQUEST", "message": "Invalid region"}}
        )

    # +5 km buffer approx 0.045 degrees
    buffer = 0.045
    min_lon, min_lat, max_lon, max_lat = bbox
    if not (
        min_lat - buffer <= req.lat <= max_lat + buffer
        and min_lon - buffer <= req.lon <= max_lon + buffer
    ):
        raise HTTPException(
            status_code=400,
            detail={"error": {"code": "BAD_REQUEST", "message": "Coordinates outside region"}},
        )

    if not (0 <= req.severity <= 3):
        raise HTTPException(
            status_code=400,
            detail={"error": {"code": "BAD_REQUEST", "message": "Invalid severity"}},
        )

    if not (100 <= req.radius_m <= 20000):
        raise HTTPException(
            status_code=400, detail={"error": {"code": "BAD_REQUEST", "message": "Invalid radius"}}
        )

    alert_id = _generate_id()
    now = int(time.time())

    expires_at = req.expires_at
    if expires_at is None:
        expires_at = now + (6 * 3600 if req.is_simulation else 24 * 3600)

    try:
        wire = build_alert(
            alert_id=alert_id,
            template_code=req.template_code,
            severity=req.severity,
            lat=req.lat,
            lon=req.lon,
            radius_m=req.radius_m,
            is_simulation=req.is_simulation,
            ts=now,
        )
    except ValueError as e:
        raise HTTPException(
            status_code=400, detail={"error": {"code": "BAD_REQUEST", "message": str(e)}}
        )

    db.execute(
        text("""
            INSERT INTO alerts (id, region_id, template_code, severity, lat, lon, radius_m, extra_text, is_simulation, issued_at, expires_at, wire, created_by)
            VALUES (:id, :region_id, :template_code, :severity, :lat, :lon, :radius_m, :extra_text, :is_simulation, :issued_at, :expires_at, :wire, :created_by)
        """),
        {
            "id": alert_id,
            "region_id": req.region_id,
            "template_code": req.template_code,
            "severity": req.severity,
            "lat": req.lat,
            "lon": req.lon,
            "radius_m": req.radius_m,
            "extra_text": req.extra_text,
            "is_simulation": req.is_simulation,
            "issued_at": now,
            "expires_at": expires_at,
            "wire": wire,
            "created_by": user.get("uid", "unknown"),
        },
    )
    db.commit()

    return AlertPostResponse(
        id=alert_id,
        region_id=req.region_id,
        template_code=req.template_code,
        severity=req.severity,
        lat=req.lat,
        lon=req.lon,
        radius_m=req.radius_m,
        issued_at=now,
        expires_at=expires_at,
        extra_text=req.extra_text,
        is_simulation=req.is_simulation,
        wire=wire,
        sms_recipients=0,
    )
