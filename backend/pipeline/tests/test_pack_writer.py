"""Pack writing, versioning and the 1 km sample trim."""
import json
import sqlite3
from datetime import date

from pipeline.pack_writer import next_pack_version, read_meta, write_pack
from pipeline.trim import square_around, trim_pack

from .helpers import grid_pack_data


def test_write_pack_round_trip(tmp_path):
    path = tmp_path / "out" / "testland.sqlite"
    data = grid_pack_data()
    counts = write_pack(path, data)

    assert counts["node"] == 25
    assert counts["edge"] == len(data.edges) == 80
    assert counts["poi"] == 2 and counts["risk_zone"] == 1
    assert counts["alert_template"] == counts["phrase"] == counts["embassy"] == counts["radio"] == 1
    assert read_meta(path)["pack_version"] == "2026-10-10.1"
    assert not list(path.parent.glob("*.tmp")), "temp file must be gone"


def test_edges_in_pack_are_symmetric_with_risk(grid_pack):
    db = sqlite3.connect(grid_pack)
    forward = {(a, b): (length, risk) for a, b, length, risk in db.execute("SELECT from_id, to_id, length_m, risk_cost FROM edge")}
    assert all((b, a) in forward and forward[(b, a)] == value for (a, b), value in forward.items())
    assert all(0 <= risk <= 5 for _, risk in forward.values())


def test_rewriting_replaces_the_previous_pack(tmp_path):
    path = tmp_path / "p.sqlite"
    write_pack(path, grid_pack_data(size=3))
    write_pack(path, grid_pack_data(size=4))
    assert sqlite3.connect(path).execute("SELECT COUNT(*) FROM node").fetchone()[0] == 16


def test_next_pack_version():
    today = date(2026, 10, 10)
    assert next_pack_version(None, today) == "2026-10-10.1"
    assert next_pack_version("2026-10-10.1", today) == "2026-10-10.2"
    assert next_pack_version("2026-10-10.9", today) == "2026-10-10.10"
    assert next_pack_version("2026-10-09.4", today) == "2026-10-10.1"
    assert next_pack_version("garbage", today) == "2026-10-10.1"


def test_read_meta_of_missing_or_foreign_file(tmp_path):
    assert read_meta(tmp_path / "nope.sqlite") == {}
    other = tmp_path / "other.sqlite"
    sqlite3.connect(other).close()
    assert read_meta(other) == {}


def test_trim_keeps_a_consistent_renumbered_sub_pack(grid_pack, tmp_path):
    out = tmp_path / "sample.sqlite"
    square = square_around(12.004, 80.004, 300)  # the middle 600 m of the 800 m grid
    counts = trim_pack(grid_pack, out, square)

    db = sqlite3.connect(out)
    node_ids = [r[0] for r in db.execute("SELECT id FROM node ORDER BY id")]
    assert node_ids == list(range(len(node_ids))) and 0 < len(node_ids) < 25
    edges = {(a, b) for a, b in db.execute("SELECT from_id, to_id FROM edge")}
    assert edges and all((b, a) in edges for a, b in edges), "both directions survive trimming"
    assert all(a in node_ids and b in node_ids for a, b in edges), "no dangling edges"
    assert counts["node"] == len(node_ids)

    pois = [r[0] for r in db.execute("SELECT id FROM poi")]
    assert pois == ["poi_w1"], "the far hospital is outside the square"
    assert json.loads(read_meta(out)["bbox"]) == [round(v, 6) for v in square]
    assert db.execute("SELECT COUNT(*) FROM alert_template").fetchone()[0] == 1, "content tables are kept"
    assert db.execute("SELECT COUNT(*) FROM risk_zone").fetchone()[0] == 1
    geometry = json.loads(db.execute("SELECT geojson FROM risk_zone").fetchone()[0])
    assert geometry["type"] in ("Polygon", "MultiPolygon")
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"


def test_trim_of_an_empty_area_gives_an_empty_but_valid_pack(grid_pack, tmp_path):
    out = tmp_path / "empty.sqlite"
    counts = trim_pack(grid_pack, out, square_around(40.0, 10.0, 100))
    assert counts["node"] == counts["edge"] == 0
