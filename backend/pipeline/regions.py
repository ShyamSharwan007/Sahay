"""Region definitions (regions.json) and the geojson.io bbox preview link."""

import json
from dataclasses import dataclass
from urllib.parse import quote

from .paths import PIPELINE_DIR

BBox = tuple[
    float, float, float, float
]  # west, south, east, north (= minLon, minLat, maxLon, maxLat)


@dataclass(frozen=True)
class Region:
    id: str
    name: str
    bbox: BBox

    @property
    def west(self) -> float:
        return self.bbox[0]

    @property
    def south(self) -> float:
        return self.bbox[1]

    @property
    def east(self) -> float:
        return self.bbox[2]

    @property
    def north(self) -> float:
        return self.bbox[3]


def validate_bbox(bbox) -> BBox:
    if len(bbox) != 4:
        raise ValueError(f"bbox must have 4 numbers, got {bbox!r}")
    west, south, east, north = (float(v) for v in bbox)
    if not (-180 <= west < east <= 180 and -90 <= south < north <= 90):
        raise ValueError(
            f"bbox must be [minLon, minLat, maxLon, maxLat] with min < max, got {bbox!r}"
        )
    return west, south, east, north


def load_regions() -> dict[str, Region]:
    raw = json.loads((PIPELINE_DIR / "regions.json").read_text(encoding="utf-8"))
    return {
        rid: Region(rid, info["name"], validate_bbox(info["bbox"])) for rid, info in raw.items()
    }


def get_region(region_id: str) -> Region:
    regions = load_regions()
    if region_id not in regions:
        raise KeyError(f"unknown region {region_id!r}; known: {', '.join(sorted(regions))}")
    return regions[region_id]


def bbox_geojson(bbox: BBox) -> dict:
    west, south, east, north = bbox
    ring = [[west, south], [east, south], [east, north], [west, north], [west, south]]
    return {
        "type": "Feature",
        "properties": {},
        "geometry": {"type": "Polygon", "coordinates": [ring]},
    }


def geojson_io_url(bbox: BBox) -> str:
    """Link that opens the bbox rectangle on a map so the area can be eyeballed."""
    payload = json.dumps(bbox_geojson(bbox), separators=(",", ":"))
    return "https://geojson.io/#data=data:application/json," + quote(payload, safe="")
