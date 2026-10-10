"""Tests for the packs router."""

import pytest
import respx
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app, raise_server_exceptions=False)

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
                    "temperature_2m_max": [31.5]
                }
            }
        )

        # Mock history
        respx_mock.get(url__regex=r"https://archive-api\.open-meteo\.com/v1/archive.*").respond(
            status_code=200,
            json={
                "daily": {
                    "time": ["2025-10-10"],
                    "precipitation_sum": [5.0]
                }
            }
        )
        yield respx_mock

def test_get_pack_manifest(mock_open_meteo):
    start = "2026-10-10"
    end = "2026-10-12"

    # Needs valid region_id from packs.json
    resp = client.get(f"/api/v1/packs/mahabalipuram/manifest?start={start}&end={end}")

    assert resp.status_code == 200
    data = resp.json()
    assert data["regionId"] == "mahabalipuram"
    assert "forecast" in data
    assert "history" in data
    assert "incidents" in data
    assert "precautions" in data
    assert data["publicKeyB64"] is not None

def test_get_pack_manifest_invalid_dates():
    resp = client.get("/api/v1/packs/mahabalipuram/manifest?start=2026-10-10&end=2026-11-20")
    assert resp.status_code == 400
    assert resp.json()["error"]["code"] == "BAD_REQUEST"

def test_get_pack_manifest_invalid_region():
    resp = client.get("/api/v1/packs/invalid_region/manifest?start=2026-10-10&end=2026-10-12")
    assert resp.status_code == 404
    assert resp.json()["error"]["code"] == "pack_not_ready"
