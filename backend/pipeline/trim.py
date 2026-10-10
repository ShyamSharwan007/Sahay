"""Trim a finished pack to a small square around a centre point (used for samples/mahabalipuram-sample.sqlite).

python -m pipeline.trim --src ../packs/mahabalipuram/mahabalipuram.sqlite --dst ../samples/mahabalipuram-sample.sqlite
"""

import argparse
import json
import math
import shutil
import sqlite3
from contextlib import closing
from pathlib import Path

from shapely.geometry import box, mapping, shape
from shapely.ops import unary_union
from shapely.validation import make_valid

from .pack_writer import read_meta
from .paths import PACKS_DIR, SAMPLES_DIR
from .regions import BBox

METRES_PER_DEG_LAT = 111_320.0


def square_around(centre_lat: float, centre_lon: float, half_side_m: float) -> BBox:
    dlat = half_side_m / METRES_PER_DEG_LAT
    dlon = half_side_m / (METRES_PER_DEG_LAT * math.cos(math.radians(centre_lat)))
    return centre_lon - dlon, centre_lat - dlat, centre_lon + dlon, centre_lat + dlat


def bbox_centre(bbox: BBox) -> tuple[float, float]:
    """(lat, lon)"""
    return (bbox[1] + bbox[3]) / 2, (bbox[0] + bbox[2]) / 2


def trim_pack(src: Path, dst: Path, square: BBox) -> dict[str, int]:
    """Copy `src` to `dst` keeping only what lies in `square`: the largest connected piece of the street
    graph (ids renumbered to 0..M-1), POIs inside, risk zones clipped. Content tables are kept whole."""
    west, south, east, north = square
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.unlink(missing_ok=True)
    shutil.copyfile(src, dst)

    with closing(sqlite3.connect(dst)) as db:
        with db:
            inside = {
                row[0]
                for row in db.execute(
                    "SELECT id FROM node WHERE lon BETWEEN ? AND ? AND lat BETWEEN ? AND ?",
                    (west, east, south, north),
                )
            }
            edges = [
                r
                for r in db.execute(
                    "SELECT from_id, to_id, length_m, risk_cost, road_class FROM edge"
                )
                if r[0] in inside and r[1] in inside
            ]
            keep = _largest_component(inside, [(e[0], e[1]) for e in edges])
            edges = [e for e in edges if e[0] in keep and e[1] in keep]
            _rewrite_graph(db, keep, edges)

            db.execute(
                "DELETE FROM poi WHERE NOT (lon BETWEEN ? AND ? AND lat BETWEEN ? AND ?)",
                (west, east, south, north),
            )
            _clip_zones(db, square)
            db.execute(
                "UPDATE meta SET value = ? WHERE key = 'bbox'",
                (json.dumps([round(v, 6) for v in square], separators=(",", ":")),),
            )
        db.execute("VACUUM")
        return {
            t: db.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
            for t in ("node", "edge", "poi", "risk_zone")
        }


def _largest_component(nodes: set[int], links: list[tuple[int, int]]) -> set[int]:
    parent = {n: n for n in nodes}

    def find(n: int) -> int:
        while parent[n] != n:
            parent[n] = parent[parent[n]]
            n = parent[n]
        return n

    for a, b in links:
        parent[find(a)] = find(b)
    groups: dict[int, set[int]] = {}
    for n in nodes:
        groups.setdefault(find(n), set()).add(n)
    connected = [g for g in groups.values() if len(g) > 1]  # drop nodes with no edges
    return max(connected, key=len) if connected else set()


def _rewrite_graph(db: sqlite3.Connection, keep: set[int], edges: list[tuple]) -> None:
    new_id = {old: i for i, old in enumerate(sorted(keep))}
    nodes = [
        (new_id[r[0]], r[1], r[2], r[3])
        for r in db.execute("SELECT id, lat, lon, elevation_m FROM node")
        if r[0] in new_id
    ]
    db.execute("DELETE FROM node")
    db.execute("DELETE FROM edge")
    db.executemany("INSERT INTO node VALUES (?, ?, ?, ?)", nodes)
    db.executemany(
        "INSERT INTO edge VALUES (?, ?, ?, ?, ?)",
        [(new_id[e[0]], new_id[e[1]], e[2], e[3], e[4]) for e in edges],
    )


def _clip_zones(db: sqlite3.Connection, square: BBox) -> None:
    window = box(*square)
    for zone_id, geojson in db.execute("SELECT id, geojson FROM risk_zone").fetchall():
        clipped = make_valid(shape(json.loads(geojson))).intersection(window)
        polygons = [
            g
            for g in getattr(clipped, "geoms", [clipped])
            if g.geom_type in ("Polygon", "MultiPolygon")
        ]
        if not polygons:
            db.execute("DELETE FROM risk_zone WHERE id = ?", (zone_id,))
            continue
        geometry = unary_union(polygons)
        db.execute(
            "UPDATE risk_zone SET geojson = ? WHERE id = ?",
            (json.dumps(mapping(geometry), separators=(",", ":")), zone_id),
        )


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Trim a pack to a square around its centre.")
    parser.add_argument(
        "--src", type=Path, default=PACKS_DIR / "mahabalipuram" / "mahabalipuram.sqlite"
    )
    parser.add_argument("--dst", type=Path, default=SAMPLES_DIR / "mahabalipuram-sample.sqlite")
    parser.add_argument(
        "--size-m", type=float, default=1000.0, help="side of the square in metres (default 1000)"
    )
    parser.add_argument(
        "--centre", help="LAT,LON of the square's centre (default: centre of the pack bbox)"
    )
    args = parser.parse_args(argv)

    meta = read_meta(args.src)
    if "bbox" not in meta:
        parser.error(f"{args.src} is missing or not a pack")
    lat, lon = (
        (float(v) for v in args.centre.split(","))
        if args.centre
        else bbox_centre(tuple(json.loads(meta["bbox"])))
    )
    counts = trim_pack(args.src, args.dst, square_around(lat, lon, args.size_m / 2))
    print(f"wrote {args.dst} ({args.dst.stat().st_size / 1024:.0f} KB): {counts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
