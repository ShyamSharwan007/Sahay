"""Pydantic models for API requests and responses (CONTRACTS §3.1).

Uses camelCase aliases to match the JSON specification exactly.
"""

from pydantic import BaseModel, ConfigDict
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
    region_id: str
    template_code: str
    severity: int
    lat: float
    lon: float
    radius_m: int
    extra_text: str | None = None
    is_simulation: bool = True


class ShelterStatusResponse(CamelModel):
    shelter_id: str
    status: str
    updated_at: int
    wire: str


class ShelterStatusUpdate(CamelModel):
    region_id: str
    status: str


class SimulateRequest(CamelModel):
    region_id: str
    scenario: str


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
