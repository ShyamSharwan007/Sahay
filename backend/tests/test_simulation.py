"""Simulation end to end: simulate → GET /alerts → end simulation → gone; admin list and delete."""

from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.auth import admin_user
from app.config import settings
from app.db.session import get_db
from app.main import app

REGION = "mahabalipuram"


@pytest.fixture()
def client(test_keypair, monkeypatch):
    _, _, priv_b64, pub_b64 = test_keypair
    monkeypatch.setattr(settings, "SIGNING_PRIVATE_KEY_B64", priv_b64)
    monkeypatch.setattr(settings, "SIGNING_PUBLIC_KEY_B64", pub_b64)
    # No pack download in tests: the shelter step is best effort.
    monkeypatch.setattr("app.routers.admin._get_sqlite_url", lambda region_id: None)

    engine = create_engine(
        "sqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    session_factory = sessionmaker(bind=engine, autoflush=False)
    schema = (Path(__file__).parent.parent / "app" / "db" / "schema.sql").read_text()
    with engine.begin() as conn:
        for statement in schema.split(";"):
            if statement.strip():
                conn.execute(text(statement.strip()))

    def db_override():
        db = session_factory()
        try:
            yield db
        finally:
            db.close()

    previous = dict(app.dependency_overrides)
    app.dependency_overrides[get_db] = db_override
    app.dependency_overrides[admin_user] = lambda: {"uid": "admin1", "email": "admin@test.com"}
    yield TestClient(app, raise_server_exceptions=False)
    app.dependency_overrides.clear()
    app.dependency_overrides.update(previous)


def _simulate(client):
    return client.post("/api/v1/admin/simulate", json={"regionId": REGION, "scenario": "cyclone"})


def test_simulate_creates_signed_simulation_alerts(client):
    resp = _simulate(client)
    assert resp.status_code == 200
    alerts = resp.json()["alerts"]
    assert {a["templateCode"] for a in alerts} == {"CYC_WARN", "FLD_EVAC"}
    for a in alerts:
        assert a["isSimulation"] is True
        assert a["regionId"] == REGION
        assert a["expiresAt"] - a["issuedAt"] == 6 * 3600
        assert a["wire"].startswith("SH1*A*") and len(a["wire"]) <= 160


def test_simulate_then_get_alerts_then_end(client):
    created = {a["id"] for a in _simulate(client).json()["alerts"]}

    listed = client.get(f"/api/v1/alerts?region={REGION}").json()
    assert created <= {a["id"] for a in listed}
    assert all(a["expiresAt"] > a["issuedAt"] for a in listed)

    ended = client.post("/api/v1/admin/simulate/end", json={"regionId": REGION})
    assert ended.status_code == 200
    assert ended.json()["ended"] == 2

    assert client.get(f"/api/v1/alerts?region={REGION}").json() == []


def test_simulation_only_touches_its_own_region(client):
    _simulate(client)
    other = client.get("/api/v1/alerts?region=chennai-central").json()
    assert other == []


def test_admin_list_shows_active_and_expired(client):
    _simulate(client)
    active = client.get(f"/api/v1/admin/alerts?regionId={REGION}").json()
    assert len(active) == 2 and all(a["active"] for a in active)

    client.post("/api/v1/admin/simulate/end", json={"regionId": REGION})
    expired = client.get(f"/api/v1/admin/alerts?regionId={REGION}").json()
    assert len(expired) == 2 and not any(a["active"] for a in expired)


def test_delete_alert(client):
    alert_id = _simulate(client).json()["alerts"][0]["id"]
    assert client.delete(f"/api/v1/admin/alerts/{alert_id}").status_code == 200
    assert alert_id not in {a["id"] for a in client.get(f"/api/v1/alerts?region={REGION}").json()}
    assert client.delete(f"/api/v1/admin/alerts/{alert_id}").status_code == 404


def test_delete_alert_requires_admin(client):
    app.dependency_overrides.pop(admin_user)
    assert client.delete("/api/v1/admin/alerts/abc123").status_code == 401
    assert client.post("/api/v1/admin/simulate/end", json={"regionId": REGION}).status_code == 401
