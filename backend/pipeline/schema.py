"""Pack SQLite schema, copied VERBATIM from docs/CONTRACTS.md section 5.2 (a test compares them)."""

SCHEMA_SQL = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
-- keys: region_id, region_name, pack_version, built_at, bbox (JSON), public_key_b64

CREATE TABLE poi (
  id TEXT PRIMARY KEY, type TEXT NOT NULL,          -- SHELTER | CANDIDATE_SHELTER | HOSPITAL | POLICE
  name TEXT NOT NULL, name_ta TEXT, lat REAL NOT NULL, lon REAL NOT NULL,
  phone TEXT, is_official INTEGER NOT NULL DEFAULT 0, elevation_m REAL, capacity INTEGER);

CREATE TABLE node (id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, elevation_m REAL);
CREATE TABLE edge (
  from_id INTEGER NOT NULL, to_id INTEGER NOT NULL, length_m REAL NOT NULL,
  risk_cost REAL NOT NULL DEFAULT 0,                -- 0..5
  road_class TEXT);
CREATE INDEX edge_from ON edge(from_id);          -- edges stored in BOTH directions unless one-way

CREATE TABLE risk_zone (id TEXT PRIMARY KEY, name TEXT, level TEXT NOT NULL, geojson TEXT NOT NULL); -- level HIGH|MEDIUM; geojson Polygon/MultiPolygon geometry
CREATE TABLE alert_template (code TEXT NOT NULL, lang TEXT NOT NULL, severity INTEGER NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY (code, lang));
CREATE TABLE alert_keyword (code TEXT NOT NULL, lang TEXT NOT NULL, keyword TEXT NOT NULL);   -- lowercase
CREATE TABLE phrase (id TEXT NOT NULL, lang TEXT NOT NULL, category TEXT NOT NULL, text TEXT NOT NULL, icon TEXT, PRIMARY KEY (id, lang)); -- includes lang 'ta'
CREATE TABLE embassy (country_code TEXT PRIMARY KEY, name TEXT NOT NULL, phone TEXT, address TEXT, lat REAL, lon REAL, url TEXT);
CREATE TABLE radio (name TEXT NOT NULL, frequency TEXT NOT NULL, lang TEXT);
"""

TABLES = ("meta", "poi", "node", "edge", "risk_zone", "alert_template", "alert_keyword", "phrase", "embassy", "radio")
META_KEYS = ("region_id", "region_name", "pack_version", "built_at", "bbox", "public_key_b64")
