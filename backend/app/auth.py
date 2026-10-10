"""Firebase authentication helpers.

Provides FastAPI dependencies:
- current_user: requires a valid Firebase ID token
- optional_user: returns None if no token is present
- admin_user:   requires a valid token whose email is in ADMIN_EMAILS
"""

from __future__ import annotations

import base64
import json
import logging
from typing import Any

from fastapi import Depends, HTTPException, Request

from app.config import settings

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Firebase Admin SDK initialisation (lazy, so tests can skip it)
# ---------------------------------------------------------------------------
_firebase_app = None


def _get_firebase_app():
    global _firebase_app
    if _firebase_app is not None:
        return _firebase_app

    try:
        import firebase_admin
        from firebase_admin import credentials

        if settings.FIREBASE_SERVICE_ACCOUNT_B64:
            sa_json = base64.b64decode(settings.FIREBASE_SERVICE_ACCOUNT_B64)
            sa_info = json.loads(sa_json)
            cred = credentials.Certificate(sa_info)
        else:
            # Fall back to application-default credentials
            cred = credentials.ApplicationDefault()

        _firebase_app = firebase_admin.initialize_app(cred)
    except Exception:
        logger.warning("Firebase Admin SDK not initialised – auth will reject all tokens")
    return _firebase_app


def _extract_token(request: Request) -> str | None:
    """Pull the Bearer token from the Authorization header."""
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        return auth[7:]
    return None


def verify_id_token(token: str) -> dict[str, Any]:
    """Verify a Firebase ID token. Raises HTTPException on failure."""
    _get_firebase_app()
    try:
        from firebase_admin import auth as fb_auth

        return fb_auth.verify_id_token(token)
    except Exception as exc:
        raise HTTPException(
            status_code=401,
            detail={"error": {"code": "UNAUTHORIZED", "message": str(exc)}},
        ) from exc


async def current_user(request: Request) -> dict[str, Any]:
    """Dependency: require a valid Firebase user."""
    token = _extract_token(request)
    if not token:
        raise HTTPException(
            status_code=401,
            detail={"error": {"code": "UNAUTHORIZED", "message": "Missing Authorization header"}},
        )
    return verify_id_token(token)


async def optional_user(request: Request) -> dict[str, Any] | None:
    """Dependency: return user info if a token is present, else None."""
    token = _extract_token(request)
    if not token:
        return None
    try:
        return verify_id_token(token)
    except HTTPException:
        return None


async def admin_user(user: dict[str, Any] = Depends(current_user)) -> dict[str, Any]:
    """Dependency: require that the authenticated user's email is in ADMIN_EMAILS."""
    email = (user.get("email") or "").lower()
    if not email or email not in settings.admin_email_set:
        raise HTTPException(
            status_code=403,
            detail={"error": {"code": "FORBIDDEN", "message": "Admin access required"}},
        )
    return user
