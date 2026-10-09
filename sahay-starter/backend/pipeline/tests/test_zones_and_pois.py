"""Water/risk zones and POI handling."""
import json

import pytest
from shapely.geometry import LineString, Point, box

from pipeline.geo import haversine_m
from pipeline.pois import (CANDIDATE_SHELTER, HOSPITAL, SHELTER, Poi, classify_poi, dedupe_nearby, drop_unsafe_candidates,
                           load_official_shelters, merge_official, poi_from_osm, poi_id)
from pipeline.zones import (OTHER_WATER, RIVER_OR_COAST, build_medium_zone, build_near_river_coast, classify_water,
                            contains_points, load_high_zones)

BBOX = (80.16, 12.59, 80.21, 12.65)


# ---- zones -------------------------------------------------------------------------------------------------------

def test_classify_water():
    assert classify_water({"natural": "coastline"}) == RIVER_OR_COAST
    assert classify_water({"waterway": "drain"}) == RIVER_OR_COAST
    assert classify_water({"natural": "water", "water": "canal"}) == RIVER_OR_COAST
    assert classify_water({"natural": "water", "water": "lake"}) == OTHER_WATER
    assert classify_water({"natural": "water"}) == OTHER_WATER
    assert classify_water({"waterway": "dam"}) is None
    assert classify_water({"amenity": "school"}) is None


def test_medium_zone_is_150m_around_water_and_clipped_to_the_bbox():
    river = LineString([(80.17, 12.60), (80.17, 12.64)])  # north-south stream inside the bbox
    zone = build_medium_zone([river], BBOX)

    assert zone is not None and zone.geom_type in ("Polygon", "MultiPolygon")
    d = 100 / 111_320 / 0.976  # ~100 m in degrees of longitude here
    assert contains_points(zone, [80.17 + d], [12.62])[0], "100 m away is inside"
    assert not contains_points(zone, [80.17 + 3 * d], [12.62])[0], "300 m away is outside"
    west, south, east, north = zone.bounds
    assert BBOX[0] <= west and south >= BBOX[1] and east <= BBOX[2] and north <= BBOX[3]


def test_water_outside_the_bbox_can_still_reach_in_but_is_clipped():
    coast = LineString([(80.2095, 12.58), (80.2095, 12.66)])  # just east of the bbox, buffer reaches in
    zone = build_medium_zone([coast], BBOX)
    assert zone is not None and zone.bounds[2] <= BBOX[2]
    assert contains_points(zone, [80.2090], [12.62])[0]


def test_no_water_means_no_zone():
    assert build_medium_zone([], BBOX) is None
    assert build_near_river_coast([]) is None
    assert build_medium_zone([Point(0, 0).buffer(0).boundary], BBOX) is None  # empty geometry


def test_near_river_coast_is_not_clipped():
    zone = build_near_river_coast([LineString([(80.2105, 12.60), (80.2105, 12.64)])])
    assert contains_points(zone, [80.2102], [12.62])[0]


def test_contains_points_with_no_zone_or_no_points():
    assert not contains_points(None, [1.0], [1.0]).any()
    assert contains_points(box(0, 0, 1, 1), [], []).size == 0


def test_load_high_zones(tmp_path):
    path = tmp_path / "risk_zones" / "r.geojson"
    path.parent.mkdir()
    path.write_text(json.dumps({"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {"name": "Low colony", "source": "NDMA"},
         "geometry": {"type": "Polygon", "coordinates": [[[80.17, 12.6], [80.18, 12.6], [80.18, 12.61], [80.17, 12.61], [80.17, 12.6]]]}},
        {"type": "Feature", "properties": {}, "geometry": {"type": "Point", "coordinates": [80.1, 12.6]}},
    ]}), encoding="utf-8")
    messages = []
    zones = load_high_zones(path, "r", log=messages.append)

    assert [(z.id, z.name, z.level, z.source) for z in zones] == [("r_high_1", "Low colony", "HIGH", "NDMA")]
    assert len(messages) == 1 and "feature 2" in messages[0]


def test_missing_high_zone_file_is_fine_and_corrupt_file_is_an_error(tmp_path):
    assert load_high_zones(tmp_path / "none.geojson", "r") == []
    bad = tmp_path / "bad.geojson"
    bad.write_text("{not json", encoding="utf-8")
    with pytest.raises(ValueError, match="bad.geojson"):
        load_high_zones(bad, "r")


# ---- POIs --------------------------------------------------------------------------------------------------------

def test_classify_poi():
    assert classify_poi({"amenity": "hospital"}) == HOSPITAL
    assert classify_poi({"amenity": "clinic"}) == HOSPITAL
    assert classify_poi({"amenity": "police"}) == "POLICE"
    for amenity in ("school", "college", "university", "community_centre", "townhall"):
        assert classify_poi({"amenity": amenity}) == CANDIDATE_SHELTER
    assert classify_poi({"building": "school"}) == CANDIDATE_SHELTER
    assert classify_poi({"amenity": "cafe"}) is None
    assert classify_poi({"building": "house"}) is None


def test_poi_ids_follow_the_osm_type_letter():
    assert poi_id("node", 5) == "poi_n5"
    assert poi_id("way", 123456) == "poi_w123456"
    assert poi_id("relation", 9) == "poi_r9"


def test_poi_from_osm_keeps_names_phone_and_falls_back_politely():
    poi = poi_from_osm("way", 7, {"amenity": "hospital", "name": "GH Chengalpattu", "name:ta": "அரசு மருத்துவமனை",
                                  "contact:phone": "+91 44 1234", "capacity": "120"}, 12.6, 80.19)
    assert (poi.id, poi.type, poi.name, poi.name_ta, poi.phone, poi.capacity) == (
        "poi_w7", HOSPITAL, "GH Chengalpattu", "அரசு மருத்துவமனை", "+91 44 1234", 120)

    unnamed = poi_from_osm("node", 8, {"amenity": "police"}, 12.6, 80.19)
    assert unnamed.name == "Police station" and unnamed.name_ta is None and unnamed.phone is None
    assert poi_from_osm("node", 9, {"building": "school"}, 12.6, 80.19).name == "School"
    assert poi_from_osm("node", 10, {"shop": "bakery"}, 12.6, 80.19) is None
    assert poi_from_osm("node", 11, {"amenity": "school", "capacity": "many"}, 12.6, 80.19).capacity is None


def candidate(pid, lat=12.62, lon=80.19, elevation=10.0, name="School"):
    return Poi(pid, CANDIDATE_SHELTER, name, lat, lon, elevation_m=elevation)


def test_candidates_in_high_zones_or_below_2m_are_dropped():
    high = box(80.18, 12.61, 80.20, 12.63)
    pois = [
        candidate("in_zone"),
        candidate("low", lat=12.70, elevation=1.9),
        candidate("ok", lat=12.70, elevation=2.0),
        candidate("unknown", lat=12.70, elevation=None),
        Poi("hospital_in_zone", HOSPITAL, "H", 12.62, 80.19, elevation_m=0.0),  # only candidates are filtered
    ]
    kept, stats = drop_unsafe_candidates(pois, high)

    assert [p.id for p in kept] == ["ok", "unknown", "hospital_in_zone"]
    assert stats == {"in_high_zone": 1, "too_low": 1, "unknown_elevation_kept": 1}


def test_drop_candidates_without_any_high_zone():
    kept, _ = drop_unsafe_candidates([candidate("a")], None)
    assert [p.id for p in kept] == ["a"]


def test_official_shelters_replace_nearby_candidates():
    official = Poi("poi_w1", SHELTER, "Cyclone Shelter", 12.62, 80.19, is_official=True)
    osm = [
        candidate("poi_w1", lat=12.7),                # same id
        candidate("poi_w2", lat=12.62, lon=80.1902),  # ~20 m away
        candidate("poi_w3", lat=12.64),               # far: stays
        Poi("poi_n4", HOSPITAL, "H", 12.62, 80.19),   # not a candidate: stays even when close
    ]
    assert [p.id for p in merge_official(osm, [official])] == ["poi_w3", "poi_n4", "poi_w1"]


def test_dedupe_merges_same_name_buildings_of_one_campus_but_not_distant_ones():
    pois = [
        candidate("poi_w1", name="St Joseph School"),
        candidate("poi_w2", lat=12.6203, name="St Joseph School"),   # ~33 m away
        candidate("poi_w3", lat=12.64, name="St Joseph School"),     # 2 km away: a different school
        candidate("poi_w4", lat=12.6203, name="Other School"),
    ]
    kept = dedupe_nearby(pois)
    assert sorted(p.id for p in kept) == ["poi_w1", "poi_w3", "poi_w4"]
    assert haversine_m(12.62, 80.19, 12.6203, 80.19) < 100


def test_dedupe_never_touches_official_shelters():
    official = Poi("a", SHELTER, "Hall", 12.62, 80.19, is_official=True)
    twin = Poi("b", SHELTER, "Hall", 12.62, 80.19, is_official=True)
    assert len(dedupe_nearby([official, twin])) == 2


def test_load_official_shelters(tmp_path):
    path = tmp_path / "shelters.geojson"
    path.write_text(json.dumps({"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {"id": "poi_w42", "name": "Cyclone Shelter", "name_ta": "புயல் காப்பகம்",
                                           "phone": "044-123", "capacity": 300},
         "geometry": {"type": "Point", "coordinates": [80.19, 12.62]}},
        {"type": "Feature", "properties": {"osm_id": "way/77", "name": "Community Hall"},
         "geometry": {"type": "Polygon", "coordinates": [[[80.19, 12.62], [80.1904, 12.62], [80.1904, 12.6204], [80.19, 12.6204], [80.19, 12.62]]]}},
        {"type": "Feature", "properties": {"name": "No id"}, "geometry": {"type": "Point", "coordinates": [80.2, 12.63]}},
        {"type": "Feature", "properties": {"name": "Broken"}, "geometry": None},
    ]}), encoding="utf-8")
    messages = []
    shelters = load_official_shelters(path, log=messages.append)

    assert [s.id for s in shelters] == ["poi_w42", "poi_w77", "poi_s3"]
    assert all(s.type == SHELTER and s.is_official for s in shelters)
    first = shelters[0]
    assert (first.name_ta, first.phone, first.capacity) == ("புயல் காப்பகம்", "044-123", 300)
    assert abs(shelters[1].lat - 12.6202) < 1e-4, "polygon centroid"
    assert any("poi_s3" in m for m in messages) and any("feature 4" in m for m in messages)


def test_missing_shelter_file_means_no_official_shelters(tmp_path):
    assert load_official_shelters(tmp_path / "none.geojson") == []
