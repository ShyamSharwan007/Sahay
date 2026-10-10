from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app, raise_server_exceptions=False)

def mock_verify_id_token(token):
    return {"uid": "testuid", "email": "user@example.com"}

@patch("app.auth.verify_id_token", side_effect=mock_verify_id_token)
def test_post_presence(mock_verify):
    resp = client.post("/api/v1/presence", json={"lat": 12.123, "lon": 80.123}, headers={"Authorization": "Bearer fake"})
    assert resp.status_code == 204

    # Missing auth
    resp = client.post("/api/v1/presence", json={"lat": 12.123, "lon": 80.123})
    assert resp.status_code == 401
