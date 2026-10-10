"""Points of interest: classification, ids, filtering and merging with the curated official shelters."""

import json
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace
from pathlib import Path

from shapely.geometry import shape
from shapely.geometry.base import BaseGeometry

from .geo import haversine_m
from .projection import centroid_latlon
from .zones import contains_points

SHELTER = "SHELTER"
CANDIDATE_SHELTER = "CANDIDATE_SHELTER"
HOSPITAL = "HOSPITAL"
POLICE = "POLICE"

MIN_CANDIDATE_ELEVATION_M = 2.0
DUPLICATE_RADIUS_M = 100.0  # same-name OSM POIs closer than this are one place (e.g. a campus)
OFFICIAL_OVERLAP_RADIUS_M = 50.0  # a candidate this close to an official shelter is that shelter

OSM_AMENITIES = [
    "hospital",
    "clinic",
    "police",
    "school",
    "college",
    "university",
    "community_centre",
    "townhall",
]
_AMENITY_TYPE = {
    "hospital": HOSPITAL,
    "clinic": HOSPITAL,
    "police": POLICE,
    "school": CANDIDATE_SHELTER,
    "college": CANDIDATE_SHELTER,
    "university": CANDIDATE_SHELTER,
    "community_centre": CANDIDATE_SHELTER,
    "townhall": CANDIDATE_SHELTER,
}
_FALLBACK_NAME = {
    "hospital": "Hospital",
    "clinic": "Clinic",
    "police": "Police station",
    "school": "School",
    "college": "College",
    "university": "University",
    "community_centre": "Community centre",
    "townhall": "Town hall",
}
_OSM_REF = re.compile(r"^(node|way|relation|n|w|r)[/ ]?(\d+)$", re.IGNORECASE)


@dataclass(frozen=True)
class Poi:
    id: str
    type: str
    name: str
    lat: float
    lon: float
    name_ta: str | None = None
    phone: str | None = None
    is_official: bool = False
    elevation_m: float | None = None
    capacity: int | None = None


def classify_poi(tags: Mapping[str, str]) -> str | None:
    amenity = tags.get("amenity")
    if amenity in _AMENITY_TYPE:
        return _AMENITY_TYPE[amenity]
    if tags.get("building") == "school":
        return CANDIDATE_SHELTER
    return None


def poi_id(element_type: str, osm_id: int) -> str:
    """'poi_<n|w|r><osm id>', e.g. poi_w123456."""
    return f"poi_{element_type[0].lower()}{osm_id}"


def _parse_int(value) -> int | None:
    try:
        number = int(str(value).strip())
    except (TypeError, ValueError):
        return None
    return number if number >= 0 else None


def poi_from_osm(
    element_type: str, osm_id: int, tags: Mapping[str, str], lat: float, lon: float
) -> Poi | None:
    poi_type = classify_poi(tags)
    if poi_type is None:
        return None
    fallback = _FALLBACK_NAME.get(tags.get("amenity", ""), "School")
    return Poi(
        id=poi_id(element_type, osm_id),
        type=poi_type,
        name=(tags.get("name") or tags.get("name:en") or fallback).strip(),
        name_ta=(tags.get("name:ta") or "").strip() or None,
        lat=lat,
        lon=lon,
        phone=(tags.get("phone") or tags.get("contact:phone") or "").strip() or None,
        capacity=_parse_int(tags.get("capacity")),
    )


def dedupe_nearby(pois: Sequence[Poi], radius_m: float = DUPLICATE_RADIUS_M) -> list[Poi]:
    """Collapse same-type, same-name POIs within `radius_m` (OSM often maps a campus as several buildings).
    Keeps the first by id so rebuilds are stable. Official (curated) POIs are never touched."""
    kept: list[Poi] = [p for p in pois if p.is_official]
    for poi in sorted((p for p in pois if not p.is_official), key=lambda p: p.id):
        key = (poi.type, poi.name.casefold())
        if any(
            (k.type, k.name.casefold()) == key
            and haversine_m(k.lat, k.lon, poi.lat, poi.lon) <= radius_m
            for k in kept
        ):
            continue
        kept.append(poi)
    return kept


def drop_unsafe_candidates(
    pois: Sequence[Poi], high_zone: BaseGeometry | None
) -> tuple[list[Poi], dict[str, int]]:
    """Remove CANDIDATE_SHELTERs inside a HIGH zone or lower than 2 m. Unknown elevation is kept (we cannot
    tell), and counted so the build log shows how much is unverified."""
    inside = contains_points(high_zone, [p.lon for p in pois], [p.lat for p in pois])
    kept: list[Poi] = []
    stats = {"in_high_zone": 0, "too_low": 0, "unknown_elevation_kept": 0}
    for poi, in_high in zip(pois, inside):
        if poi.type == CANDIDATE_SHELTER:
            if in_high:
                stats["in_high_zone"] += 1
                continue
            if poi.elevation_m is None:
                stats["unknown_elevation_kept"] += 1
            elif poi.elevation_m < MIN_CANDIDATE_ELEVATION_M:
                stats["too_low"] += 1
                continue
        kept.append(poi)
    return kept, stats


def merge_official(osm_pois: Sequence[Poi], official: Sequence[Poi]) -> list[Poi]:
    """Official shelters replace any OSM candidate with the same id or within 50 m."""
    official_ids = {p.id for p in official}
    merged = [
        p
        for p in osm_pois
        if p.id not in official_ids
        and not (
            p.type == CANDIDATE_SHELTER
            and any(
                haversine_m(p.lat, p.lon, o.lat, o.lon) <= OFFICIAL_OVERLAP_RADIUS_M
                for o in official
            )
        )
    ]
    return merged + list(official)


def with_elevations(
    pois: Sequence[Poi], elevations: Mapping[tuple[float, float], float | None]
) -> list[Poi]:
    return [replace(p, elevation_m=elevations.get((p.lat, p.lon))) for p in pois]


def _official_id(props: Mapping, index: int) -> str:
    raw = str(props.get("id") or props.get("poi_id") or "")
    if raw.startswith("poi_"):
        return raw
    match = _OSM_REF.match(str(props.get("osm_id") or props.get("osmId") or raw))
    if match:
        return poi_id(
            {"node": "n", "way": "w", "relation": "r"}.get(match[1].lower(), match[1].lower()),
            int(match[2]),
        )
    return f"poi_s{index}"  # no stable id given: depends on file order, so prefer setting "id" in the file


def load_official_shelters(path: Path, log=print) -> list[Poi]:
    """Curated official shelters (GeoJSON; properties: id, name, name_ta, phone, capacity). Missing file = none."""
    if not path.exists():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as exc:
        raise ValueError(f"{path} is not valid JSON: {exc}") from exc
    features = data.get("features", []) if isinstance(data, dict) else []

    shelters: list[Poi] = []
    for index, feature in enumerate(features, start=1):
        props = feature.get("properties") or {}
        try:
            geom = shape(feature["geometry"])
            lat, lon = centroid_latlon(geom)
        except (KeyError, ValueError, TypeError, AttributeError):
            log(f"  WARNING: {path.name} feature {index} has no usable geometry, skipped")
            continue
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            log(f"  WARNING: {path.name} feature {index} has invalid coordinates, skipped")
            continue
        if (
            "id" not in props
            and "poi_id" not in props
            and "osm_id" not in props
            and "osmId" not in props
        ):
            log(
                f"  WARNING: {path.name} feature {index} has no 'id'; using file order (poi_s{index})"
            )
        shelters.append(
            Poi(
                id=_official_id(props, index),
                type=SHELTER,
                name=str(props.get("name") or "Relief shelter").strip(),
                name_ta=str(props.get("name_ta") or props.get("name:ta") or "").strip() or None,
                lat=lat,
                lon=lon,
                phone=str(props.get("phone") or "").strip() or None,
                is_official=True,
                capacity=_parse_int(props.get("capacity")),
            )
        )
    return shelters
