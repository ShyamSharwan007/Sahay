"""SQLAlchemy session + startup schema execution.

Uses a sync engine with psycopg (CONTRACTS §10 is Postgres on Supabase).
Pool settings: pool_pre_ping=True, pool_size=5.

Engine creation is lazy to allow tests to import the module without
a live database connection.
"""

from __future__ import annotations

import logging
from pathlib import Path

from app.config import settings

logger = logging.getLogger(__name__)

_engine = None
_SessionLocal = None


def _get_engine():
    """Lazy-create the SQLAlchemy engine."""
    global _engine
    if _engine is None:
        from sqlalchemy import create_engine

        _engine = create_engine(
            settings.DATABASE_URL,
            pool_pre_ping=True,
            pool_size=5,
        )
    return _engine


def _get_session_factory():
    """Lazy-create the session factory."""
    global _SessionLocal
    if _SessionLocal is None:
        from sqlalchemy.orm import sessionmaker

        _SessionLocal = sessionmaker(bind=_get_engine())
    return _SessionLocal


def get_db():
    """FastAPI dependency – yields a DB session and closes it after the request."""
    Session = _get_session_factory()
    db = Session()
    try:
        yield db
    finally:
        db.close()


# Columns added after the first release. Postgres only (ADD COLUMN IF NOT EXISTS); fresh databases get them from
# schema.sql. Each statement runs on its own so one failure cannot block the others.
MIGRATIONS = [
    "ALTER TABLE alerts ADD COLUMN IF NOT EXISTS expires_at BIGINT",
    "ALTER TABLE reports ADD COLUMN IF NOT EXISTS photo_data BYTEA",
    "ALTER TABLE reports ADD COLUMN IF NOT EXISTS photo_mime TEXT",
    "ALTER TABLE reports ADD COLUMN IF NOT EXISTS review_status TEXT NOT NULL DEFAULT 'pending'",
]


def run_migrations(engine) -> None:
    from sqlalchemy import text

    for statement in MIGRATIONS:
        try:
            with engine.begin() as conn:
                conn.execute(text(statement))
        except Exception:
            logger.warning("Migration failed: %s", statement, exc_info=True)


def init_db() -> None:
    """Run schema.sql against the database (CREATE IF NOT EXISTS, idempotent)."""
    schema_path = Path(__file__).parent / "schema.sql"
    sql_text = schema_path.read_text()
    try:
        from sqlalchemy import text

        engine = _get_engine()
        with engine.connect() as conn:
            # Execute each statement individually (psycopg needs this)
            for statement in sql_text.split(";"):
                stmt = statement.strip()
                if stmt:
                    conn.execute(text(stmt))
            conn.commit()
        run_migrations(engine)
        logger.info("Database schema initialized successfully")
    except Exception:
        logger.warning(
            "Could not initialize DB schema (database may be unavailable)",
            exc_info=True,
        )
