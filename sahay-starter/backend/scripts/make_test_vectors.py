#!/usr/bin/env python3
"""Generate wire test vectors → docs/wire_test_vectors.json.

Uses a TEST keypair (never the production key).
Outputs valid and invalid cases for alert, shelter, group, and various
invalid mutations.

Usage:
    uv run python scripts/make_test_vectors.py
"""

from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

# Add project root to path so we can import app modules
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.signing import sign
from app.wire import MAX_WIRE_LEN, build_alert, build_group, build_shelter


def main():
    # Generate a dedicated TEST keypair
    test_private = Ed25519PrivateKey.generate()
    test_public_bytes = test_private.public_key().public_bytes_raw()
    test_public_b64 = base64.b64encode(test_public_bytes).decode()

    ts = 1760000000  # fixed timestamp for reproducibility

    cases: list[dict] = []

    # --- Valid cases ---
    alert_wire = build_alert(
        alert_id="a1b2c3",
        template_code="FLD_EVAC",
        severity=3,
        lat=12.62080,
        lon=80.19450,
        radius_m=2000,
        is_simulation=True,
        ts=ts,
        private_key=test_private,
    )
    cases.append(
        {
            "wire": alert_wire,
            "valid": True,
            "note": "Valid alert – FLD_EVAC severity 3, simulation flag",
        }
    )

    shelter_wire = build_shelter(
        shelter_id="poi_123",
        status="F",
        ts=ts,
        private_key=test_private,
    )
    cases.append(
        {
            "wire": shelter_wire,
            "valid": True,
            "note": "Valid shelter status – FULL",
        }
    )

    group_wire = build_group(
        lat=12.621,
        lon=80.193,
        size=7,
        status="A",
        ts=ts,
        private_key=test_private,
    )
    cases.append(
        {
            "wire": group_wire,
            "valid": True,
            "note": "Valid group – 7 people AT_SHELTER",
        }
    )

    # --- Invalid cases ---

    # 1. Tampered field (change severity from 3 to 2 in the alert wire)
    tampered = alert_wire.replace("*3*12.62080", "*2*12.62080")
    cases.append(
        {
            "wire": tampered,
            "valid": False,
            "note": "Invalid – tampered severity field (3→2), signature mismatch",
        }
    )

    # 2. Bad signature (corrupt last chars of the signature)
    bad_sig = alert_wire[:-4] + "XXXX"
    cases.append(
        {
            "wire": bad_sig,
            "valid": False,
            "note": "Invalid – corrupted signature bytes",
        }
    )

    # 3. Wrong prefix
    wrong_prefix = "SH2" + alert_wire[3:]
    cases.append(
        {
            "wire": wrong_prefix,
            "valid": False,
            "note": "Invalid – wrong prefix SH2 instead of SH1",
        }
    )

    # 4. Wrong field count (remove the flags field from alert)
    parts = alert_wire.split("*")
    # Remove element 7 (flags) to get wrong field count
    wrong_count_parts = parts[:7] + parts[8:]
    wrong_count = "*".join(wrong_count_parts)
    cases.append(
        {
            "wire": wrong_count,
            "valid": False,
            "note": "Invalid – wrong field count for Alert (missing flags field)",
        }
    )

    # 5. Over 160 characters — build manually (builders enforce the limit)
    long_id = "poi_" + "x" * 70
    payload = f"SH1*S*{long_id}*O*{ts}*"
    sig = sign(payload.encode("utf-8"), test_private)
    long_wire = payload + sig
    assert len(long_wire) > MAX_WIRE_LEN, "Expected >160 chars for the over-length test case"
    cases.append(
        {
            "wire": long_wire,
            "valid": False,
            "note": f"Invalid – wire length {len(long_wire)} exceeds 160 char limit",
        }
    )

    output = {
        "publicKeyB64": test_public_b64,
        "cases": cases,
    }

    out_path = Path(__file__).resolve().parent.parent.parent / "docs" / "wire_test_vectors.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(output, indent=2) + "\n")
    print(f"Wrote {len(cases)} test vectors to {out_path}")


if __name__ == "__main__":
    main()
