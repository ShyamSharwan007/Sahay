"""Packs router for manifest endpoints (CONTRACTS §5.1)."""

import datetime
import json
import logging
import os
from pathlib import Path

from fastapi import APIRouter, HTTPException, Query, Request

from app.models import ManifestResponse
from app.routers.health import _REGIONS
from app.services.weather import CONTENT_DIR, fetch_forecast, fetch_history

logger = logging.getLogger(__name__)
router = APIRouter()

PACKS_JSON = Path(__file__).resolve().parent.parent / "data" / "packs.json"


def _lookup_region(region_id: str) -> dict | None:
    """Find a region entry in regions.json by id."""
    for r in _REGIONS:
        if r["id"] == region_id:
            return r
    return None


def _load_packs() -> dict:
    """Load packs.json as a dict keyed by regionId.

    Returns an empty dict on missing / invalid file.
    """
    if not PACKS_JSON.exists():
        return {}
    try:
        data = json.loads(PACKS_JSON.read_text())
        if isinstance(data, dict):
            return data
    except Exception:
        pass
    return {}


@router.get("/packs/{region_id}/manifest", response_model=ManifestResponse)
async def get_pack_manifest(
    region_id: str,
    request: Request,
    start: str = Query(...),
    end: str = Query(...),
):
    """GET /packs/{regionId}/manifest?start=YYYY-MM-DD&end=YYYY-MM-DD"""

    # 1. Date validation
    try:
        start_dt = datetime.datetime.strptime(start, "%Y-%m-%d")
        end_dt = datetime.datetime.strptime(end, "%Y-%m-%d")
    except ValueError:
        raise HTTPException(
            status_code=400,
            detail={
                "error": {
                    "code": "BAD_REQUEST",
                    "message": "Invalid date format, expected YYYY-MM-DD",
                }
            },
        )

    if start_dt > end_dt:
        raise HTTPException(
            status_code=400,
            detail={"error": {"code": "BAD_REQUEST", "message": "start must be <= end"}},
        )

    if (end_dt - start_dt).days > 30:
        raise HTTPException(
            status_code=400,
            detail={
                "error": {"code": "BAD_REQUEST", "message": "Date range cannot exceed 30 days"}
            },
        )

    # 2. Read packs.json (object keyed by regionId, written by pipeline/publish.py)
    packs = _load_packs()
    if not packs:
        raise HTTPException(
            status_code=404,
            detail={"error": {"code": "pack_not_ready", "message": "No packs available"}},
        )

    pack = packs.get(region_id)
    if not pack:
        raise HTTPException(
            status_code=404,
            detail={
                "error": {
                    "code": "pack_not_ready",
                    "message": f"Region {region_id} not found or not built",
                }
            },
        )

    # 3. Look up regionName and bbox from regions.json (not stored in packs.json)
    region = _lookup_region(region_id)
    if not region:
        raise HTTPException(
            status_code=404,
            detail={
                "error": {"code": "pack_not_ready", "message": f"Region {region_id} not configured"}
            },
        )

    bbox = region["bbox"]

    center_lon = (bbox[0] + bbox[2]) / 2.0
    center_lat = (bbox[1] + bbox[3]) / 2.0

    # 3. Weather & History
    forecast_data = await fetch_forecast(center_lat, center_lon, start, end)
    history_data = await fetch_history(center_lat, center_lon, start, end)

    # Analyze forecast to find triggers for precautions
    triggers = {"always"}
    for day in forecast_data:
        r = day.get("rainMm", 0.0)
        w = day.get("windKmh", 0.0)
        if r >= 204.5:
            triggers.add("extreme_rain")
        elif r >= 115.6:
            triggers.add("very_heavy_rain")
        elif r >= 64.5:
            triggers.add("heavy_rain")

        if w >= 62.0:
            triggers.add("cyclone_wind")

    # Check monsoon season (Oct-Dec)
    if start_dt.month in (10, 11, 12) or end_dt.month in (10, 11, 12):
        triggers.add("monsoon_season")

    # 4. Read Precautions
    precautions_list = []
    precautions_file = CONTENT_DIR / "precautions.json"
    if precautions_file.exists():
        try:
            all_prec = json.loads(precautions_file.read_text())
            for p in all_prec:
                if p.get("trigger") in triggers:
                    precautions_list.append(
                        {
                            "id": p["id"],
                            "severity": p["severity"],
                            "title": p["title"],
                            "body": p["body"],
                        }
                    )
        except Exception:
            pass

    # 5. Read Incidents
    incidents_list = []
    incidents_file = CONTENT_DIR / "incidents" / f"{region_id}.json"
    if incidents_file.exists():
        try:
            incidents_list = json.loads(incidents_file.read_text())
        except Exception:
            pass

    # 6. Build Manifest
    return ManifestResponse(
        region_id=region_id,
        region_name=region["name"],
        pack_version=pack.get("packVersion", "unknown"),
        bbox=bbox,
        sqlite_url=pack.get("sqliteUrl"),
        sqlite_bytes=pack.get("sqliteBytes"),
        sqlite_sha256=pack.get("sqliteSha256"),
        pmtiles_url=pack.get("pmtilesUrl"),
        pmtiles_bytes=pack.get("pmtilesBytes"),
        style_light_url=pack.get("styleLightUrl"),
        style_dark_url=pack.get("styleDarkUrl"),
        assets_zip_url=pack.get("assetsZipUrl"),
        assets_zip_bytes=pack.get("assetsZipBytes"),
        public_key_b64=os.environ.get("SIGNING_PUBLIC_KEY_B64", ""),
        forecast=forecast_data,
        history=history_data,
        incidents=incidents_list,
        precautions=precautions_list,
    )
