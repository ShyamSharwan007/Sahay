"""Shelters router (CONTRACTS §3)."""

import time

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.auth import admin_user
from app.db.session import get_db
from app.limiter import limiter
from app.models import ShelterStatusResponse, ShelterStatusUpdate
from app.wire import build_shelter

router = APIRouter()


@router.get("/shelters/status", response_model=list[ShelterStatusResponse])
@limiter.limit("120/minute")
def get_shelter_status(
    request: Request,
    region_id: str = Query(..., alias="regionId"),
    db: Session = Depends(get_db),
):
    """GET /shelters/status?regionId"""
    results = (
        db.execute(
            text("SELECT * FROM shelter_status WHERE region_id = :region_id"),
            {"region_id": region_id},
        )
        .mappings()
        .all()
    )

    return [
        ShelterStatusResponse(
            shelter_id=r["shelter_id"],
            status=r["status"],
            updated_at=r["updated_at"],
            wire=r["wire"],
        )
        for r in results
    ]


@router.post("/admin/shelters/{shelter_id}/status", response_model=ShelterStatusResponse)
@limiter.limit("30/minute")
def update_shelter_status(
    shelter_id: str,
    req: ShelterStatusUpdate,
    request: Request,
    user: dict = Depends(admin_user),
    db: Session = Depends(get_db),
):
    """POST /admin/shelters/{shelterId}/status"""
    if req.status not in ["OPEN", "FULL", "CLOSED"]:
        raise HTTPException(
            status_code=400, detail={"error": {"code": "BAD_REQUEST", "message": "Invalid status"}}
        )

    now = int(time.time())
    wire_status = req.status[0]  # O, F, C

    try:
        wire = build_shelter(shelter_id=shelter_id, status=wire_status, ts=now)
    except ValueError as e:
        raise HTTPException(
            status_code=400, detail={"error": {"code": "BAD_REQUEST", "message": str(e)}}
        )

    db.execute(
        text("""
            INSERT INTO shelter_status (shelter_id, region_id, status, updated_at, wire)
            VALUES (:shelter_id, :region_id, :status, :updated_at, :wire)
            ON CONFLICT (shelter_id) DO UPDATE SET
                status = EXCLUDED.status,
                updated_at = EXCLUDED.updated_at,
                wire = EXCLUDED.wire
        """),
        {
            "shelter_id": shelter_id,
            "region_id": req.region_id,
            "status": req.status,
            "updated_at": now,
            "wire": wire,
        },
    )
    db.commit()

    return ShelterStatusResponse(
        shelter_id=shelter_id, status=req.status, updated_at=now, wire=wire
    )
