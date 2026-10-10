import time
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app, raise_server_exceptions=False)

def mock_verify_id_token(token):
    return {"uid": "testuid", "email": "user@example.com"}

@patch("app.auth.verify_id_token", side_effect=mock_verify_id_token)
def test_post_report(mock_verify):
    # Missing auth
    resp = client.post("/api/v1/reports", json={
        "type": "FL", "lat": 12.6, "lon": 80.2, "reporterLat": 12.6, "reporterLon": 80.2, "channel": "INTERNET", "createdAt": int(time.time())
    })
    assert resp.status_code == 401

    # Valid request
    resp = client.post("/api/v1/reports", json={
        "type": "FL", "lat": 12.6, "lon": 80.19, "reporterLat": 12.6, "reporterLon": 80.19, "channel": "INTERNET", "createdAt": int(time.time())
    }, headers={"Authorization": "Bearer fake"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["type"] == "FL"
    assert data["mine"] is True
    assert data["trustScore"] >= 0.0

    # Invalid type
    resp = client.post("/api/v1/reports", json={
        "type": "INVALID", "lat": 12.6, "lon": 80.19, "reporterLat": 12.6, "reporterLon": 80.19, "channel": "INTERNET", "createdAt": int(time.time())
    }, headers={"Authorization": "Bearer fake"})
    assert resp.status_code == 400

@patch("app.auth.verify_id_token", side_effect=mock_verify_id_token)
def test_get_reports(mock_verify):
    resp = client.get("/api/v1/reports?regionId=mahabalipuram", headers={"Authorization": "Bearer fake"})
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)
