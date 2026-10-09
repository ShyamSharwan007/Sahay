# CONTRACTS.md — Sahay shared specification (FROZEN)

Everything that crosses a person boundary is defined here. Changes need all 4 people to agree; Person A commits them.
Requests go to `docs/CONTRACT_REQUESTS.md`.

---

## 0. Names and constants

| Item | Value |
|---|---|
| App name / package | Sahay / `com.sahay` |
| Android | minSdk 26, latest stable compile/target SDK, Kotlin 2.x, Compose, Material 3, Hilt (KSP) |
| User languages (UI + alerts) | `en, de, fr, es, ru, ja, ko, zh, ar` (Arabic is RTL) |
| Local language (for "show to a local" card) | `ta` (Tamil) |
| Keyword languages (matching official SMS) | `en, hi, ta` |
| Regions | `mahabalipuram` bbox `[80.16, 12.59, 80.21, 12.65]` (main demo story); `iiitdm-kancheepuram` bbox `[80.13, 12.82, 80.18, 12.86]` (live-test region where the team physically is, so GPS, routing and group finder work for real); `chennai-central` bbox `[80.20, 12.97, 80.30, 13.10]` (only if time allows). Order: minLon, minLat, maxLon, maxLat. C verifies each bbox on a map before building |
| Walking speed for ETA | 75 m/min |
| Group minimum size | 5 (demo setting: 3, server env `GROUP_MIN_SIZE`) |
| Presence heartbeat | every 5 min while Emergency Mode is on (SMS fallback max every 15 min) |
| Group polling | every 2 min while online and Emergency Mode is on |
| Time | all timestamps are Unix epoch **seconds** (UTC); dates are `YYYY-MM-DD` |
| JSON | camelCase keys everywhere |

Config values that live in code: `SahayConfig.BASE_URL` and `SahayConfig.SMS_GATEWAY_NUMBER` in
`android/core/contracts/.../Contracts.kt`. Person C sends the real values; Person A updates them (allowed edit).

---

## 1. Repo layout

```
sahay/
  AGENTS.md  CLAUDE.md  README.md  .gitignore  CODEOWNERS
  docs/        CONTRACTS.md  DESIGN.md  CONTRACT_REQUESTS.md  wire_test_vectors.json (C)
  samples/     mahabalipuram-sample.sqlite (C, small test pack)
  android/     Gradle root (A)
    app/                 (A)  application, navigation, screens, auth, profile
    core/contracts/      (FROZEN) models + interfaces (Contracts.kt)
    core/designsystem/   (A)  theme, tokens, components
    engine/              (B)  packs, map, routing, location, geofencing
    comms/               (D)  SMS, wire codec, signatures, alerts, SOS, reports, groups, mesh
    keystore/sahay.keystore (A) shared debug+release keystore
  backend/     (C) FastAPI app, pipeline, curated data, tests
  admin/       (D) React admin dashboard
  packs/       build output (gitignored; published to GitHub Releases)
```

Android module dependencies (no other edges allowed):
`app → designsystem, engine, comms, contracts` · `engine → contracts` · `comms → contracts` · `designsystem → contracts`.

Wiring: Hilt. Each module binds its own implementations in its own `@Module` (`AppModule` in :app, `EngineModule` in :engine,
`CommsModule` in :comms). Modules inject each other's interfaces only through `core/contracts` types.

---

## 2. Android interfaces (see Contracts.kt for the exact Kotlin)

| Interface | Implemented by | Used by |
|---|---|---|
| `ProfileStore`, `AuthTokenProvider`, `UiPreferences` | A (:app) | B, D |
| `PackRepository`, `LocationProvider`, `RoutingEngine`, `RiskMonitor`, `EmergencyModeController`, `SahayMap` composable | B (:engine) | A, D |
| `AlertRepository`, `SosService`, `ReportRepository`, `GroupService`, `ConnectivityMonitor` | D (:comms) | A |

`SahayMap` is NOT in contracts (contracts has no Compose). B exports it from :engine with exactly this signature, and A calls it:
`@Composable fun SahayMap(state: MapViewState, modifier: Modifier = Modifier, onPoiClick: (Poi) -> Unit = {}, onReportClick: (HazardReport) -> Unit = {}, onGroupClick: (PeopleGroup) -> Unit = {}, onLongPress: (GeoPoint) -> Unit = {})` in package `com.sahay.engine.map`.

No Firebase Cloud Messaging. Fresh data arrives by: polling while the app is open or Emergency Mode is on (D, every 2 min), a WorkManager job every 15 min when online (D), and server SMS to opted-in phone numbers.

Deep links (notifications → app): MainActivity reads intent extra `DeepLinks.EXTRA` with values
`DeepLinks.NAVIGATE_SAFE` or `DeepLinks.alert(id)`. A handles them; D/B create them.

Profile data (medical, contacts) never leaves the phone, except inside the SOS SMS the user sends.
The backend only ever receives: Firebase uid, optional phone number (for SMS alerts), language, region, locations of reports/presence/beacons.

---

## 3. REST API (Person C implements, A/B/D call)

Base: `https://<render-app>.onrender.com/api/v1`. Auth header for user calls: `Authorization: Bearer <Firebase ID token>`.
Admin calls: same header; email must be in `ADMIN_EMAILS`. Guest users (Firebase anonymous sign-in) are valid users with no email. Errors: HTTP 4xx/5xx with `{"error":{"code":"string","message":"string"}}`.

| Method & path | Auth | Request | Response |
|---|---|---|---|
| `GET /health` | – | – | `{"ok":true,"time":1760000000}` |
| `GET /regions` | – | – | `[{"id":"mahabalipuram","name":"Mahabalipuram","bbox":[80.16,12.59,80.21,12.65]}]` |
| `GET /packs/{regionId}/manifest?start=YYYY-MM-DD&end=YYYY-MM-DD` | – | – | Manifest (§5.1) |
| `GET /alerts?regionId=&since=` | – | – | `[Alert]` (§3.1) |
| `GET /alert-templates?lang=en` | – | – | `[{"code":"FLD_EVAC","severity":3,"title":"...","body":"..."}]` |
| `POST /alerts` | admin | `{"regionId","templateCode","severity","lat","lon","radiusM","extraText":null,"isSimulation":true}` | `Alert` + `"smsRecipients":12` |
| `GET /shelters/status?regionId=` | – | – | `[{"shelterId":"poi_123","status":"OPEN","updatedAt":0,"wire":"SH1*S*..."}]` |
| `POST /admin/shelters/{shelterId}/status` | admin | `{"regionId","status":"OPEN|FULL|CLOSED"}` | shelter status |
| `POST /admin/simulate` | admin | `{"regionId","scenario":"cyclone"}` | `{"alerts":[Alert],"shelterChanges":[...]}` |
| `GET /admin/overview?regionId=` | admin | – | `{"devices":0,"reports":0,"presence":0,"groups":0,"beacons":0,"smsSentToday":0}` |
| `POST /devices` | user | `{"phone":"+91...","lang":"de","regionId":"mahabalipuram","smsOptIn":true}` | `{"ok":true}` |
| `POST /translate` | – | `{"text":"...","targetLang":"de"}` | `{"detectedLang":"ta","simplifiedEn":"...","translated":"...","matchedTemplateCode":"FLD_WARN"}` |
| `POST /reports` | user | `{"type":"FL","lat","lon","reporterLat","reporterLon","note":null,"photoBase64":null,"createdAt","channel":"INTERNET"}` | `Report` |
| `GET /reports?regionId=&sinceMin=180` | optional | – | `[Report]` |
| `POST /presence` | user | `{"lat","lon"}` | `204` |
| `GET /groups?lat=&lon=&radiusM=3000` | optional | – | `{"groups":[Group],"beacons":[Beacon],"minSize":5}` |
| `POST /beacons` | user | `{"lat","lon"}` | `Beacon` |
| `DELETE /beacons/me` | user | – | `204` |
| `POST /sms/inbound?secret=<SMS_WEBHOOK_SECRET>` | query secret | SMS Gateway for Android webhook payload (`sms:received`) | `200` |

### 3.1 JSON shapes
```json
Alert  = {"id":"a1b2c3","regionId":"mahabalipuram","templateCode":"FLD_EVAC","severity":3,"lat":12.6208,"lon":80.1945,
          "radiusM":2000,"issuedAt":1760000000,"extraText":null,"isSimulation":true,"wire":"SH1*A*..."}
Report = {"id":"r_9f2","type":"FL","lat":12.62,"lon":80.19,"note":"knee-deep water","photoUrl":null,"createdAt":1760000000,
          "trustScore":0.72,"label":"VERIFIED","mine":false,"channel":"INTERNET"}
Group  = {"id":"g_1","lat":12.621,"lon":80.193,"size":7,"status":"AT_SHELTER","lastSeen":1760000000}
Beacon = {"id":"b_1","lat":12.62,"lon":80.19,"createdAt":1760000000,"mine":false}
```
Clients must trust the signed `wire` string, not the JSON fields, for alerts and shelter status (verify, then parse the wire).

---

## 4. Wire format (same string over internet, SMS and Bluetooth mesh)

`SH1*<TYPE>*<fields...>*<ts>*<sig-or-uid>` — separated by `*` (NOT `|`: the pipe is outside the GSM-7 basic alphabet and costs 2 characters per SMS), ASCII only, **max 160 characters**.
Coordinates: 5 decimals for alerts/reports, 3 decimals for groups/presence/beacons (privacy). Severity 0–3 (0 info, 1 watch, 2 warning, 3 emergency).

Signed by the server (Ed25519):
| Type | Format | Example (sig shortened) |
|---|---|---|
| Alert | `SH1*A*<id6>*<templateCode>*<sev>*<lat>,<lon>*<radiusM>*<flags>*<ts>*<sig>` flags: `S` = simulation, `R` = real | `SH1*A*a1b2c3*FLD_EVAC*3*12.62080,80.19450*2000*S*1760000000*Qm9...` |
| Shelter status | `SH1*S*<shelterId>*<O/F/C>*<ts>*<sig>` (one letter: O open, F full, C closed) | `SH1*S*poi_123*F*1760000000*Zx1...` |
| Group | `SH1*G*<lat>,<lon>*<size>*<A/S/R>*<ts>*<sig>` (one letter: A at shelter, S safe area, R risk zone) | `SH1*G*12.621,80.193*7*A*1760000000*Pq3...` |

From users (unsigned, uid = first 8 chars of Firebase uid, or `anon0000`):
| Type | Format |
|---|---|
| Report | `SH1*R*<typeCode>*<lat>,<lon>*<sev>*<ts>*<uid8>` type codes: `FL` flood, `RB` road blocked, `SF` shelter full, `SO` shelter open, `PL` power line down, `LS` landslide, `OT` other |
| Presence | `SH1*P*<lat>,<lon>*<ts>*<uid8>` |
| Beacon | `SH1*B*<lat>,<lon>*<ts>*<uid8>` (cancel: `SH1*B*0,0*<ts>*<uid8>`) |

Signature rules:
- Signed bytes = UTF-8 bytes of the whole string **up to and including the `*` before the signature**.
- Signature = 64-byte Ed25519 signature, encoded **base64url without padding** (86 chars).
- Public key = 32 raw bytes, base64 (standard, with padding) in `meta.public_key_b64` of the pack and in the manifest `publicKeyB64`.
- Reject: wrong prefix, wrong field count, length > 160, bad signature, `ts` more than 48 h in the past or 10 min in the future.

Mesh wrapper (Bluetooth only): `H<hops>*` + wire, e.g. `H1*SH1*A*...`. Max hops 3. Dedupe by SHA-256 of the inner wire.

Test vectors: `docs/wire_test_vectors.json` (Person C), format:
`{"publicKeyB64":"...","cases":[{"wire":"SH1*A*...","valid":true,"note":"..."}]}` using a TEST key, never the production key.

---

## 5. Trip pack

### 5.1 Manifest (`GET /packs/{regionId}/manifest`)
```json
{
  "regionId": "mahabalipuram", "regionName": "Mahabalipuram", "packVersion": "2026-10-10.1",
  "bbox": [80.16,12.59,80.21,12.65],
  "sqliteUrl": "https://github.com/<org>/sahay/releases/download/packs-v1/mahabalipuram.sqlite", "sqliteBytes": 4200000, "sqliteSha256": "hex",
  "pmtilesUrl": ".../mahabalipuram.pmtiles", "pmtilesBytes": 18000000,
  "styleLightUrl": ".../style-light.json", "styleDarkUrl": ".../style-dark.json",
  "assetsZipUrl": ".../basemap-assets.zip", "assetsZipBytes": 3000000,
  "publicKeyB64": "....",
  "forecast": [{"date":"2026-10-10","rainMm":12.4,"windKmh":22.0,"maxTempC":31.0,"riskLevel":"LOW"}],
  "history": {"years":[2021,2022,2023,2024,2025],"avgRainMm":8.1,"heavyRainDays":3,"summary":{"en":"...","de":"..."}},
  "incidents": [{"date":"2023-12-04","type":"cyclone","title":{"en":"Cyclone Michaung"},"summary":{"en":"..."},"sourceUrl":"https://..."}],
  "precautions": [{"id":"p_flood_1","severity":2,"title":{"en":"...","de":"..."},"body":{"en":"...","de":"..."}}]
}
```
All human text in the manifest is a map `{langCode: text}` containing all 9 user languages (fallback to `en`).
Forecast only for dates within 16 days of today; otherwise `forecast` is `[]` and the app shows history only.

`pmtilesUrl`, `styleLightUrl`, `styleDarkUrl` and `assetsZipUrl` MAY be `null`. Then B falls back to downloading an offline region for the bbox with MapLibre `OfflineManager` from an OpenFreeMap style (allowed by its terms). This fallback depends on B's Phase-0 spike result.

Style placeholders (in style JSON, replaced by the app with local `file://` paths):
`pmtiles://{PMTILES}` for the vector source, `{GLYPHS}/{fontstack}/{range}.pbf` for glyphs, `{SPRITE}` for the sprite base path.
`basemap-assets.zip` contains `fonts/` and `sprites/` folders. Map attribution "© OpenStreetMap contributors, Protomaps" must be shown.

### 5.2 SQLite schema (built by C, read read-only by B with `android.database.sqlite.SQLiteDatabase`)
```sql
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
```

### 5.3 Routing rules (B)
- Edge cost = `length_m × (1 + risk_cost)`.
- Edges with either endpoint within 50 m of a blocked point (`RoutingEngine.setBlockedPoints`) are skipped.
- Snap start to the nearest node within 300 m; otherwise return a straight-line `Route` with a warning.
- `routeToNearestSafe`: candidates = `SHELTER` + `CANDIDATE_SHELTER` whose status is not FULL/CLOSED and which are not inside a HIGH zone;
  route to the 5 nearest by straight line, pick the lowest cost; fallback `HOSPITAL`.
- `avoidsRiskZones = true` when no used edge has `risk_cost ≥ 3`.

### 5.4 Risk cost (C, computed in the pipeline)
`risk_cost = clamp(3·inHighZone + 1.5·inMediumZone + elev + 1.0·within150mOfRiverOrCoast, 0, 5)`
where `elev = 1.5` if elevation < 3 m, `0.8` if 3–6 m, else `0`.

---

## 6. Trust score for hazard reports (C on server; D computes a provisional local copy for mesh/SMS reports)

`trust = (0.25·P + 0.30·C + 0.20·O + 0.10·E + 0.15·H) × D`
- P proximity: `max(0, 1 − d/1000)` where d = metres between reporter location and reported point.
- C corroboration: `min(1, n/3)`, n = distinct other reporters, same type, within 300 m and 30 min.
- O official match: 1 if inside an active official alert area of a related template (flood ↔ FLD_*/RAIN_*/COAST_*, etc.), else 0.
- E evidence: 1 if a photo is attached, else 0.
- H history: share of the reporter's earlier reports that ended LIKELY or VERIFIED; 0.5 if none.
- D recency: `exp(−ageMinutes / 60)`.
Labels: `≥ 0.70` VERIFIED, `≥ 0.40` LIKELY, else UNCONFIRMED. Recompute on every read.

---

## 7. Group clustering (C)

- Input: presence rows from the last 10 min (one per uid, latest wins).
- DBSCAN, haversine metric, `eps = 100 m`, `min_samples = GROUP_MIN_SIZE` (5; demo 3).
- Output centre rounded to 3 decimals (~110 m). Never return individuals.
- Status: `AT_SHELTER` if centre within 150 m of a SHELTER/CANDIDATE_SHELTER; `RISK_ZONE` if inside a HIGH risk zone or within 300 m of a VERIFIED/LIKELY `FL` report; else `SAFE_AREA`.
- Beacons: active for 30 min unless cancelled; location rounded to 3 decimals; returned to everyone within `radiusM`.

---

## 8. Content

### 8.1 Alert template codes (C writes text for all 9 languages + keywords en/hi/ta)
| Code | Sev | Meaning (English) |
|---|---|---|
| RAIN_HVY | 1 | Heavy rain expected |
| RAIN_XHVY | 2 | Very heavy rain expected |
| FLD_WATCH | 1 | Flooding possible in low-lying areas |
| FLD_WARN | 2 | Flooding likely, avoid low roads and underpasses |
| FLD_EVAC | 3 | Flooding: move to higher ground or a shelter now |
| CYC_WATCH | 1 | Cyclone may affect the area |
| CYC_WARN | 2 | Cyclone warning, stay indoors, secure belongings |
| CYC_LANDFALL | 3 | Cyclone landfall imminent, go to shelter now |
| WIND_HIGH | 2 | Strong winds, stay away from trees and hoardings |
| SEA_ROUGH | 2 | Rough sea, stay away from the beach |
| COAST_EVAC | 3 | Coastal evacuation ordered |
| SURGE | 3 | Storm surge, move away from the coast |
| LIGHTNING | 1 | Lightning risk, stay indoors |
| ROAD_CLOSED | 1 | Roads closed in the area |
| POWER_OUT | 1 | Power cuts expected, charge your phone |
| STAY_INDOORS | 2 | Stay indoors until further notice |
| SHELTER_OPEN | 1 | Relief shelters are open |
| BOIL_WATER | 1 | Drink only boiled or bottled water |
| ALL_CLEAR | 0 | Danger has passed |
| TEST | 0 | Test message, no action needed |

Text rules: title ≤ 6 words, body ≤ 25 words, plain words, one action per sentence.

### 8.2 Phrase ids (C writes text for all 9 languages + `ta`)
`tourist_need_help` (card headline: "I am a tourist. I don't speak Tamil. Please help me."), `take_me_to_shelter`, `where_is_shelter`,
`where_is_hospital`, `call_police`, `call_ambulance`, `i_am_injured`, `i_have_allergy`, `i_need_water`, `i_need_to_charge_phone`,
`is_this_road_safe`, `where_is_high_ground`, `my_hotel_is`, `i_am_lost`, `thank_you`, `yes`, `no`.
Categories: `emergency`, `directions`, `medical`, `basic`.

### 8.3 Precaution rules (C, manifest generation)
IMD daily rain thresholds: heavy ≥ 64.5 mm, very heavy ≥ 115.6 mm, extremely heavy ≥ 204.5 mm.
Wind ≥ 62 km/h → cyclone precautions. Always include: save emergency numbers (112), keep phone charged, know your nearest shelter.
Oct–Dec in Chennai/Mahabalipuram: north-east monsoon note.

---

## 9. Error handling and UX conventions
- Network timeout 10 s, 1 retry with backoff, then fall back to cached/offline data. Never show raw exceptions.
- Every list screen has empty, loading and error states with a retry action.
- All user-visible text comes from string resources (Android) or the language maps above.

---

## 10. Database (Postgres on Supabase, C only — listed for reference)
`devices(uid PK, phone, lang, region_id, sms_opt_in, updated_at)`
`alerts(id PK, region_id, template_code, severity, lat, lon, radius_m, extra_text, is_simulation, issued_at, wire, created_by)`
`shelter_status(shelter_id PK, region_id, status, updated_at, wire)`
`reports(id PK, uid, region_id, type, lat, lon, reporter_lat, reporter_lon, note, photo_url, created_at, channel)`
`presence(uid PK, lat, lon, updated_at)` · `beacons(uid PK, lat, lon, created_at, active)` · `sms_log(id PK, direction, phone, body, created_at, status)`

---

## 11. Environment variables and secrets
Backend (Render): `DATABASE_URL`, `FIREBASE_SERVICE_ACCOUNT_B64`, `SIGNING_PRIVATE_KEY_B64`, `SIGNING_PUBLIC_KEY_B64`, `ADMIN_EMAILS`,
`LLM_PROVIDER`, `LLM_API_KEY`, `LLM_MODEL`, `SMS_GATEWAY_URL`, `SMS_GATEWAY_USER`, `SMS_GATEWAY_PASS`, `SMS_WEBHOOK_SECRET`,
`SMS_DAILY_CAP` (default 90), `GROUP_MIN_SIZE` (default 5), `CORS_ORIGINS`, `PACK_BASE_URL`.
Admin (Vercel): `VITE_API_BASE`, `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN`, `VITE_FIREBASE_PROJECT_ID`, `VITE_FIREBASE_APP_ID`.
Android: `android/app/google-services.json` (committed), `android/keystore/sahay.keystore` (committed, alias `sahay`, passwords `sahay123`).
