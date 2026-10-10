"""Risk zones: curated HIGH polygons, and MEDIUM = union of 150 m buffers around water features."""
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import shapely
from shapely.geometry import box, shape
from shapely.geometry.base import BaseGeometry
from shapely.ops import unary_union
from shapely.validation import make_valid

from .projection import to_utm, to_wgs84
from .regions import BBox

BUFFER_M = 150.0
SIMPLIFY_M = 10.0
PRECISION_DEG = 1e-6  # ~0.1 m; keeps the stored GeoJSON compact

RIVER_OR_COAST = "river_coast"
OTHER_WATER = "water"
_WATERWAYS = {"river", "canal", "stream", "drain"}
_RIVER_LIKE_WATER = {"river", "canal", "stream"}
_USABLE_TYPES = {"LineString", "MultiLineString", "Polygon", "MultiPolygon", "GeometryCollection"}


@dataclass(frozen=True)
class RiskZone:
    id: str
    name: str | None
    level: str  # HIGH | MEDIUM
    geometry: BaseGeometry  # WGS84 Polygon/MultiPolygon
    source: str | None = None


def classify_water(tags: dict[str, str]) -> str | None:
    """RIVER_OR_COAST for coastline/river-like features (they also feed the +1.0 'near river or coast' term),
    OTHER_WATER for lakes and ponds (MEDIUM zone only), None if it is not water at all."""
    natural = tags.get("natural")
    if natural == "coastline" or tags.get("waterway") in _WATERWAYS:
        return RIVER_OR_COAST
    if natural == "water":
        return RIVER_OR_COAST if tags.get("water") in _RIVER_LIKE_WATER else OTHER_WATER
    return None


def _polygonal(geom: BaseGeometry) -> BaseGeometry | None:
    """Keep only the area parts of a geometry (intersections often yield stray lines/points)."""
    if geom.is_empty:
        return None
    if geom.geom_type in ("Polygon", "MultiPolygon"):
        return geom
    if geom.geom_type == "GeometryCollection":
        parts = [g for g in geom.geoms if g.geom_type in ("Polygon", "MultiPolygon") and not g.is_empty]
        return unary_union(parts) if parts else None
    return None


def _finalize(geom: BaseGeometry) -> BaseGeometry | None:
    area = _polygonal(geom)
    if area is None:
        return None
    return _polygonal(shapely.set_precision(area, PRECISION_DEG))


def buffered_union(
    geoms: list[BaseGeometry], buffer_m: float = BUFFER_M, simplify_m: float = 0.0, clip_bbox: BBox | None = None
) -> BaseGeometry | None:
    """Union of `buffer_m` buffers (metres, via UTM) around WGS84 geometries; WGS84 Polygon/MultiPolygon or None."""
    usable = [make_valid(g) for g in geoms if g is not None and not g.is_empty]
    usable = [g for g in usable if g.geom_type in _USABLE_TYPES]
    if not usable:
        return None
    union = unary_union([to_utm(g).buffer(buffer_m) for g in usable])
    if simplify_m:
        union = union.simplify(simplify_m, preserve_topology=True)
    zone = to_wgs84(union)
    if clip_bbox is not None:
        zone = zone.intersection(box(*clip_bbox))
    return _finalize(zone)


def build_medium_zone(water_geoms: list[BaseGeometry], bbox: BBox) -> BaseGeometry | None:
    """MEDIUM zone: 150 m around all water features, simplified to 10 m, clipped to the bbox."""
    return buffered_union(water_geoms, BUFFER_M, SIMPLIFY_M, clip_bbox=bbox)


def build_near_river_coast(river_coast_geoms: list[BaseGeometry]) -> BaseGeometry | None:
    """Area within 150 m of a river/canal/stream/drain/coastline. Not clipped: a river just outside
    the bbox can still be close to its edge roads."""
    return buffered_union(river_coast_geoms, BUFFER_M)


def contains_points(zone: BaseGeometry | None, lons, lats) -> np.ndarray:
    """Vectorised point-in-zone test (boolean array)."""
    lons = np.asarray(lons, dtype=float)
    if zone is None or lons.size == 0:
        return np.zeros(lons.shape, dtype=bool)
    return shapely.contains_xy(zone, lons, np.asarray(lats, dtype=float))


def geometry_to_geojson(geom: BaseGeometry) -> str:
    return json.dumps(shapely.geometry.mapping(geom), separators=(",", ":"))


def load_high_zones(path: Path, region_id: str, log=print) -> list[RiskZone]:
    """Curated HIGH zones: GeoJSON with properties name + source. A missing file means no zones; a corrupt
    file is an error (silently dropping a safety layer would be worse than failing the build)."""
    if not path.exists():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as exc:
        raise ValueError(f"{path} is not valid JSON: {exc}") from exc

    zones: list[RiskZone] = []
    for index, feature in enumerate(_features_of(data, path), start=1):
        props = feature.get("properties") or {}
        try:
            geom = _finalize(make_valid(shape(feature["geometry"])))
        except (KeyError, ValueError, TypeError, AttributeError):
            geom = None
        if geom is None:
            log(f"  WARNING: {path.name} feature {index} is not a usable Polygon/MultiPolygon, skipped")
            continue
        zones.append(RiskZone(
            id=f"{region_id}_high_{index}",
            name=props.get("name") or "High-risk area",
            level="HIGH",
            geometry=geom,
            source=props.get("source"),
        ))
    return zones


def _features_of(data, path: Path) -> list[dict]:
    if isinstance(data, dict) and data.get("type") == "FeatureCollection":
        return [f for f in data.get("features", []) if isinstance(f, dict)]
    if isinstance(data, dict) and data.get("type") == "Feature":
        return [data]
    if isinstance(data, dict) and "coordinates" in data:  # bare geometry
        return [{"type": "Feature", "properties": {}, "geometry": data}]
    raise ValueError(f"{path}: expected a GeoJSON FeatureCollection, Feature or geometry")
