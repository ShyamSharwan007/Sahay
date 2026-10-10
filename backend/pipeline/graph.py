"""Walking-graph post-processing: id remapping and two-direction edges (pure Python, no OSM access)."""
import math
from collections.abc import Iterable, Mapping
from dataclasses import dataclass


@dataclass(frozen=True)
class RawEdge:
    """An edge as extracted from OSM, still using OSM node ids. The midpoint follows the real geometry."""
    u: int
    v: int
    length_m: float
    road_class: str | None
    mid_lon: float
    mid_lat: float


@dataclass(frozen=True)
class Edge:
    """A pack edge using remapped node ids (0..N-1)."""
    from_id: int
    to_id: int
    length_m: float
    road_class: str | None
    mid_lon: float
    mid_lat: float


def remap_node_ids(osm_ids: Iterable[int]) -> dict[int, int]:
    """OSM id -> dense id 0..N-1. Sorted by OSM id so rebuilds are deterministic."""
    return {osm_id: new_id for new_id, osm_id in enumerate(sorted(set(osm_ids)))}


def normalize_highway(value) -> str | None:
    """osmnx merges parallel ways into lists such as ['footway', 'path']; keep the first."""
    if isinstance(value, (list, tuple)):
        value = value[0] if value else None
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def build_edges(raw_edges: Iterable[RawEdge], id_map: Mapping[int, int]) -> list[Edge]:
    """Remap ids, drop loops/unknown nodes/bad lengths, keep the shortest of parallel edges, and make sure
    every edge exists in BOTH directions. Output is sorted by (from_id, to_id)."""
    best: dict[tuple[int, int], Edge] = {}
    for raw in raw_edges:
        if raw.u == raw.v or raw.u not in id_map or raw.v not in id_map:
            continue
        if not math.isfinite(raw.length_m) or raw.length_m < 0:
            continue
        a, b = id_map[raw.u], id_map[raw.v]
        current = best.get((a, b))
        if current is None or raw.length_m < current.length_m:
            best[(a, b)] = Edge(a, b, raw.length_m, raw.road_class, raw.mid_lon, raw.mid_lat)

    for (a, b), edge in list(best.items()):
        if (b, a) not in best:
            best[(b, a)] = Edge(b, a, edge.length_m, edge.road_class, edge.mid_lon, edge.mid_lat)
    return [best[key] for key in sorted(best)]
