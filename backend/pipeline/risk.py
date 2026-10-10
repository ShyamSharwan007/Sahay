"""Per-edge risk cost, exactly CONTRACTS section 5.4:

    risk_cost = clamp(3*inHighZone + 1.5*inMediumZone + elev + 1.0*within150mOfRiverOrCoast, 0, 5)
    elev = 1.5 if elevation < 3 m, 0.8 if 3-6 m, else 0

A missing (NULL) elevation counts as 0 m, i.e. the cautious assumption.
"""
import math
from collections.abc import Sequence

from .graph import Edge
from .zones import contains_points

MAX_RISK = 5.0
HISTOGRAM_LABELS = ("0", "0-1", "1-2", "2-3", "3-4", "4-5")


def elevation_term(elevation_m: float | None) -> float:
    elevation = 0.0 if elevation_m is None or math.isnan(elevation_m) else elevation_m
    if elevation < 3:
        return 1.5
    if elevation < 6:
        return 0.8
    return 0.0


def risk_cost(*, in_high: bool, in_medium: bool, elevation_m: float | None, near_river_or_coast: bool) -> float:
    raw = 3.0 * in_high + 1.5 * in_medium + elevation_term(elevation_m) + 1.0 * near_river_or_coast
    return round(min(max(raw, 0.0), MAX_RISK), 2)


def edge_elevation(a: float | None, b: float | None) -> float | None:
    """Elevation at the edge midpoint, approximated by the mean of its end nodes (the known one if only one is)."""
    known = [v for v in (a, b) if v is not None]
    return sum(known) / len(known) if known else None


def compute_edge_risks(edges: Sequence[Edge], node_elevation: Sequence[float | None], high, medium, near_water) -> list[float]:
    """risk_cost for each edge, from its midpoint. `high`, `medium`, `near_water` are WGS84 shapely geometries or
    None; `node_elevation` is indexed by remapped node id."""
    lons = [e.mid_lon for e in edges]
    lats = [e.mid_lat for e in edges]
    in_high = contains_points(high, lons, lats)
    in_medium = contains_points(medium, lons, lats)
    near = contains_points(near_water, lons, lats)
    return [
        risk_cost(
            in_high=bool(in_high[i]),
            in_medium=bool(in_medium[i]),
            elevation_m=edge_elevation(node_elevation[e.from_id], node_elevation[e.to_id]),
            near_river_or_coast=bool(near[i]),
        )
        for i, e in enumerate(edges)
    ]


def risk_histogram(values: Sequence[float]) -> list[tuple[str, int]]:
    counts = [0] * len(HISTOGRAM_LABELS)
    for value in values:
        counts[0 if value <= 0 else min(math.ceil(value), 5)] += 1
    return list(zip(HISTOGRAM_LABELS, counts))


def format_histogram(histogram: list[tuple[str, int]], width: int = 40) -> str:
    total = sum(count for _, count in histogram) or 1
    peak = max(count for _, count in histogram) or 1
    lines = ["  risk_cost  edges   share"]
    for label, count in histogram:
        bar = "#" * round(width * count / peak)
        lines.append(f"  {label:>9} {count:>6}  {100 * count / total:5.1f}%  {bar}")
    return "\n".join(lines)
