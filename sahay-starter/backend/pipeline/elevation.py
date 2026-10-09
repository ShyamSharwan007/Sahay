"""Open-Meteo elevation lookups: batches of 100, polite rate limit, disk cache, retry with backoff.

A coordinate that cannot be resolved after all retries is returned as None (stored as NULL in the pack and
treated as 0 m by the risk formula). Failures are never written to the cache, so a re-run retries them.
"""
import math
import random
import sqlite3
import time
from collections.abc import Callable, Iterable
from pathlib import Path

import requests

API_URL = "https://api.open-meteo.com/v1/elevation"
BATCH_SIZE = 100
KEY_DECIMALS = 4  # ~11 m; the DEM is 90 m resolution, so finer keys only cost extra requests
_SCALE = 10 ** KEY_DECIMALS

Point = tuple[float, float]  # (lat, lon)
Key = tuple[int, int]


def cache_key(lat: float, lon: float) -> Key:
    return round(lat * _SCALE), round(lon * _SCALE)


def key_coords(key: Key) -> Point:
    return key[0] / _SCALE, key[1] / _SCALE


class ElevationCache:
    """SQLite cache; a row means 'the API answered' (elevation may be NULL, e.g. for open sea)."""

    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(path)
        self._db.execute(
            "CREATE TABLE IF NOT EXISTS elevation ("
            "lat_k INTEGER NOT NULL, lon_k INTEGER NOT NULL, elevation_m REAL, PRIMARY KEY (lat_k, lon_k))"
        )

    def get_many(self, keys: Iterable[Key]) -> dict[Key, float | None]:
        found: dict[Key, float | None] = {}
        for key in keys:
            row = self._db.execute(
                "SELECT elevation_m FROM elevation WHERE lat_k = ? AND lon_k = ?", key
            ).fetchone()
            if row is not None:
                found[key] = row[0]
        return found

    def put_many(self, values: dict[Key, float | None]) -> None:
        with self._db:
            self._db.executemany(
                "INSERT OR REPLACE INTO elevation (lat_k, lon_k, elevation_m) VALUES (?, ?, ?)",
                [(k[0], k[1], v) for k, v in values.items()],
            )

    def close(self) -> None:
        self._db.close()


class ElevationClient:
    def __init__(
        self,
        cache: ElevationCache,
        session: requests.Session | None = None,
        sleep: Callable[[float], None] = time.sleep,
        delay_s: float = 0.4,
        max_retries: int = 5,
        max_failed_batches: int = 3,
        log: Callable[[str], None] = print,
    ):
        self._cache = cache
        self._session = session or requests.Session()
        self._sleep = sleep
        self._delay_s = delay_s
        self._max_retries = max_retries
        self._max_failed_batches = max_failed_batches
        self._log = log

    def lookup(self, points: Iterable[Point]) -> dict[Point, float | None]:
        """Elevation in metres for every input point (None if it could not be resolved)."""
        points = list(dict.fromkeys(points))
        keys = {cache_key(lat, lon) for lat, lon in points}
        resolved = self._cache.get_many(keys)
        missing = sorted(keys - resolved.keys())
        self._log(f"  elevation: {len(keys)} unique points, {len(resolved)} cached, {len(missing)} to fetch")

        failed_in_a_row = 0
        batches = [missing[i:i + BATCH_SIZE] for i in range(0, len(missing), BATCH_SIZE)]
        for n, batch in enumerate(batches, start=1):
            if failed_in_a_row >= self._max_failed_batches:
                self._log("  elevation: too many failed batches in a row (rate limit?), giving up; "
                          "re-run later, cached answers are kept")
                break
            values = self._fetch_batch(batch)
            if values is None:
                failed_in_a_row += 1
                continue
            failed_in_a_row = 0
            fetched = dict(zip(batch, values))
            self._cache.put_many(fetched)
            resolved.update(fetched)
            if n % 10 == 0 or n == len(batches):
                self._log(f"  elevation: batch {n}/{len(batches)}")
            self._sleep(self._delay_s)

        return {p: resolved.get(cache_key(*p)) for p in points}

    def _fetch_batch(self, keys: list[Key]) -> list[float | None] | None:
        coords = [key_coords(k) for k in keys]
        params = {
            "latitude": ",".join(f"{lat:.{KEY_DECIMALS}f}" for lat, _ in coords),
            "longitude": ",".join(f"{lon:.{KEY_DECIMALS}f}" for _, lon in coords),
        }
        for attempt in range(self._max_retries):
            retry_after = None
            try:
                response = self._session.get(API_URL, params=params, timeout=30)
            except requests.RequestException as exc:
                self._log(f"  elevation: network error ({type(exc).__name__}), attempt {attempt + 1}")
            else:
                if response.status_code == 200:
                    values = _parse_elevations(response, len(keys))
                    if values is None:
                        self._log("  elevation: malformed response, skipping batch")
                    return values
                if response.status_code != 429 and response.status_code < 500:
                    self._log(f"  elevation: HTTP {response.status_code}, skipping batch")
                    return None  # client error: retrying will not help
                retry_after = _retry_after_seconds(response)
                self._log(f"  elevation: HTTP {response.status_code}, attempt {attempt + 1}")
            if attempt < self._max_retries - 1:
                backoff = min(60.0, 2.0 ** (attempt + 1)) + random.uniform(0, 1)
                self._sleep(max(backoff, retry_after or 0.0))
        return None


def _parse_elevations(response: requests.Response, expected: int) -> list[float | None] | None:
    try:
        raw = response.json()["elevation"]
    except (ValueError, KeyError, TypeError):
        return None
    if not isinstance(raw, list) or len(raw) != expected:
        return None
    return [float(v) if isinstance(v, (int, float)) and math.isfinite(v) else None for v in raw]


def _retry_after_seconds(response: requests.Response) -> float | None:
    try:
        return float(response.headers.get("Retry-After", ""))
    except ValueError:
        return None
