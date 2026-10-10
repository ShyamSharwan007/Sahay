"""The pack schema must match CONTRACTS.md section 5.2 exactly."""
import re
import sqlite3

import pytest

from pipeline.pack_writer import write_pack
from pipeline.paths import CONTRACTS_MD
from pipeline.schema import META_KEYS, SCHEMA_SQL, TABLES

from .helpers import grid_pack_data


def _contract_sql() -> str:
    text = CONTRACTS_MD.read_text(encoding="utf-8")
    section = text.split("### 5.2", 1)[1].split("### 5.3", 1)[0]
    match = re.search(r"```sql\r?\n(.*?)```", section, re.DOTALL)
    assert match, "no sql block under 5.2"
    return match.group(1)


def _normalise(sql: str) -> str:
    return re.sub(r"\s+", " ", sql).strip()


def _describe(db: sqlite3.Connection) -> dict:
    tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")]
    return {
        "tables": {t: db.execute(f"PRAGMA table_info({t})").fetchall() for t in tables},
        "indexes": sorted(r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL")),
    }


@pytest.mark.skipif(not CONTRACTS_MD.exists(), reason="docs/CONTRACTS.md not found")
def test_schema_sql_is_verbatim_contract():
    assert _normalise(SCHEMA_SQL) == _normalise(_contract_sql())


@pytest.mark.skipif(not CONTRACTS_MD.exists(), reason="docs/CONTRACTS.md not found")
def test_built_pack_has_exactly_the_contract_tables_columns_and_indexes(tmp_path):
    contract = sqlite3.connect(":memory:")
    contract.executescript(_contract_sql())

    path = tmp_path / "pack.sqlite"
    write_pack(path, grid_pack_data())
    built = sqlite3.connect(path)

    assert _describe(built) == _describe(contract)


def test_expected_columns_written_out(tmp_path):
    """Independent of the docs file: guards the column names/order/nullability the Android side relies on."""
    path = tmp_path / "pack.sqlite"
    write_pack(path, grid_pack_data())
    db = sqlite3.connect(path)

    def columns(table):
        return [(r[1], r[2], bool(r[3])) for r in db.execute(f"PRAGMA table_info({table})")]  # name, type, notnull

    assert columns("edge") == [("from_id", "INTEGER", True), ("to_id", "INTEGER", True), ("length_m", "REAL", True),
                               ("risk_cost", "REAL", True), ("road_class", "TEXT", False)]
    assert [c[0] for c in columns("poi")] == ["id", "type", "name", "name_ta", "lat", "lon", "phone",
                                              "is_official", "elevation_m", "capacity"]
    assert [c[0] for c in columns("node")] == ["id", "lat", "lon", "elevation_m"]
    assert [c[0] for c in columns("risk_zone")] == ["id", "name", "level", "geojson"]
    assert sorted(r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")) == sorted(TABLES)


def test_meta_has_all_contract_keys(tmp_path):
    path = tmp_path / "pack.sqlite"
    write_pack(path, grid_pack_data())
    keys = {r[0] for r in sqlite3.connect(path).execute("SELECT key FROM meta")}
    assert keys == set(META_KEYS)


def test_missing_meta_key_is_rejected(tmp_path):
    data = grid_pack_data()
    del data.meta["public_key_b64"]
    with pytest.raises(ValueError, match="public_key_b64"):
        write_pack(tmp_path / "pack.sqlite", data)
    assert not (tmp_path / "pack.sqlite").exists()
