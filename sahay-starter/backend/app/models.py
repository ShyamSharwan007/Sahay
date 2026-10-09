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
