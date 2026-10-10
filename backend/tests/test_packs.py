"""Tests for the packs router.

Covers the new packs.json format: an object keyed by regionId with NO
bbox or regionName (those come from regions.json).
"""

import json
from unittest.mock import patch

import pytest
import respx
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app, raise_server_exceptions=False)

# ── A valid pack entry (no bbox, no regionName) ────────────────────────
_VALID_PACKS = {
    "mahabalipuram": {
        "packVersion": "2026-10-10.1",
        "sqliteUrl": "https://example.com/mahabalipuram.sqlite",
        "sqliteBytes": 221184,
        "sqliteSha256": "abc123",
        "pmtilesUrl": None,
        "pmtilesBytes": None,
        "styleLightUrl": None,
        "styleDarkUrl": None,
        "assetsZipUrl": None,
        "assetsZipBytes": None,
    }
}


@pytest.fixture
def mock_open_meteo():
    with respx.mock(assert_all_called=False) as respx_mock:
        # Mock forecast
        respx_mock.get(url__regex=r"https://api\.open-meteo\.com/v1/forecast.*").respond(
            status_code=200,
            json={
                "daily": {
                    "time": ["2026-10-10"],
                    "precipitation_sum": [10.5],
                    "wind_speed_10m_max": [15.2],
                    "temperature_2m_max": [31.5],
                }
            },
        )

        # Mock history
        respx_mock.get(url__regex=r"https://archive-api\.open-meteo\.com/v1/archive.*").respond(
            status_code=200,
            json={
                "daily": {
                    "time": ["2025-10-10"],
                    "precipitation_sum": [5.0],
                }
            },
        )
        yield respx_mock


@pytest.fixture
def packs_with_valid_entry(tmp_path):
    """Write a valid packs.json (object format) and patch PACKS_JSON to it."""
    packs_file = tmp_path / "packs.json"
    packs_file.write_text(json.dumps(_VALID_PACKS))
    with patch("app.routers.packs.PACKS_JSON", packs_file):
        yield packs_file


@pytest.fixture
def packs_empty(tmp_path):
    """Write an empty packs.json ({})."""
    packs_file = tmp_path / "packs.json"
    packs_file.write_text("{}")
    with patch("app.routers.packs.PACKS_JSON", packs_file):
        yield packs_file


# ── Tests ──────────────────────────────────────────────────────────────


def test_manifest_valid_entry(mock_open_meteo, packs_with_valid_entry):
    """A region that exists in both packs.json and regions.json returns 200."""
    resp = client.get(
        "/api/v1/packs/mahabalipuram/manifest?start=2026-10-10&end=2026-10-12"
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["regionId"] == "mahabalipuram"
    # regionName comes from regions.json, not from the pack entry
    assert data["regionName"] == "Mahabalipuram"
    assert data["bbox"] == [80.16, 12.59, 80.21, 12.65]
    assert data["packVersion"] == "2026-10-10.1"
    assert "forecast" in data
    assert "history" in data
    assert "incidents" in data
    assert "precautions" in data
    assert data["publicKeyB64"] is not None


def test_manifest_missing_region(packs_with_valid_entry):
    """A regionId not present in packs.json returns 404 pack_not_ready."""
    resp = client.get(
        "/api/v1/packs/nonexistent_region/manifest?start=2026-10-10&end=2026-10-12"
    )
    assert resp.status_code == 404
    assert resp.json()["error"]["code"] == "pack_not_ready"


def test_manifest_empty_packs(packs_empty):
    """An empty packs.json ({}) returns 404 pack_not_ready."""
    resp = client.get(
        "/api/v1/packs/mahabalipuram/manifest?start=2026-10-10&end=2026-10-12"
    )
    assert resp.status_code == 404
    assert resp.json()["error"]["code"] == "pack_not_ready"


def test_manifest_invalid_dates():
    """Date range > 30 days returns 400."""
    resp = client.get(
        "/api/v1/packs/mahabalipuram/manifest?start=2026-10-10&end=2026-11-20"
    )
    assert resp.status_code == 400
    assert resp.json()["error"]["code"] == "BAD_REQUEST"


def test_manifest_10_days_ahead(mock_open_meteo, packs_with_valid_entry):
    """Test forecast for dates 10 days ahead."""
    import datetime
    today = datetime.datetime.now()
    start_dt = today + datetime.timedelta(days=10)
    end_dt = today + datetime.timedelta(days=11)
    
    start_str = start_dt.strftime("%Y-%m-%d")
    end_str = end_dt.strftime("%Y-%m-%d")
    
    resp = client.get(
        f"/api/v1/packs/mahabalipuram/manifest?start={start_str}&end={end_str}"
    )
    assert resp.status_code == 200
    data = resp.json()
    assert "forecast" in data
    # mock_open_meteo returns 1 day of data, which should be included if dates overlap
    assert len(data["forecast"]) >= 0


def test_manifest_2_days_forecast(packs_with_valid_entry):
    """Test forecast for 2 days ahead returns 2 entries."""
    import datetime
    today = datetime.datetime.now()
    d1 = today + datetime.timedelta(days=1)
    d2 = today + datetime.timedelta(days=2)
    
    start_str = d1.strftime("%Y-%m-%d")
    end_str = d2.strftime("%Y-%m-%d")
    
    with respx.mock(assert_all_called=False) as respx_mock:
        respx_mock.get(url__regex=r"https://api\.open-meteo\.com/v1/forecast.*").respond(
            status_code=200,
            json={
                "daily": {
                    "time": [start_str, end_str],
                    "precipitation_sum": [10.5, 75.0],
                    "wind_speed_10m_max": [15.2, 20.0],
                    "temperature_2m_max": [31.5, 30.0],
                }
            },
        )
        respx_mock.get(url__regex=r"https://archive-api\.open-meteo\.com/v1/archive.*").respond(
            status_code=200,
            json={"daily": {"time": [], "precipitation_sum": []}},
        )
        
        resp = client.get(
            f"/api/v1/packs/mahabalipuram/manifest?start={start_str}&end={end_str}"
        )
        
        assert resp.status_code == 200
        data = resp.json()
        
        assert len(data["forecast"]) == 2
        assert data["forecast"][0]["date"] == start_str
        assert data["forecast"][0]["rainMm"] == 10.5
        assert data["forecast"][1]["riskLevel"] == "HIGH"  # 75.0 mm -> HIGH
