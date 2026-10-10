"""Tests for new endpoints: alerts, shelters, admin, admin_ui."""

from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.db.session import get_db
from app.main import app

client = TestClient(app, raise_server_exceptions=False)

# Setup in-memory sqlite
test_engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False})
TestSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=test_engine)


def mock_get_db():
    db = TestSessionLocal()
    try:
        from pathlib import Path

        from sqlalchemy import text

        schema_path = Path(__file__).parent.parent / "app" / "db" / "schema.sql"
        for stmt in schema_path.read_text().split(";"):
            if stmt.strip():
                db.execute(text(stmt.strip()))
        db.commit()
        yield db
    finally:
        db.close()


app.dependency_overrides[get_db] = mock_get_db


def test_admin_ui():
    resp = client.get("/admin")
    assert resp.status_code == 200
    assert "text/html" in resp.headers["content-type"]
    assert "Sahay Admin Dashboard" in resp.text


# We mock Firebase auth for the rest
def test_get_alert_templates():
    resp = client.get("/api/v1/alert-templates?lang=en")
    assert resp.status_code == 200
    assert len(resp.json()) == 20
    assert resp.json()[0]["code"] == "RAIN_HVY"


def test_get_alerts():
    resp = client.get("/api/v1/alerts")
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


def test_get_shelters():
    resp = client.get("/api/v1/shelters/status?regionId=mahabalipuram")
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


def test_admin_overview_no_auth():
    resp = client.get("/api/v1/admin/overview?regionId=mahabalipuram")
    assert resp.status_code == 401


def test_post_alert_no_auth():
    resp = client.post(
        "/api/v1/alerts",
        json={
            "regionId": "mahabalipuram",
            "templateCode": "TEST",
            "severity": 0,
            "lat": 12.6,
            "lon": 80.2,
            "radiusM": 1000,
            "isSimulation": True,
        },
    )
    assert resp.status_code == 401


# The rest of auth requires more complex mocking of the database session
# and firebase auth which is better done via mock patches.
