"""Sahay backend configuration loaded from environment variables.

Maps to CONTRACTS §11 env vars (excluding SMS_* which are handled separately).
"""

from __future__ import annotations

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """All env vars from CONTRACTS §11 (minus SMS_* ones)."""

    # --- Postgres (Supabase) ---
    DATABASE_URL: str = "postgresql+psycopg://localhost/sahay"

    # --- Firebase Admin ---
    FIREBASE_SERVICE_ACCOUNT_B64: str = ""

    # --- Ed25519 signing (raw 32-byte keys, standard base64) ---
    SIGNING_PRIVATE_KEY_B64: str = ""
    SIGNING_PUBLIC_KEY_B64: str = ""

    # --- Access control ---
    ADMIN_EMAILS: str = ""  # comma-separated

    # --- LLM / translation ---
    LLM_PROVIDER: str = ""
    LLM_API_KEY: str = ""
    LLM_MODEL: str = ""

    # --- CORS ---
    CORS_ORIGINS: str = "http://localhost:3000"

    # --- Pack / content ---
    PACK_BASE_URL: str = ""

    # --- Group clustering ---
    GROUP_MIN_SIZE: int = 5

    # --- SMS (kept for completeness but NOT used by Person C code) ---
    SMS_DAILY_CAP: int = 90
    SMS_WEBHOOK_SECRET: str = ""

    # --- helpers ---

    @property
    def admin_email_set(self) -> set[str]:
        """Return the set of admin emails (lower-cased)."""
        return {e.strip().lower() for e in self.ADMIN_EMAILS.split(",") if e.strip()}

    @property
    def cors_origin_list(self) -> list[str]:
        """Return parsed CORS origins."""
        return [o.strip() for o in self.CORS_ORIGINS.split(",") if o.strip()]

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8", "extra": "ignore"}


# Singleton – import this everywhere.
settings = Settings()
