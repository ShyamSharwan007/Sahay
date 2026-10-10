"""Template loader for alert templates (CONTRACTS §8.1)."""

import json
from pathlib import Path
from typing import Any

from app.models import CamelModel

CONTENT_DIR = Path(__file__).resolve().parent.parent / "data" / "content"
TEMPLATES_FILE = CONTENT_DIR / "templates.json"

# Fallback English templates based on CONTRACTS §8.1
FALLBACK_TEMPLATES = {
    "RAIN_HVY": {
        "severity": 1,
        "title": {"en": "Heavy Rain"},
        "body": {"en": "Heavy rain expected."},
    },
    "RAIN_XHVY": {
        "severity": 2,
        "title": {"en": "Very Heavy Rain"},
        "body": {"en": "Very heavy rain expected."},
    },
    "FLD_WATCH": {
        "severity": 1,
        "title": {"en": "Flood Watch"},
        "body": {"en": "Flooding possible in low-lying areas."},
    },
    "FLD_WARN": {
        "severity": 2,
        "title": {"en": "Flood Warning"},
        "body": {"en": "Flooding likely, avoid low roads and underpasses."},
    },
    "FLD_EVAC": {
        "severity": 3,
        "title": {"en": "Evacuate: Flood"},
        "body": {"en": "Flooding: move to higher ground or a shelter now."},
    },
    "CYC_WATCH": {
        "severity": 1,
        "title": {"en": "Cyclone Watch"},
        "body": {"en": "Cyclone may affect the area."},
    },
    "CYC_WARN": {
        "severity": 2,
        "title": {"en": "Cyclone Warning"},
        "body": {"en": "Cyclone warning, stay indoors, secure belongings."},
    },
    "CYC_LANDFALL": {
        "severity": 3,
        "title": {"en": "Cyclone Landfall"},
        "body": {"en": "Cyclone landfall imminent, go to shelter now."},
    },
    "WIND_HIGH": {
        "severity": 2,
        "title": {"en": "Strong Winds"},
        "body": {"en": "Strong winds, stay away from trees and hoardings."},
    },
    "SEA_ROUGH": {
        "severity": 2,
        "title": {"en": "Rough Sea"},
        "body": {"en": "Rough sea, stay away from the beach."},
    },
    "COAST_EVAC": {
        "severity": 3,
        "title": {"en": "Evacuate: Coast"},
        "body": {"en": "Coastal evacuation ordered."},
    },
    "SURGE": {
        "severity": 3,
        "title": {"en": "Storm Surge"},
        "body": {"en": "Storm surge, move away from the coast."},
    },
    "LIGHTNING": {
        "severity": 1,
        "title": {"en": "Lightning Risk"},
        "body": {"en": "Lightning risk, stay indoors."},
    },
    "ROAD_CLOSED": {
        "severity": 1,
        "title": {"en": "Roads Closed"},
        "body": {"en": "Roads closed in the area."},
    },
    "POWER_OUT": {
        "severity": 1,
        "title": {"en": "Power Outage"},
        "body": {"en": "Power cuts expected, charge your phone."},
    },
    "STAY_INDOORS": {
        "severity": 2,
        "title": {"en": "Stay Indoors"},
        "body": {"en": "Stay indoors until further notice."},
    },
    "SHELTER_OPEN": {
        "severity": 1,
        "title": {"en": "Shelter Open"},
        "body": {"en": "Relief shelters are open."},
    },
    "BOIL_WATER": {
        "severity": 1,
        "title": {"en": "Boil Water"},
        "body": {"en": "Drink only boiled or bottled water."},
    },
    "ALL_CLEAR": {
        "severity": 0,
        "title": {"en": "All Clear"},
        "body": {"en": "Danger has passed."},
    },
    "TEST": {
        "severity": 0,
        "title": {"en": "Test Message"},
        "body": {"en": "Test message, no action needed."},
    },
}


class AlertTemplateResponse(CamelModel):
    code: str
    severity: int
    title: str
    body: str


def load_templates() -> dict[str, dict[str, Any]]:
    if TEMPLATES_FILE.exists():
        try:
            return json.loads(TEMPLATES_FILE.read_text(encoding="utf-8"))
        except Exception:
            return FALLBACK_TEMPLATES
    return FALLBACK_TEMPLATES


def get_templates_for_lang(lang: str = "en") -> list[AlertTemplateResponse]:
    templates = load_templates()
    result = []
    for code, data in templates.items():
        title_map = data.get("title", {})
        body_map = data.get("body", {})

        # Fallback to English if the requested language is missing
        title = title_map.get(lang) or title_map.get("en", "")
        body = body_map.get(lang) or body_map.get("en", "")

        result.append(
            AlertTemplateResponse(
                code=code, severity=data.get("severity", 0), title=title, body=body
            )
        )
    return result
