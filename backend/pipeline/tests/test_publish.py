"""packs.json generation (no GitHub access)."""
import hashlib
import json

import pytest

from pipeline import publish
from pipeline.regions import bbox_geojson, geojson_io_url, get_region, load_regions, validate_bbox


def test_entry_has_exactly_the_documented_fields_with_null_map_fields(grid_pack):
    entry = publish.build_entry(grid_pack, "org/sahay")

    assert list(entry) == ["packVersion", "sqliteUrl", "sqliteBytes", "sqliteSha256",
                           "pmtilesUrl", "pmtilesBytes", "styleLightUrl", "styleDarkUrl", "assetsZipUrl", "assetsZipBytes"]
    assert entry["packVersion"] == "2026-10-10.1"
    assert entry["sqliteUrl"] == "https://github.com/org/sahay/releases/download/packs-v1/testland.sqlite"
    assert entry["sqliteBytes"] == grid_pack.stat().st_size
    assert entry["sqliteSha256"] == hashlib.sha256(grid_pack.read_bytes()).hexdigest()
    assert all(entry[f] is None for f in publish.MAP_FIELDS)


def test_existing_map_fields_are_preserved_on_republish(grid_pack):
    previous = {"pmtilesUrl": "https://x/m.pmtiles", "pmtilesBytes": 5, "sqliteBytes": 1, "packVersion": "old"}
    entry = publish.build_entry(grid_pack, "org/sahay", previous=previous)
    assert entry["pmtilesUrl"] == "https://x/m.pmtiles" and entry["pmtilesBytes"] == 5
    assert entry["packVersion"] == "2026-10-10.1" and entry["sqliteBytes"] == grid_pack.stat().st_size


def test_non_pack_file_is_rejected(tmp_path):
    other = tmp_path / "x.sqlite"
    other.write_bytes(b"not a database")
    with pytest.raises(publish.PublishError):
        publish.build_entry(other, "org/sahay")


def test_packs_json_roundtrip_keeps_other_regions(tmp_path):
    path = tmp_path / "app" / "data" / "packs.json"
    assert publish.read_packs_json(path) == {}
    publish.write_packs_json(path, {"b": {"packVersion": "1"}, "a": {"packVersion": "2"}})
    assert list(json.loads(path.read_text(encoding="utf-8"))) == ["a", "b"]

    path.write_text("{broken", encoding="utf-8")
    with pytest.raises(publish.PublishError):
        publish.read_packs_json(path)


# ---- regions ---------------------------------------------------------------------------------------------------

def test_only_the_two_live_regions_with_contract_bboxes():
    regions = load_regions()
    assert set(regions) == {"mahabalipuram", "iiitdm-kancheepuram"}
    assert regions["mahabalipuram"].bbox == (80.16, 12.59, 80.21, 12.65)
    assert regions["iiitdm-kancheepuram"].bbox == (80.13, 12.82, 80.18, 12.86)


def test_unknown_region_and_bad_bbox():
    with pytest.raises(KeyError):
        get_region("atlantis")
    with pytest.raises(ValueError):
        validate_bbox([80.2, 12.6, 80.1, 12.7])  # west > east
    with pytest.raises(ValueError):
        validate_bbox([1, 2, 3])


def test_geojson_io_link_contains_the_bbox_polygon():
    bbox = get_region("mahabalipuram").bbox
    url = geojson_io_url(bbox)
    assert url.startswith("https://geojson.io/#data=data:application/json,")
    ring = bbox_geojson(bbox)["geometry"]["coordinates"][0]
    assert ring[0] == ring[-1] and len(ring) == 5
    assert " " not in url and "{" not in url
