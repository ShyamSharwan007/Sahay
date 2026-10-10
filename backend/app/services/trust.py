"""Trust scoring pure functions for hazard reports (CONTRACTS §6)."""

import math
from typing import Any

# Hazard-type to related-template-codes table for Official Match (O)
HAZARD_TO_TEMPLATES = {
    "FL": {
        "FLD_WATCH",
        "FLD_WARN",
        "FLD_EVAC",
        "RAIN_HVY",
        "RAIN_XHVY",
        "COAST_EVAC",
        "SURGE",
        "SEA_ROUGH",
    },
    "RB": {
        "ROAD_CLOSED",
        "FLD_WARN",
        "FLD_EVAC",
        "CYC_WARN",
        "CYC_LANDFALL",
        "LS",
        "LANDSLIDE",
    },  # Assuming some related codes
    "SF": {"SHELTER_OPEN"},
    "SO": {"SHELTER_OPEN"},
    "PL": {"POWER_OUT", "WIND_HIGH", "CYC_WARN", "CYC_LANDFALL"},
    "LS": {"ROAD_CLOSED", "RAIN_XHVY", "FLD_EVAC"},
    "OT": set(),
}


def haversine_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Calculate the great circle distance in meters between two points."""
    R = 6371000  # Radius of earth in meters
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)

    a = (
        math.sin(delta_phi / 2.0) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2
    )
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return R * c


def calculate_proximity_score(
    reporter_lat: float, reporter_lon: float, lat: float, lon: float
) -> float:
    """P proximity: max(0, 1 - d/1000)"""
    if reporter_lat == 0 and reporter_lon == 0:
        return 0.0
    d = haversine_distance(reporter_lat, reporter_lon, lat, lon)
    return max(0.0, 1.0 - d / 1000.0)


def calculate_corroboration_score(n_distinct_reporters: int) -> float:
    """C corroboration: min(1, n/3)"""
    return min(1.0, n_distinct_reporters / 3.0)


def calculate_official_match_score(
    report_type: str, lat: float, lon: float, active_alerts: list[dict[str, Any]]
) -> float:
    """O official match: 1 if inside an active official alert area of a related template, else 0."""
    related_codes = HAZARD_TO_TEMPLATES.get(report_type, set())
    if not related_codes:
        return 0.0

    for alert in active_alerts:
        if alert.get("template_code") in related_codes:
            d = haversine_distance(lat, lon, alert.get("lat", 0), alert.get("lon", 0))
            if d <= alert.get("radius_m", 0):
                return 1.0
    return 0.0


def calculate_history_score(total_prior: int, likely_or_verified_prior: int) -> float:
    """H history: share of reporter's earlier reports that ended LIKELY or VERIFIED; 0.5 if none."""
    if total_prior == 0:
        return 0.5
    return likely_or_verified_prior / total_prior


def calculate_recency_score(created_at: int, now: int) -> float:
    """D recency: exp(-ageMinutes / 60). Future timestamps clamped."""
    age_seconds = now - created_at
    if age_seconds < 0:
        age_seconds = 0
    age_minutes = age_seconds / 60.0
    return math.exp(-age_minutes / 60.0)


def calculate_trust(
    p_score: float, c_score: float, o_score: float, e_score: float, h_score: float, d_score: float
) -> float:
    """trust = (0.25·P + 0.30·C + 0.20·O + 0.10·E + 0.15·H) × D"""
    base_trust = (
        (0.25 * p_score) + (0.30 * c_score) + (0.20 * o_score) + (0.10 * e_score) + (0.15 * h_score)
    )
    return base_trust * d_score


def get_trust_label(trust_score: float) -> str:
    """Labels: >= 0.70 VERIFIED, >= 0.40 LIKELY, else UNCONFIRMED."""
    if trust_score >= 0.70:
        return "VERIFIED"
    if trust_score >= 0.40:
        return "LIKELY"
    return "UNCONFIRMED"
