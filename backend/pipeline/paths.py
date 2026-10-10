"""Filesystem locations used by the pipeline (all derived from this file's position)."""
from pathlib import Path

PIPELINE_DIR = Path(__file__).resolve().parent
BACKEND_DIR = PIPELINE_DIR.parent
REPO_ROOT = BACKEND_DIR.parent

CACHE_DIR = BACKEND_DIR / "cache"                       # gitignored
CURATED_DIR = BACKEND_DIR / "data" / "curated"          # risk_zones/<region>.geojson, shelters/<region>.geojson
CONTENT_DIR = BACKEND_DIR / "app" / "data" / "content"  # alert templates, phrases, ... (*.json)
PACKS_JSON = BACKEND_DIR / "app" / "data" / "packs.json"

PACKS_DIR = REPO_ROOT / "packs"                         # gitignored build output
SAMPLES_DIR = REPO_ROOT / "samples"
CONTRACTS_MD = REPO_ROOT / "docs" / "CONTRACTS.md"
