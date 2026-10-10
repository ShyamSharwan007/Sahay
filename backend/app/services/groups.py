import json
import logging
import math
import sqlite3
import tempfile
import threading
from pathlib import Path

import httpx
import numpy as np
from sklearn.cluster import DBSCAN

from app.config import settings

logger = logging.getLogger(__name__)

PACKS_JSON = Path(__file__).resolve().parent.parent / "data" / "packs.json"

# In-memory caches
_sqlite_conns = {}  # region_id -> sqlite3.Connection
_sqlite_lock = threading.Lock()

_groups_cache = {}  # cache_key -> (timestamp, data)
_groups_cache_lock = threading.Lock()


def _haversine_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    R = 6371000
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = math.sin(delta_phi / 2.0) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def _get_sqlite_conn(region_id: str) -> sqlite3.Connection | None:
    with _sqlite_lock:
        if region_id in _sqlite_conns:
            return _sqlite_conns[region_id]

        if not PACKS_JSON.exists():
            return None
        try:
            packs = json.loads(PACKS_JSON.read_text())
            pack = packs.get(region_id)
            if not pack or not pack.get("sqliteUrl"):
                return None

            url = pack["sqliteUrl"]
            logger.info(f"Downloading sqlite for {region_id} from {url}")
            with httpx.Client() as client:
                resp = client.get(url, timeout=30.0)
                if resp.status_code == 200:
                    with tempfile.NamedTemporaryFile(delete=False, suffix=".sqlite") as tmp:
                        tmp.write(resp.content)
                        tmp_path = tmp.name
                    conn = sqlite3.connect(tmp_path, check_same_thread=False)
                    _sqlite_conns[region_id] = conn
                    return conn
        except Exception as e:
            logger.error(f"Error downloading sqlite for {region_id}: {e}")
            return None


def point_in_polygon(lat: float, lon: float, geojson_str: str) -> bool:
    try:
        geo = json.loads(geojson_str)
        # Simplified point in polygon for HIGH risk zones (assuming single polygon for simplicity or handle multipolygon)
        # However, a proper implementation requires ray casting.
        from shapely.geometry import Point, shape
        pt = Point(lon, lat)
        poly = shape(geo)
        return poly.contains(pt)
    except Exception:
        # If shapely is not available, we could do a crude check or return False.
        # Wait, shapely isn't in requirements. Let's do a pure python ray casting or check if there's a better way.
        pass

    # Pure python fallback for Polygon
    try:
        geo = json.loads(geojson_str)
        poly_type = geo.get("type")
        coords = geo.get("coordinates", [])

        def ray_cast(x, y, poly):
            n = len(poly)
            inside = False
            p1x, p1y = poly[0]
            for i in range(n+1):
                p2x, p2y = poly[i % n]
                if y > min(p1y, p2y):
                    if y <= max(p1y, p2y):
                        if x <= max(p1x, p2x):
                            if p1y != p2y:
                                xints = (y - p1y) * (p2x - p1x) / (p2y - p1y) + p1x
                            if p1x == p2x or x <= xints:
                                inside = not inside
                p1x, p1y = p2x, p2y
            return inside

        if poly_type == "Polygon":
            return ray_cast(lon, lat, coords[0])
        elif poly_type == "MultiPolygon":
            for poly in coords:
                if ray_cast(lon, lat, poly[0]):
                    return True
    except Exception as e:
        logger.warning(f"Error in pure python ray cast: {e}")
    return False

def determine_group_status(
    lat: float, lon: float, region_id: str, fl_reports: list[dict]
) -> str:
    # 1. AT_SHELTER if within 150m of a SHELTER/CANDIDATE_SHELTER
    conn = _get_sqlite_conn(region_id)
    if conn:
        try:
            cur = conn.cursor()
            cur.execute("SELECT lat, lon FROM poi WHERE type IN ('SHELTER', 'CANDIDATE_SHELTER')")
            for r in cur.fetchall():
                if _haversine_distance(lat, lon, r[0], r[1]) <= 150:
                    return "AT_SHELTER"
        except Exception as e:
            logger.error(f"Error querying POI: {e}")

    # 2. RISK_ZONE if inside HIGH risk zone
    if conn:
        try:
            cur = conn.cursor()
            cur.execute("SELECT geojson FROM risk_zone WHERE level = 'HIGH'")
            for r in cur.fetchall():
                if point_in_polygon(lat, lon, r[0]):
                    return "RISK_ZONE"
        except Exception as e:
            logger.error(f"Error querying risk zones: {e}")

    # 3. RISK_ZONE if within 300m of VERIFIED/LIKELY FL report
    for rep in fl_reports:
        if rep.get("type") == "FL" and rep.get("label") in ("VERIFIED", "LIKELY"):
            if _haversine_distance(lat, lon, rep["lat"], rep["lon"]) <= 300:
                return "RISK_ZONE"

    return "SAFE_AREA"


def calculate_groups(
    presences: list[dict], region_id: str, fl_reports: list[dict]
) -> list[dict]:
    if not presences:
        return []

    min_size = int(getattr(settings, "GROUP_MIN_SIZE", 5))

    coords = np.array([[math.radians(p["lat"]), math.radians(p["lon"])] for p in presences])

    eps = 100 / 6371000.0
    db = DBSCAN(eps=eps, min_samples=min_size, metric="haversine", algorithm="ball_tree").fit(coords)

    labels = db.labels_

    groups_out = []
    unique_labels = set(labels)
    for k in unique_labels:
        if k == -1:
            continue

        class_member_mask = (labels == k)
        cluster_presences = [p for p, m in zip(presences, class_member_mask) if m]

        # mean rounded to 3 decimals
        mean_lat = round(sum(p["lat"] for p in cluster_presences) / len(cluster_presences), 3)
        mean_lon = round(sum(p["lon"] for p in cluster_presences) / len(cluster_presences), 3)

        status = determine_group_status(mean_lat, mean_lon, region_id, fl_reports)

        # latest last_seen
        last_seen = max(p["updated_at"] for p in cluster_presences)

        groups_out.append({
            "id": f"g_{k}_{last_seen}",  # generate an ID
            "lat": mean_lat,
            "lon": mean_lon,
            "size": len(cluster_presences),
            "status": status,
            "last_seen": last_seen
        })

    return groups_out
