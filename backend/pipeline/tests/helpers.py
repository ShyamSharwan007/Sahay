"""Shared test builders."""

from pipeline.content import ContentRows
from pipeline.graph import Edge
from pipeline.pack_writer import PackData, ZoneRow
from pipeline.pois import CANDIDATE_SHELTER, HOSPITAL, Poi

META = {
    "region_id": "testland",
    "region_name": "Testland",
    "pack_version": "2026-10-10.1",
    "built_at": "1760000000",
    "bbox": "[80.0,12.0,80.1,12.1]",
    "public_key_b64": "",
}


def grid_pack_data(size: int = 5, step: float = 0.002) -> PackData:
    """A size x size street grid starting at (12.0, 80.0); every street stored in both directions."""
    nodes, edges = [], []
    for row in range(size):
        for col in range(size):
            nodes.append((row * size + col, 12.0 + row * step, 80.0 + col * step, 5.0))
    for row in range(size):
        for col in range(size):
            here = row * size + col
            for dr, dc in ((0, 1), (1, 0)):
                if row + dr < size and col + dc < size:
                    there = (row + dr) * size + col + dc
                    mid_lon = 80.0 + (col + dc / 2) * step
                    mid_lat = 12.0 + (row + dr / 2) * step
                    edges.append(Edge(here, there, 220.0, "residential", mid_lon, mid_lat))
                    edges.append(Edge(there, here, 220.0, "residential", mid_lon, mid_lat))
    pois = [
        Poi("poi_w1", CANDIDATE_SHELTER, "School", 12.004, 80.004, elevation_m=9.0),
        Poi(
            "poi_n2", HOSPITAL, "Clinic", 12.05, 80.05, elevation_m=9.0
        ),  # outside a small trim square
    ]
    zone = '{"type":"Polygon","coordinates":[[[80.0,12.0],[80.05,12.0],[80.05,12.05],[80.0,12.05],[80.0,12.0]]]}'
    content = ContentRows(
        alert_templates=[("TEST", "en", 0, "Test", "This is a test.")],
        alert_keywords=[("TEST", "en", "test")],
        phrases=[("yes", "en", "basic", "Yes", None)],
        embassies=[("DE", "German Embassy", None, None, None, None, None)],
        radio=[("AIR Chennai", "101.4 MHz", "ta")],
    )
    return PackData(
        dict(META),
        nodes,
        edges,
        [0.5] * len(edges),
        pois,
        [ZoneRow("testland_medium", "Water", "MEDIUM", zone)],
        content,
    )
