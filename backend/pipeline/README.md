# Sahay pack pipeline (Person C)

Builds the offline trip pack (`packs/<region>/<region>.sqlite`, schema = CONTRACTS section 5.2) and publishes it.
Runs on a laptop, never on Render. All commands run from `backend/`.

```bash
# 1. Check the area on a map first (prints a geojson.io link, downloads nothing)
uv run --with-requirements pipeline/requirements.txt python -m pipeline.build_pack --region mahabalipuram --bbox-only

# 2. Build (long: OSM download + elevation lookups; both are cached in backend/cache/, so re-runs are fast)
export SIGNING_PUBLIC_KEY_B64=...        # optional now; the pack is rebuilt once the production key exists
uv run --with-requirements pipeline/requirements.txt python -m pipeline.build_pack --region mahabalipuram
uv run --with-requirements pipeline/requirements.txt python -m pipeline.build_pack --region iiitdm-kancheepuram

# 3. Publish to the GitHub Release "packs-v1" and write app/data/packs.json (needs `gh auth login`)
uv run --with-requirements pipeline/requirements.txt python -m pipeline.publish

# Tests (no network needed)
uv run --with-requirements pipeline/requirements.txt --with pytest python -m pytest pipeline/tests
```

Inputs (all optional): `backend/data/curated/risk_zones/<region>.geojson` (HIGH zones, properties `name`, `source`),
`backend/data/curated/shelters/<region>.geojson` (official shelters, properties `id`, `name`, `name_ta`, `phone`,
`capacity`), `backend/app/data/content/*.json` (see `content.py` for the accepted shapes).

Other options: `--pack-version`, `--no-sample`, `--sample-centre LAT,LON`, `--skip-elevation` (debug only);
`publish`: `--region` (repeatable), `--repo owner/name`, `--dry-run`. A different 1 km sample can be cut from an
existing pack with `python -m pipeline.trim`.
