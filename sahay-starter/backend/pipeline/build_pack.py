"""Build a region's trip pack (run locally, not on Render).

    cd backend
    uv run --with-requirements pipeline/requirements.txt python -m pipeline.build_pack --region mahabalipuram

Output: packs/<region>/<region>.sqlite (and samples/mahabalipuram-sample.sqlite for mahabalipuram).
"""
import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from shapely.ops import unary_union

from . import osm_source
from .content import load_content
from .elevation import ElevationCache, ElevationClient
from .graph import build_edges, remap_node_ids
from .pack_writer import PackData, ZoneRow, next_pack_version, read_meta, write_pack
from .paths import CACHE_DIR, CONTENT_DIR, CURATED_DIR, PACKS_DIR, SAMPLES_DIR
from .pois import (Poi, dedupe_nearby, drop_unsafe_candidates, load_official_shelters, merge_official, poi_from_osm,
                   with_elevations)
from .projection import centroid_latlon
from .regions import Region, get_region, geojson_io_url, load_regions
from .risk import compute_edge_risks, format_histogram, risk_histogram
from .trim import bbox_centre, square_around, trim_pack
from .zones import (OTHER_WATER, RIVER_OR_COAST, build_medium_zone, build_near_river_coast, classify_water,
                    geometry_to_geojson, load_high_zones)

SAMPLE_REGION = "mahabalipuram"
SAMPLE_SIZE_M = 1000.0


def step(number: int, title: str) -> None:
    print(f"\n[{number}/8] {title}", flush=True)


def build(region: Region, args: argparse.Namespace) -> Path:
    started = time.time()
    out_path = PACKS_DIR / region.id / f"{region.id}.sqlite"

    step(1, "Area to cover")
    print(f"  {region.name}  bbox [minLon, minLat, maxLon, maxLat] = {list(region.bbox)}")
    print(f"  Check it on a map: {geojson_io_url(region.bbox)}")
    if args.bbox_only:
        return out_path

    step(2, "Walking graph (OpenStreetMap via osmnx)")
    ox = osm_source.configure_osmnx(CACHE_DIR)
    osm_nodes, raw_edges = osm_source.fetch_walk_graph(ox, region.bbox)
    id_map = remap_node_ids(osm_nodes)
    edges = build_edges(raw_edges, id_map)
    print(f"  {len(id_map)} nodes, {len(raw_edges)} OSM edges -> {len(edges)} edges stored in both directions")
    if not edges:
        raise SystemExit("No walkable edges found in this bbox; refusing to build an empty pack.")

    step(3, "Water features and points of interest")
    water = osm_source.fetch_water(ox, region.bbox)
    water_geoms = [e.geometry for e in water if classify_water(e.tags)]
    river_coast_geoms = [e.geometry for e in water if classify_water(e.tags) == RIVER_OR_COAST]
    print(f"  water features: {len(water_geoms)} ({len(river_coast_geoms)} river/canal/stream/drain/coast, "
          f"{sum(1 for e in water if classify_water(e.tags) == OTHER_WATER)} lakes/ponds)")
    osm_pois = dedupe_nearby(_osm_pois(osm_source.fetch_poi_elements(ox, region.bbox), region))
    official = load_official_shelters(CURATED_DIR / "shelters" / f"{region.id}.geojson")
    print(f"  POIs from OSM (after merging duplicates): {len(osm_pois)}; official shelters (curated): {len(official)}")
    pois = merge_official(osm_pois, official)

    step(4, "Elevation (Open-Meteo)")
    node_points = {new_id: osm_nodes[osm_id] for osm_id, new_id in id_map.items()}
    if args.skip_elevation:
        print("  --skip-elevation: every elevation is NULL (debug build, do not publish)")
        elevations: dict = {}
    else:
        cache = ElevationCache(CACHE_DIR / "elevation.sqlite")
        try:
            elevations = ElevationClient(cache).lookup(list(node_points.values()) + [(p.lat, p.lon) for p in pois])
        finally:
            cache.close()
    node_elevation = [elevations.get(node_points[i]) for i in range(len(node_points))]
    pois = with_elevations(pois, elevations)
    unknown = sum(1 for v in node_elevation if v is None)
    print(f"  nodes without elevation: {unknown}/{len(node_elevation)} (treated as 0 m by the risk formula)")

    step(5, "Risk zones and edge risk")
    high_zones = load_high_zones(CURATED_DIR / "risk_zones" / f"{region.id}.geojson", region.id)
    for zone in high_zones:
        print(f"  HIGH zone '{zone.name}' (source: {zone.source or 'unknown'})")
    if not high_zones:
        print("  no curated HIGH zones for this region (backend/data/curated/risk_zones/)")
    high_geom = unary_union([z.geometry for z in high_zones]) if high_zones else None
    medium_geom = build_medium_zone(water_geoms, region.bbox)
    near_water = build_near_river_coast(river_coast_geoms)
    print(f"  MEDIUM zone: {'none (no water found)' if medium_geom is None else medium_geom.geom_type}")
    risks = compute_edge_risks(edges, node_elevation, high_geom, medium_geom, near_water)
    print(format_histogram(risk_histogram(risks)))

    step(6, "Shelters and services")
    pois, stats = drop_unsafe_candidates(pois, high_geom)
    print(f"  dropped candidate shelters: {stats['in_high_zone']} inside HIGH zones, {stats['too_low']} below 2 m; "
          f"{stats['unknown_elevation_kept']} kept with unknown elevation")
    for kind in ("SHELTER", "CANDIDATE_SHELTER", "HOSPITAL", "POLICE"):
        print(f"  {kind}: {sum(1 for p in pois if p.type == kind)}")

    step(7, "Writing the pack")
    content = load_content(CONTENT_DIR)
    for warning in content.warnings:
        print(f"  content: {warning}")
    zones = [ZoneRow(z.id, z.name, "HIGH", geometry_to_geojson(z.geometry)) for z in high_zones]
    if medium_geom is not None:
        zones.append(ZoneRow(f"{region.id}_medium", "Near water (150 m)", "MEDIUM", geometry_to_geojson(medium_geom)))

    now = datetime.now(timezone.utc)
    version = args.pack_version or next_pack_version(read_meta(out_path).get("pack_version"), now.date())
    public_key = os.environ.get("SIGNING_PUBLIC_KEY_B64", "").strip()
    if not public_key:
        print("  WARNING: SIGNING_PUBLIC_KEY_B64 not set, meta.public_key_b64 is '' (rebuild before releasing)")
    meta = {
        "region_id": region.id,
        "region_name": region.name,
        "pack_version": version,
        "built_at": str(int(now.timestamp())),
        "bbox": json.dumps(list(region.bbox), separators=(",", ":")),
        "public_key_b64": public_key,
    }
    nodes = [(i, node_points[i][0], node_points[i][1], node_elevation[i]) for i in range(len(node_points))]
    counts = write_pack(out_path, PackData(meta, nodes, edges, risks, pois, zones, content))
    print(f"  {out_path}")
    print("  rows: " + ", ".join(f"{table}={n}" for table, n in counts.items()))
    print(f"  size: {out_path.stat().st_size / 1_048_576:.2f} MB   pack_version: {version}")

    step(8, "Sample pack")
    if region.id != SAMPLE_REGION or args.no_sample:
        print("  skipped")
    else:
        centre = (tuple(float(v) for v in args.sample_centre.split(",")) if args.sample_centre
                  else bbox_centre(region.bbox))
        sample_path = SAMPLES_DIR / "mahabalipuram-sample.sqlite"
        sample_counts = trim_pack(out_path, sample_path, square_around(*centre, SAMPLE_SIZE_M / 2))
        print(f"  {sample_path} ({sample_path.stat().st_size / 1024:.0f} KB): {sample_counts}")
        if sample_counts["edge"] < 20:
            print("  WARNING: very little road data in the central 1 km; try --sample-centre LAT,LON on the town")

    print(f"\nDone in {time.time() - started:.0f} s.")
    return out_path


def _osm_pois(elements, region: Region) -> list[Poi]:
    pois = []
    for element in elements:
        lat, lon = centroid_latlon(element.geometry)
        if not (region.west <= lon <= region.east and region.south <= lat <= region.north):
            continue
        poi = poi_from_osm(element.element_type, element.osm_id, element.tags, lat, lon)
        if poi is not None:
            pois.append(poi)
    return pois


def parse_args(argv=None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build a Sahay trip pack.")
    parser.add_argument("--region", required=True, choices=sorted(load_regions()))
    parser.add_argument("--bbox-only", action="store_true", help="print the geojson.io link and stop")
    parser.add_argument("--pack-version", help="override YYYY-MM-DD.N (default: next number for today)")
    parser.add_argument("--no-sample", action="store_true", help="do not write samples/mahabalipuram-sample.sqlite")
    parser.add_argument("--sample-centre", metavar="LAT,LON", help="centre of the 1 km sample (default: bbox centre)")
    parser.add_argument("--skip-elevation", action="store_true", help="debug only: leave all elevations NULL")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="replace")  # Tamil names must not crash a Windows console
    args = parse_args(argv)
    build(get_region(args.region), args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
