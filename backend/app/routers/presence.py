import time

from fastapi import APIRouter, Depends, Request
from fastapi.responses import Response
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.auth import current_user
from app.db.session import get_db
from app.limiter import limiter
from app.models import PresenceCreate

router = APIRouter()


@router.post("/presence", status_code=204)
@limiter.limit("30/minute")
def post_presence(
    req: PresenceCreate,
    request: Request,
    user: dict = Depends(current_user),
    db: Session = Depends(get_db),
):
    """POST /presence (user): upsert presence, clean old rows."""
    now = int(time.time())
    uid = user.get("uid")
    if not uid:
        return Response(status_code=401)

    # Round to 3 decimals
    lat = round(req.lat, 3)
    lon = round(req.lon, 3)

    db.execute(
        text("""
            INSERT INTO presence (uid, lat, lon, updated_at)
            VALUES (:uid, :lat, :lon, :now)
            ON CONFLICT (uid) DO UPDATE SET
                lat = EXCLUDED.lat,
                lon = EXCLUDED.lon,
                updated_at = EXCLUDED.updated_at
        """),
        {"uid": uid, "lat": lat, "lon": lon, "now": now},
    )

    # Delete older than 30 min
    cutoff = now - 30 * 60
    db.execute(text("DELETE FROM presence WHERE updated_at < :cutoff"), {"cutoff": cutoff})

    db.commit()
    return Response(status_code=204)
