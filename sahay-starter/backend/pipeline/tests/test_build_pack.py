"""End-to-end run of build_pack.build() with OpenStreetMap and Open-Meteo replaced by small synthetic data."""
import json
import sqlite3

import pytest
from shapely.geometry import LineString, Point, box

from pipeline import build_pack
from pipeline.graph import RawEdge
from pipeline.osm_source import OsmElement

CENTRE_LAT, CENTRE_LON = 12.62, 80.185  # centre of the mahabalipuram bbox


class FakeElevation:
    """Elevation rises 1 m per 0.001 deg of longitude east of 80.18, so the west is low-lying."""

    def __init__(self, cache, **_):
        pass

    def lookup(self, points):
        return {p: (p[1] - 80.18) * 1000 for p in points}


@pytest.fixture
def sandbox(tmp_path, monkeypatch):
    nodes = {1000 + r * 5 + c: (CENTRE_LAT + (r - 2) * 0.002, CENTRE_LON + (c - 2) * 0.002) for r in range(5) for c in range(5)}
    edges = []
    for r in range(5):
        for c in range(5):
            here = 1000 + r * 5 + c
            if c < 4:
                edges.append(RawEdge(here, here + 1, 210.0, "residential", nodes[here][1] + 0.001, nodes[here][0]))
            if r < 4:
                edges.append(RawEdge(here, here + 5, 220.0, "footway", nodes[here][1], nodes[here][0] + 0.001))

    water = [OsmElement("way", 1, {"natural": "coastline"}, LineString([(80.17, 12.58), (80.17, 12.66)]))]
    pois = [
        OsmElement("way", 11, {"amenity": "school", "name": "Govt School"}, box(80.186, 12.619, 80.187, 12.620)),
        OsmElement("node", 12, {"amenity": "hospital", "name": "PHC", "phone": "044"}, Point(80.19, 12.62)),
        OsmElement("node", 13, {"amenity": "police"}, Point(80.1, 12.0)),  # outside the bbox: ignored
    ]
    monkeypatch.setattr(build_pack.osm_source, "configure_osmnx", lambda cache_dir: object())
    monkeypatch.setattr(build_pack.osm_source, "fetch_walk_graph", lambda ox, bbox: (nodes, edges))
    monkeypatch.setattr(build_pack.osm_source, "fetch_water", lambda ox, bbox: water)
    monkeypatch.setattr(build_pack.osm_source, "fetch_poi_elements", lambda ox, bbox: pois)
    monkeypatch.setattr(build_pack, "ElevationClient", FakeElevation)
    monkeypatch.setattr(build_pack, "CACHE_DIR", tmp_path / "cache")
    monkeypatch.setattr(build_pack, "PACKS_DIR", tmp_path / "packs")
    monkeypatch.setattr(build_pack, "SAMPLES_DIR", tmp_path / "samples")
    monkeypatch.setattr(build_pack, "CONTENT_DIR", tmp_path / "content")
    monkeypatch.setattr(build_pack, "CURATED_DIR", tmp_path / "curated")
    monkeypatch.setenv("SIGNING_PUBLIC_KEY_B64", "KEY123==")
    return tmp_path


def run(argv):
    return build_pack.build(build_pack.get_region(argv[1]), build_pack.parse_args(argv))


def test_full_build_writes_a_valid_pack_and_the_sample(sandbox, capsys):
    (sandbox / "curated" / "risk_zones").mkdir(parents=True)
    (sandbox / "curated" / "risk_zones" / "mahabalipuram.geojson").write_text(json.dumps({
        "type": "FeatureCollection", "features": [{"type": "Feature", "properties": {"name": "Low colony", "source": "test"},
            "geometry": {"type": "Polygon", "coordinates": [[[80.17, 12.61], [80.18, 12.61], [80.18, 12.63], [80.17, 12.63], [80.17, 12.61]]]}}]}))
    (sandbox / "content").mkdir()
    (sandbox / "content" / "phrases.json").write_text(json.dumps(
        [{"id": "yes", "lang": "ta", "category": "basic", "text": "ஆம்"}], ensure_ascii=False), encoding="utf-8")

    out = run(["--region", "mahabalipuram"])
    printed = capsys.readouterr().out

    assert out == sandbox / "packs" / "mahabalipuram" / "mahabalipuram.sqlite"
    assert "geojson.io" in printed and "risk_cost  edges" in printed  # bbox link and histogram were printed

    db = sqlite3.connect(out)
    assert dict(db.execute("SELECT key, value FROM meta"))["public_key_b64"] == "KEY123=="
    assert db.execute("SELECT COUNT(*) FROM node").fetchone()[0] == 25
    assert [r[0] for r in db.execute("SELECT id FROM node ORDER BY id")] == list(range(25))
    pairs = set(db.execute("SELECT from_id, to_id FROM edge"))
    assert len(pairs) == 80 and all((b, a) in pairs for a, b in pairs)
    risks = [r[0] for r in db.execute("SELECT risk_cost FROM edge")]
    assert max(risks) > 0 and all(0 <= r <= 5 for r in risks)
    assert {r[0] for r in db.execute("SELECT level FROM risk_zone")} == {"HIGH", "MEDIUM"}
    assert db.execute("SELECT COUNT(*) FROM phrase WHERE lang = 'ta'").fetchone()[0] == 1

    pois = {r[0]: r[1:] for r in db.execute("SELECT id, type, name, phone FROM poi")}
    assert set(pois) == {"poi_w11", "poi_n12"}, "the out-of-bbox police station is ignored"
    assert pois["poi_n12"] == ("HOSPITAL", "PHC", "044")

    sample = sqlite3.connect(sandbox / "samples" / "mahabalipuram-sample.sqlite")
    assert 0 < sample.execute("SELECT COUNT(*) FROM node").fetchone()[0] <= 25
    assert dict(sample.execute("SELECT key, value FROM meta"))["pack_version"] == dict(db.execute("SELECT key, value FROM meta"))["pack_version"]


def test_rebuild_on_the_same_day_bumps_the_version(sandbox):
    first = run(["--region", "mahabalipuram", "--no-sample"])
    second = run(["--region", "mahabalipuram", "--no-sample"])
    versions = {sqlite3.connect(p).execute("SELECT value FROM meta WHERE key = 'pack_version'").fetchone()[0] for p in (second,)}
    assert first == second and versions.pop().endswith(".2")
    assert not (sandbox / "samples").exists()


def test_other_regions_do_not_write_a_sample(sandbox):
    run(["--region", "iiitdm-kancheepuram"])
    assert not (sandbox / "samples").exists()


def test_bbox_only_stops_before_any_download(sandbox, monkeypatch, capsys):
    monkeypatch.setattr(build_pack.osm_source, "fetch_walk_graph", lambda *a: pytest.fail("must not download"))
    run(["--region", "mahabalipuram", "--bbox-only"])
    assert "geojson.io" in capsys.readouterr().out


def test_empty_graph_refuses_to_build(sandbox, monkeypatch):
    monkeypatch.setattr(build_pack.osm_source, "fetch_walk_graph", lambda ox, bbox: ({}, []))
    with pytest.raises(SystemExit):
        run(["--region", "mahabalipuram"])
    assert not (sandbox / "packs").exists()
