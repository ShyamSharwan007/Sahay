"""Sahay FastAPI application factory.

- All routers mounted under /api/v1
- CORS from CORS_ORIGINS env var
- Structured error handler → {"error": {"code", "message"}}
- Request logging with timing
- DB schema initialisation at startup
"""

from __future__ import annotations

import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import settings
from app.db.session import init_db
from app.routers import health

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger("sahay")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup / shutdown lifecycle."""
    logger.info("Sahay backend starting up…")
    init_db()
    yield
    logger.info("Sahay backend shutting down…")


def create_app() -> FastAPI:
    """Build and return the configured FastAPI application."""
    application = FastAPI(
        title="Sahay API",
        description="Disaster-safety backend for foreign tourists",
        version="0.1.0",
        lifespan=lifespan,
    )

    # --- CORS ---
    application.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # --- Request logging ---
    @application.middleware("http")
    async def log_requests(request: Request, call_next):
        start = time.perf_counter()
        response = await call_next(request)
        elapsed_ms = (time.perf_counter() - start) * 1000
        logger.info(
            "%s %s → %d (%.1f ms)",
            request.method,
            request.url.path,
            response.status_code,
            elapsed_ms,
        )
        return response

    # --- Global error handler ---
    @application.exception_handler(Exception)
    async def global_error_handler(request: Request, exc: Exception):
        """Always return {"error": {"code", "message"}} — CONTRACTS §3."""
        logger.exception("Unhandled exception on %s %s", request.method, request.url.path)
        return JSONResponse(
            status_code=500,
            content={
                "error": {"code": "INTERNAL_ERROR", "message": "An unexpected error occurred"}
            },
        )

    # --- Routers under /api/v1 ---
    application.include_router(health.router, prefix="/api/v1")

    return application


# The ASGI app Uvicorn imports
app = create_app()
