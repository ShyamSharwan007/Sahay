# Sahay Backend

The FastAPI backend for the Sahay disaster-safety app. It serves regional configuration (packs), alerts, shelter statuses, and processes crowdsourced reports and presence data.

## Architecture
- **Framework**: FastAPI (Python 3.12+)
- **Database**: PostgreSQL (Supabase) accessed synchronously via `psycopg` and `SQLAlchemy`. DB schema is auto-initialised.
- **Auth**: Firebase Admin SDK for JWT verification.
- **Caching & Rate Limiting**: In-memory caching for API responses; `slowapi` for rate limiting.
- **External Integrations**:
  - MET Norway / Open-Meteo (Weather forecasts)
  - Open-Meteo Archive (Weather history)
  - Google Gemini (Translation and template matching)

## Environment Variables
- `DATABASE_URL`
- `FIREBASE_SERVICE_ACCOUNT_B64`
- `LLM_API_KEY`
- `CORS_ORIGINS`
- `ADMIN_EMAILS`
- `GROUP_MIN_SIZE`

## Local Run
1. Install dependencies: `uv pip install -r pyproject.toml` (or use `uv sync`)
2. Run the server: `uv run uvicorn app.main:app --reload`
3. Run tests: `uv run pytest`

## Deployment on Render
Deploy as a Web Service on Render:
1. **Build Command**: `pip install uv && uv sync`
2. **Start Command**: `uv run uvicorn app.main:app --host 0.0.0.0 --port $PORT`
3. Set the required Environment Variables in the Render dashboard.

## API Endpoints
All main endpoints are under `/api/v1`. For request/response schemas, refer to the [API Contract](../docs/CONTRACTS.md).

- **Packs**: `GET /packs/{regionId}/manifest`
- **Groups**: `GET /groups`
- **Presence**: `POST /presence`
- **Reports**: `GET /reports`, `POST /reports`
- **Alerts**: `GET /alerts`, `GET /alert-templates`
- **Admin**: `GET /admin/overview`, `POST /admin/simulate`, `POST /alerts`, `POST /admin/shelters/{shelterId}/status`
- **Translation**: `POST /translate`

## Data Credits
- **Weather Forecasts**: [MET Norway](https://api.met.no/) (Licensed under CC BY 4.0)
- **Weather Fallback & History**: [Open-Meteo](https://open-meteo.com/) (Licensed under CC BY 4.0)
- **Map Data**: [OpenStreetMap](https://www.openstreetmap.org/) contributors (ODbL)
