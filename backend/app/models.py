"""Pydantic models for API requests and responses (CONTRACTS §3.1).

Uses camelCase aliases to match the JSON specification exactly.
"""

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    """Base model that automatically converts snake_case to camelCase for JSON."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class AlertResponse(CamelModel):
    id: str
    region_id: str
    template_code: str
    severity: int
    lat: float
    lon: float
    radius_m: int
    issued_at: int
    extra_text: str | None = None
    is_simulation: bool
    wire: str


class AlertCreate(CamelModel):
    region_id: str = Field(..., min_length=1, max_length=50)
    template_code: str = Field(..., min_length=1, max_length=30)
    severity: int = Field(..., ge=0, le=3)
    lat: float = Field(..., ge=-90.0, le=90.0)
    lon: float = Field(..., ge=-180.0, le=180.0)
    radius_m: int = Field(..., ge=100, le=20000)
    extra_text: str | None = Field(None, max_length=200)
    is_simulation: bool = True


class ShelterStatusResponse(CamelModel):
    shelter_id: str
    status: str
    updated_at: int
    wire: str


class ShelterStatusUpdate(CamelModel):
    region_id: str = Field(..., min_length=1, max_length=50)
    status: str = Field(..., pattern="^(OPEN|FULL|CLOSED)$")


class SimulateRequest(CamelModel):
    region_id: str = Field(..., min_length=1, max_length=50)
    scenario: str = Field(..., min_length=1, max_length=30)


class AdminOverview(CamelModel):
    devices: int
    reports: int
    presence: int
    groups: int
    beacons: int
    sms_sent_today: int


class ForecastDay(CamelModel):
    date: str
    rain_mm: float
    wind_kmh: float
    max_temp_c: float
    risk_level: str


class HistorySummary(CamelModel):
    years: list[int]
    avg_rain_mm: float
    heavy_rain_days: int
    summary: dict[str, str]


class Incident(CamelModel):
    date: str
    type: str
    title: dict[str, str]
    summary: dict[str, str]
    source_url: str | None = None


class Precaution(CamelModel):
    id: str
    severity: int
    title: dict[str, str]
    body: dict[str, str]


class ManifestResponse(CamelModel):
    region_id: str
    region_name: str
    pack_version: str
    bbox: list[float]
    sqlite_url: str | None
    sqlite_bytes: int | None
    sqlite_sha256: str | None
    pmtiles_url: str | None
    pmtiles_bytes: int | None
    style_light_url: str | None
    style_dark_url: str | None
    assets_zip_url: str | None
    assets_zip_bytes: int | None
    public_key_b64: str
    forecast: list[ForecastDay]
    history: HistorySummary | dict
    incidents: list[Incident]
    precautions: list[Precaution]


class ReportCreate(CamelModel):
    type: str = Field(..., min_length=1, max_length=5)
    lat: float = Field(..., ge=-90.0, le=90.0)
    lon: float = Field(..., ge=-180.0, le=180.0)
    reporter_lat: float = Field(..., ge=-90.0, le=90.0)
    reporter_lon: float = Field(..., ge=-180.0, le=180.0)
    note: str | None = Field(None, max_length=200)
    photo_base64: str | None = None
    created_at: int = Field(..., gt=0)
    channel: str = Field(..., pattern="^(INTERNET|SMS|MESH)$")


class Report(CamelModel):
    id: str
    type: str
    lat: float
    lon: float
    note: str | None
    photo_url: str | None
    created_at: int
    trust_score: float
    label: str
    mine: bool
    channel: str


class PresenceCreate(CamelModel):
    lat: float = Field(..., ge=-90.0, le=90.0)
    lon: float = Field(..., ge=-180.0, le=180.0)


class Group(CamelModel):
    id: str
    lat: float
    lon: float
    size: int
    status: str
    last_seen: int


class Beacon(CamelModel):
    id: str
    lat: float
    lon: float
    created_at: int
    mine: bool


class GroupsResponse(CamelModel):
    groups: list[Group]
    beacons: list[Beacon]
    min_size: int


class TranslateRequest(CamelModel):
    text: str = Field(..., min_length=1, max_length=500)
    target_lang: str = Field(..., min_length=2, max_length=10)


class TranslateResponse(CamelModel):
    detected_lang: str
    simplified_en: str
    translated: str
    matched_template_code: str | None
