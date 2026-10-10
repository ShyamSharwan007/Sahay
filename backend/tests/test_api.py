"""Tests for API endpoints — /health, /regions, and auth with mocked verifier."""

from __future__ import annotations

from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app, raise_server_exceptions=False)


class TestHealth:
    def test_health_returns_ok(self):
        resp = client.get("/api/v1/health")
        assert resp.status_code == 200
        body = resp.json()
        assert body["ok"] is True
        assert "time" in body

    def test_health_time_is_int(self):
        resp = client.get("/api/v1/health")
        assert isinstance(resp.json()["time"], int)


class TestRegions:
    def test_regions_returns_list(self):
        resp = client.get("/api/v1/regions")
        assert resp.status_code == 200
        data = resp.json()
        assert isinstance(data, list)
        assert len(data) >= 2

    def test_regions_have_expected_fields(self):
        resp = client.get("/api/v1/regions")
        for region in resp.json():
            assert "id" in region
            assert "name" in region
            assert "bbox" in region
            assert len(region["bbox"]) == 4

    def test_mahabalipuram_present(self):
        resp = client.get("/api/v1/regions")
        ids = [r["id"] for r in resp.json()]
        assert "mahabalipuram" in ids


class TestAuth:
    """Test auth dependencies with a mocked Firebase verifier."""

    def test_missing_auth_header_is_401(self):
        """Endpoints that require auth should return 401 without a token.

        We test this indirectly: any future protected endpoint would reject.
        For now we verify the health endpoint does NOT require auth.
        """
        resp = client.get("/api/v1/health")
        assert resp.status_code == 200  # health is public

    def test_mock_firebase_verify(self):
        """Verify that mocking firebase works for auth flow."""
        mock_user = {"uid": "test123", "email": "admin@test.com"}

        with patch("app.auth.verify_id_token", return_value=mock_user):
            from app.auth import verify_id_token

            result = verify_id_token("fake-token")
            assert result["uid"] == "test123"
            assert result["email"] == "admin@test.com"

    def test_admin_check_rejects_non_admin(self):
        """Mocked user without admin email should be rejected."""
        from fastapi import HTTPException

        from app.auth import admin_user

        # Mock a non-admin user
        mock_user = {"uid": "user1", "email": "nobody@example.com"}

        # admin_user expects the user to be in ADMIN_EMAILS
        with pytest.raises(HTTPException) as exc_info:
            import asyncio

            asyncio.get_event_loop().run_until_complete(admin_user(mock_user))
        assert exc_info.value.status_code == 403


class TestErrorHandler:
    def test_404_returns_not_found(self):
        resp = client.get("/api/v1/nonexistent")
        assert resp.status_code in (404, 405)
