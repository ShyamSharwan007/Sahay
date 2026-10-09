"""Admin UI router — serves the admin dashboard HTML."""

import os
from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import HTMLResponse

router = APIRouter()


@router.get("/admin", response_class=HTMLResponse)
def get_admin_ui():
    """GET /admin — serve the admin.html file and inject env vars."""
    static_path = Path(__file__).resolve().parent.parent / "static" / "admin.html"
    if not static_path.exists():
        return HTMLResponse(content="<h1>Admin UI missing</h1>", status_code=404)

    html = static_path.read_text(encoding="utf-8")

    # Inject Firebase web config from env vars
    html = html.replace("__FIREBASE_API_KEY__", os.environ.get("FIREBASE_WEB_API_KEY", ""))
    html = html.replace("__FIREBASE_AUTH_DOMAIN__", os.environ.get("FIREBASE_WEB_AUTH_DOMAIN", ""))
    html = html.replace("__FIREBASE_PROJECT_ID__", os.environ.get("FIREBASE_WEB_PROJECT_ID", ""))
    html = html.replace("__FIREBASE_APP_ID__", os.environ.get("FIREBASE_WEB_APP_ID", ""))

    return HTMLResponse(content=html)
