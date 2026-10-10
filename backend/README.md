# Sahay Backend

FastAPI backend for the Sahay disaster-safety app.

## Prerequisites

- Python 3.12+
- [uv](https://docs.astral.sh/uv/) package manager
- PostgreSQL (or a Supabase project)

## Local Setup

```bash
# 1. Install dependencies
uv sync

# 2. Copy and fill environment variables
cp .env.example .env
# Edit .env with your real values

# 3. Generate Ed25519 signing keys
uv run python scripts/gen_keys.py
# Copy the output into .env

# 4. Generate wire test vectors
uv run python scripts/make_test_vectors.py

# 5. Run the dev server
uv run uvicorn app.main:app --reload --port 8000

# 6. Run tests
uv run pytest

# 7. Run linter
uv run ruff check .
```

## API

All endpoints are under `/api/v1`. See `docs/CONTRACTS.md` §3 for the full spec.

| Endpoint | Auth | Description |
|---|---|---|
| `GET /health` | – | Liveness check |
| `GET /regions` | – | List supported regions |

## Deployment (Render)

1. Connect your GitHub repo to Render
2. Create a **Web Service** and point it at this repo
3. Set **Root Directory** to `backend`
4. Set **Build Command**: `pip install uv && uv sync --frozen`
5. Set **Start Command**: `uv run uvicorn app.main:app --host 0.0.0.0 --port $PORT`
6. Set **Health Check Path**: `/api/v1/health`
7. Set **Region**: Singapore
8. Add all env vars from `.env.example` in the Render dashboard
9. Deploy — Render will build and start the service

Or use `render.yaml` for infrastructure-as-code (Blueprint) deployment.
