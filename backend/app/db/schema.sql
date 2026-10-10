-- devices
CREATE TABLE IF NOT EXISTS devices (
    uid        TEXT PRIMARY KEY,
    phone      TEXT,
    lang       TEXT,
    region_id  TEXT,
    sms_opt_in BOOLEAN DEFAULT FALSE,
    updated_at BIGINT
);

-- alerts
CREATE TABLE IF NOT EXISTS alerts (
    id             TEXT PRIMARY KEY,
    region_id      TEXT,
    template_code  TEXT,
    severity       INTEGER,
    lat            DOUBLE PRECISION,
    lon            DOUBLE PRECISION,
    radius_m       INTEGER,
    extra_text     TEXT,
    is_simulation  BOOLEAN DEFAULT TRUE,
    issued_at      BIGINT,
    expires_at     BIGINT,
    wire           TEXT,
    created_by     TEXT
);
CREATE INDEX IF NOT EXISTS idx_alerts_region_issued
    ON alerts (region_id, issued_at);

-- shelter_status
CREATE TABLE IF NOT EXISTS shelter_status (
    shelter_id TEXT PRIMARY KEY,
    region_id  TEXT,
    status     TEXT,
    updated_at BIGINT,
    wire       TEXT
);

-- reports
CREATE TABLE IF NOT EXISTS reports (
    id           TEXT PRIMARY KEY,
    uid          TEXT,
    region_id    TEXT,
    type         TEXT,
    lat          DOUBLE PRECISION,
    lon          DOUBLE PRECISION,
    reporter_lat DOUBLE PRECISION,
    reporter_lon DOUBLE PRECISION,
    note         TEXT,
    photo_url    TEXT,
    created_at   BIGINT,
    channel      TEXT,
    photo_data   BYTEA,
    photo_mime   TEXT,
    review_status TEXT NOT NULL DEFAULT 'pending'
);
CREATE INDEX IF NOT EXISTS idx_reports_region_created
    ON reports (region_id, created_at);

-- presence
CREATE TABLE IF NOT EXISTS presence (
    uid        TEXT PRIMARY KEY,
    lat        DOUBLE PRECISION,
    lon        DOUBLE PRECISION,
    updated_at BIGINT
);
CREATE INDEX IF NOT EXISTS idx_presence_updated
    ON presence (updated_at);

-- beacons
CREATE TABLE IF NOT EXISTS beacons (
    uid        TEXT PRIMARY KEY,
    lat        DOUBLE PRECISION,
    lon        DOUBLE PRECISION,
    created_at BIGINT,
    active     BOOLEAN DEFAULT TRUE
);

-- sms_log
CREATE TABLE IF NOT EXISTS sms_log (
    id         TEXT PRIMARY KEY,
    direction  TEXT,
    phone      TEXT,
    body       TEXT,
    created_at BIGINT,
    status     TEXT
);
