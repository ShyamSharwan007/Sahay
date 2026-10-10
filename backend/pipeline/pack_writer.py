"""Writes a pack SQLite file with the exact CONTRACTS 5.2 schema."""
import os
import re
import sqlite3
from collections.abc import Sequence
from contextlib import closing
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

from .content import ContentRows
from .graph import Edge
from .pois import Poi
from .schema import META_KEYS, SCHEMA_SQL, TABLES

_VERSION_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})\.(\d+)$")


@dataclass(frozen=True)
class ZoneRow:
    id: str
    name: str | None
    level: str  # HIGH | MEDIUM
    geojson: str


@dataclass
class PackData:
    meta: dict[str, str]
    nodes: Sequence[tuple[int, float, float, float | None]]  # id, lat, lon, elevation_m
    edges: Sequence[Edge]
    risk_costs: Sequence[float]  # parallel to `edges`
    pois: Sequence[Poi]
    zones: Sequence[ZoneRow]
    content: ContentRows = field(default_factory=ContentRows)


def next_pack_version(previous: str | None, today: date) -> str:
    """YYYY-MM-DD.N: N counts rebuilds on the same day, so versions always increase."""
    stamp = today.isoformat()
    match = _VERSION_RE.match(previous or "")
    if match and match.group(1) == stamp:
        return f"{stamp}.{int(match.group(2)) + 1}"
    return f"{stamp}.1"


def read_meta(path: Path) -> dict[str, str]:
    """meta table of an existing pack ({} if the file is missing or not a pack)."""
    if not path.exists():
        return {}
    try:
        with closing(sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)) as db:
            return dict(db.execute("SELECT key, value FROM meta"))
    except sqlite3.DatabaseError:
        return {}


def write_pack(path: Path, data: PackData) -> dict[str, int]:
    """Create `path` atomically (temp file + rename), VACUUM it, and return the row count of every table."""
    missing = [k for k in META_KEYS if k not in data.meta]
    if missing:
        raise ValueError(f"meta is missing keys: {missing}")
    if len(data.edges) != len(data.risk_costs):
        raise ValueError("risk_costs must be parallel to edges")

    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.unlink(missing_ok=True)
    try:
        with closing(sqlite3.connect(tmp)) as db:
            db.executescript(SCHEMA_SQL)
            with db:
                _insert_all(db, data)
            db.execute("VACUUM")
            counts = {t: db.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in TABLES}
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)
    return counts


def _insert_all(db: sqlite3.Connection, data: PackData) -> None:
    db.executemany("INSERT INTO meta (key, value) VALUES (?, ?)", sorted(data.meta.items()))
    db.executemany("INSERT INTO node (id, lat, lon, elevation_m) VALUES (?, ?, ?, ?)", data.nodes)
    db.executemany(
        "INSERT INTO edge (from_id, to_id, length_m, risk_cost, road_class) VALUES (?, ?, ?, ?, ?)",
        [(e.from_id, e.to_id, round(e.length_m, 2), risk, e.road_class) for e, risk in zip(data.edges, data.risk_costs)],
    )
    db.executemany(
        "INSERT INTO poi (id, type, name, name_ta, lat, lon, phone, is_official, elevation_m, capacity) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [(p.id, p.type, p.name, p.name_ta, p.lat, p.lon, p.phone, int(p.is_official), p.elevation_m, p.capacity)
         for p in data.pois],
    )
    db.executemany(
        "INSERT INTO risk_zone (id, name, level, geojson) VALUES (?, ?, ?, ?)",
        [(z.id, z.name, z.level, z.geojson) for z in data.zones],
    )
    content = data.content
    db.executemany("INSERT INTO alert_template VALUES (?, ?, ?, ?, ?)", content.alert_templates)
    db.executemany("INSERT INTO alert_keyword VALUES (?, ?, ?)", content.alert_keywords)
    db.executemany("INSERT INTO phrase VALUES (?, ?, ?, ?, ?)", content.phrases)
    db.executemany("INSERT INTO embassy VALUES (?, ?, ?, ?, ?, ?, ?)", content.embassies)
    db.executemany("INSERT INTO radio VALUES (?, ?, ?)", content.radio)
