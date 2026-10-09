"""Health and regions endpoints — no auth required."""

from __future__ import annotations

import json
import time
from pathlib import Path

from fastapi import APIRouter, Request

from app.limiter import limiter

router = APIRouter()

# Load regions from the static JSON file
_REGIONS_PATH = Path(__file__).resolve().parent.parent / "data" / "regions.json"
_REGIONS: list[dict] = json.loads(_REGIONS_PATH.read_text())


@router.get("/health")
@limiter.limit("120/minute")
async def health(request: Request):
    """Simple liveness probe — CONTRACTS §3: GET /health."""
    return {"ok": True, "time": int(time.time())}


@router.get("/regions")
@limiter.limit("120/minute")
async def regions(request: Request):
    """Return the list of supported regions — CONTRACTS §3: GET /regions."""
    return _REGIONS
