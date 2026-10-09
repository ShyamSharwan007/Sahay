"""Tests for wire_test_vectors.json — validate each case against the parser."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.signing import load_public_key_from_b64
from app.wire import parse


def _load_vectors():
    """Load the test vectors file. Skip if it doesn't exist yet."""
    path = Path(__file__).resolve().parent.parent.parent / "docs" / "wire_test_vectors.json"
    if not path.exists():
        pytest.skip("wire_test_vectors.json not generated yet — run make_test_vectors.py first")
    return json.loads(path.read_text())


def test_vectors_file_structure():
    """Verify the vectors file has the expected top-level keys."""
    data = _load_vectors()
    assert "publicKeyB64" in data
    assert "cases" in data
    assert isinstance(data["cases"], list)
    assert len(data["cases"]) >= 6  # at least 3 valid + 3 invalid


_VECTORS_PATH = (
    Path(__file__).resolve().parent.parent.parent / "docs" / "wire_test_vectors.json"
)
_CASES = (
    _load_vectors().get("cases", []) if _VECTORS_PATH.exists() else []
)


@pytest.mark.parametrize(
    "case",
    _CASES,
    ids=lambda c: c.get("note", "")[:60],
)
def test_each_vector(case):
    """For each test vector, parsing should succeed (valid) or raise (invalid)."""
    data = _load_vectors()
    pub_key = load_public_key_from_b64(data["publicKeyB64"])
    wire = case["wire"]
    expected_valid = case["valid"]

    if expected_valid:
        # Should parse without error (time window ignored per spec)
        msg = parse(wire, pub_key, check_time_window=False)
        assert msg is not None
    else:
        with pytest.raises(ValueError):
            parse(wire, pub_key, check_time_window=False)
