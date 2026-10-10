"""OpenStreetMap access through osmnx 2.x (the only module that imports osmnx/geopandas/pandas)."""
from dataclasses import dataclass
from pathlib import Path

from shapely.geometry.base import BaseGeometry

from .geo import haversine_m
from .graph import RawEdge, normalize_highway
from .pois import OSM_AMENITIES
from .regions import BBox

WATER_PAD_DEG = 0.003  # ~330 m: also fetch water just outside the bbox, its 150 m buffer can reach inside
WATER_TAGS = {
    "natural": ["coastline", "water"],
    "waterway": ["river", "canal", "stream", "drain"],
}
POI_TAGS = {"amenity": OSM_AMENITIES, "building": ["school"]}

# Overpass filter for walkable ways. Deliberately has NO access=private / service=private exclusion
# (campus roads, gated estates). foot=no and area=yes are still excluded; cycleways, motorways and
# platforms are not in the highway list.
WALK_HIGHWAYS = (
    "footway|path|pedestrian|steps|track|living_street|service|residential|unclassified|road"
    "|tertiary|tertiary_link|secondary|secondary_link|primary|primary_link|trunk|trunk_link"
)
WALK_FILTER = f'["highway"~"^({WALK_HIGHWAYS})$"]["area"!~"yes"]["foot"!~"no"]'


@dataclass(frozen=True)
class OsmElement:
    element_type: str  # node | way | relation
    osm_id: int
    tags: dict[str, str]
    geometry: BaseGeometry  # WGS84


def configure_osmnx(cache_dir: Path):
    import osmnx as ox
    ox.settings.use_cache = True
    ox.settings.cache_folder = str(cache_dir / "osmnx")
    ox.settings.log_console = False
    ox.settings.requests_timeout = 180
    return ox


def fetch_walk_graph(ox, bbox: BBox) -> tuple[dict[int, tuple[float, float]], list[RawEdge]]:
    """Walking graph with every OSM node kept: ({osm node id: (lat, lon)}, edges with their geometry midpoint).

    Not simplified on purpose: simplification drops the nodes between junctions, so the app would draw
    straight lines through buildings instead of following the road shape.

    Uses WALK_FILTER instead of osmnx's network_type="walk": that one drops access=private ways, which
    removes campus roads and gated-estate streets a tourist can actually walk. The download keeps every
    component (retain_all) and the largest one is kept afterwards, so we control and can log what is dropped.
    """
    full = ox.graph.graph_from_bbox(bbox, custom_filter=WALK_FILTER, simplify=False, retain_all=True)
    graph = ox.truncate.largest_component(full, strongly=False)
    print(f"  OSM walkable graph: {full.number_of_nodes()} nodes / {full.number_of_edges()} edges; "
          f"largest connected component: {graph.number_of_nodes()} / {graph.number_of_edges()}")
    nodes = {int(n): (float(d["y"]), float(d["x"])) for n, d in graph.nodes(data=True)}

    edges: list[RawEdge] = []
    for u, v, data in graph.edges(data=True):
        (lat_u, lon_u), (lat_v, lon_v) = nodes[u], nodes[v]
        length = data.get("length")
        if length is None:
            length = haversine_m(lat_u, lon_u, lat_v, lon_v)
        geometry = data.get("geometry")
        if geometry is not None and not geometry.is_empty:
            mid = geometry.interpolate(0.5, normalized=True)
            mid_lon, mid_lat = mid.x, mid.y
        else:
            mid_lon, mid_lat = (lon_u + lon_v) / 2, (lat_u + lat_v) / 2
        edges.append(RawEdge(int(u), int(v), float(length), normalize_highway(data.get("highway")), mid_lon, mid_lat))
    return nodes, edges


def fetch_water(ox, bbox: BBox) -> list[OsmElement]:
    west, south, east, north = bbox
    padded = (west - WATER_PAD_DEG, south - WATER_PAD_DEG, east + WATER_PAD_DEG, north + WATER_PAD_DEG)
    return _fetch_features(ox, padded, WATER_TAGS)


def fetch_poi_elements(ox, bbox: BBox) -> list[OsmElement]:
    return _fetch_features(ox, bbox, POI_TAGS)


def _fetch_features(ox, bbox: BBox, tags: dict) -> list[OsmElement]:
    import pandas as pd

    try:
        frame = ox.features.features_from_bbox(bbox, tags)
    except ox.errors.InsufficientResponseError:  # nothing matched: a valid, empty answer
        return []

    elements: list[OsmElement] = []
    for (element_type, osm_id), row in frame.iterrows():
        geometry = row.geometry
        if geometry is None or geometry.is_empty:
            continue
        row_tags = {
            str(key): str(value)
            for key, value in row.items()
            if key != "geometry" and not isinstance(value, (list, tuple, dict, set)) and pd.notna(value)
        }
        elements.append(OsmElement(str(element_type), int(osm_id), row_tags, geometry))
    return elements
