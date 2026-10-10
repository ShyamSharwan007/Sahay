"""Photo upload on hazard reports: validation, owner-only upload, review state and visibility."""

from __future__ import annotations

import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.auth import admin_user, current_user, optional_user
from app.db.session import get_db
from app.limiter import limiter
from app.main import app

JPEG = b"\xff\xd8\xff\xe0" + b"0" * 200
REPORT = {
    "type": "FL", "lat": 12.62, "lon": 80.19, "reporterLat": 12.62, "reporterLon": 80.19,
    "channel": "INTERNET",
}


@pytest.fixture()
def env():
    who: dict = {"user": {"uid": "owner1", "email": "o@example.com"}}
    engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    factory = sessionmaker(bind=engine, autoflush=False)
    schema = (Path(__file__).parent.parent / "app" / "db" / "schema.sql").read_text()
    with engine.begin() as conn:
        for statement in schema.split(";"):
            if statement.strip():
                conn.execute(text(statement.strip()))

    def db_override():
        db = factory()
        try:
            yield db
        finally:
            db.close()

    def user_or_401():
        return who["user"]

    previous = dict(app.dependency_overrides)
    app.dependency_overrides[get_db] = db_override
    app.dependency_overrides[current_user] = user_or_401
    app.dependency_overrides[optional_user] = lambda: who["user"]
    app.dependency_overrides[admin_user] = lambda: {"uid": "admin1", "email": "admin@test.com"}
    limiter.enabled = False
    yield TestClient(app, raise_server_exceptions=False), who, factory
    limiter.enabled = True
    app.dependency_overrides.clear()
    app.dependency_overrides.update(previous)


def _create_report(client) -> str:
    resp = client.post("/api/v1/reports", json={**REPORT, "createdAt": int(time.time())})
    assert resp.status_code == 200, resp.text
    assert resp.json()["reviewStatus"] is None
    return resp.json()["id"]


def _upload(client, report_id, data=JPEG, name="p.jpg", mime="image/jpeg"):
    return client.post(f"/api/v1/reports/{report_id}/photo", files={"file": (name, data, mime)})


def test_upload_sets_photo_url_and_pending(env):
    client, _, _ = env
    rid = _create_report(client)
    resp = _upload(client, rid)
    assert resp.status_code == 200
    body = resp.json()
    assert body["photoUrl"] == f"/api/v1/reports/{rid}/photo"
    assert body["reviewStatus"] == "pending"


def test_only_the_owner_can_upload(env):
    client, who, _ = env
    rid = _create_report(client)
    who["user"] = {"uid": "someone-else"}
    assert _upload(client, rid).status_code == 403


def test_rejects_big_and_non_image_files(env):
    client, _, _ = env
    rid = _create_report(client)
    assert _upload(client, rid, data=JPEG + b"0" * (1024 * 1024)).status_code == 413
    assert _upload(client, rid, data=b"not an image at all", name="p.jpg", mime="image/jpeg").status_code == 400
    assert _upload(client, "nope").status_code == 404


def test_png_and_webp_are_accepted(env):
    client, _, _ = env
    rid = _create_report(client)
    assert _upload(client, rid, data=b"\x89PNG\r\n\x1a\n" + b"0" * 50, mime="image/png").status_code == 200
    assert _upload(client, rid, data=b"RIFF\x00\x00\x00\x00WEBP" + b"0" * 50, mime="image/webp").status_code == 200


def test_pending_photo_is_private_until_approved(env):
    client, who, _ = env
    rid = _create_report(client)
    _upload(client, rid)

    assert client.get(f"/api/v1/reports/{rid}/photo").status_code == 200  # owner sees own photo
    who["user"] = None
    assert client.get(f"/api/v1/reports/{rid}/photo").status_code == 404  # public: not yet

    assert client.post(f"/api/v1/admin/reports/{rid}/approve").status_code == 200
    resp = client.get(f"/api/v1/reports/{rid}/photo")
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "image/jpeg"
    assert resp.content == JPEG


def test_rejected_photo_stays_hidden_from_public(env):
    client, who, _ = env
    rid = _create_report(client)
    _upload(client, rid)
    assert client.post(f"/api/v1/admin/reports/{rid}/reject").status_code == 200
    who["user"] = None
    assert client.get(f"/api/v1/reports/{rid}/photo").status_code == 404
    listed = client.get("/api/v1/admin/reports?regionId=mahabalipuram").json()
    assert next(r for r in listed if r["id"] == rid)["reviewStatus"] == "rejected"


def test_approve_needs_a_photo(env):
    client, _, _ = env
    rid = _create_report(client)
    assert client.post(f"/api/v1/admin/reports/{rid}/approve").status_code == 404


def test_high_trust_report_is_auto_approved(env):
    client, who, factory = env
    now = int(time.time())
    with factory() as db:  # an active official flood alert here, and three other people reporting the same
        db.execute(
            text(
                "INSERT INTO alerts (id, region_id, template_code, severity, lat, lon, radius_m, is_simulation, "
                "issued_at, expires_at, wire, created_by) VALUES ('a1','mahabalipuram','FLD_WARN',2,12.62,80.19,"
                "5000,0,:now,:later,'w','x')"
            ),
            {"now": now, "later": now + 3600},
        )
        for i in range(3):
            db.execute(
                text(
                    "INSERT INTO reports (id, uid, region_id, type, lat, lon, reporter_lat, reporter_lon, "
                    "created_at, channel) VALUES (:id,:uid,'mahabalipuram','FL',12.62,80.19,12.62,80.19,:now,'INTERNET')"
                ),
                {"id": f"o{i}", "uid": f"other{i}", "now": now},
            )
        db.commit()
    rid = _create_report(client)
    body = _upload(client, rid).json()
    assert body["trustScore"] >= 0.7
    assert body["reviewStatus"] == "approved"
    who["user"] = None
    assert client.get(f"/api/v1/reports/{rid}/photo").status_code == 200
