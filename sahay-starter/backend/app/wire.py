"""Wire-format builders and parser (CONTRACTS §4).

Wire string: `SH1*<TYPE>*<fields...>*<ts>*<sig-or-uid>`
- Separator: `*`
- ASCII only, max 160 characters
- Coordinates: 5 decimals for alerts/reports, 3 decimals for groups/presence/beacons
- Signature: base64url without padding (86 chars) over everything up to and
  including the last `*` before the signature field.

Signed types (server): A (Alert), S (Shelter), G (Group)
Unsigned types (user):  R (Report), P (Presence), B (Beacon)
"""

from __future__ import annotations

import time
from dataclasses import dataclass

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

from app.signing import sign, verify

MAX_WIRE_LEN = 160
PREFIX = "SH1"

# Expected field counts (total parts when split by '*')
FIELD_COUNTS: dict[str, int] = {
    "A": 10,  # SH1*A*id*code*sev*lat,lon*radius*flags*ts*sig
    "S": 6,  # SH1*S*shelterId*status*ts*sig
    "G": 7,  # SH1*G*lat,lon*size*status*ts*sig
    "R": 7,  # SH1*R*typeCode*lat,lon*sev*ts*uid8
    "P": 5,  # SH1*P*lat,lon*ts*uid8
    "B": 5,  # SH1*B*lat,lon*ts*uid8
}

# Signed types need signature verification
SIGNED_TYPES = {"A", "S", "G"}

# Valid time window: 48h in the past, 10min in the future (seconds)
TIME_WINDOW_PAST = 48 * 3600
TIME_WINDOW_FUTURE = 10 * 60

# Valid report type codes
REPORT_TYPE_CODES = {"FL", "RB", "SF", "SO", "PL", "LS", "OT"}
# Valid shelter status letters
SHELTER_STATUS_LETTERS = {"O", "F", "C"}
# Valid group status letters
GROUP_STATUS_LETTERS = {"A", "S", "R"}
# Valid alert flags
ALERT_FLAGS = {"S", "R"}


# ---------------------------------------------------------------------------
# Data classes for parsed wire messages
# ---------------------------------------------------------------------------
@dataclass
class WireAlert:
    id: str
    template_code: str
    severity: int
    lat: float
    lon: float
    radius_m: int
    flags: str
    ts: int
    signature: str


@dataclass
class WireShelter:
    shelter_id: str
    status: str  # O/F/C
    ts: int
    signature: str


@dataclass
class WireGroup:
    lat: float
    lon: float
    size: int
    status: str  # A/S/R
    ts: int
    signature: str


@dataclass
class WireReport:
    type_code: str
    lat: float
    lon: float
    severity: int
    ts: int
    uid8: str


@dataclass
class WirePresence:
    lat: float
    lon: float
    ts: int
    uid8: str


@dataclass
class WireBeacon:
    lat: float
    lon: float
    ts: int
    uid8: str


WireMessage = WireAlert | WireShelter | WireGroup | WireReport | WirePresence | WireBeacon


# ---------------------------------------------------------------------------
# Coordinate formatting helpers
# ---------------------------------------------------------------------------
def _fmt5(v: float) -> str:
    """Format coordinate to 5 decimal places (alerts/reports)."""
    return f"{v:.5f}"


def _fmt3(v: float) -> str:
    """Format coordinate to 3 decimal places (groups/presence/beacons)."""
    return f"{v:.3f}"


def _round5(v: float) -> float:
    return round(v, 5)


def _round3(v: float) -> float:
    return round(v, 3)


# ---------------------------------------------------------------------------
# Builders
# ---------------------------------------------------------------------------
def build_alert(
    alert_id: str,
    template_code: str,
    severity: int,
    lat: float,
    lon: float,
    radius_m: int,
    is_simulation: bool,
    ts: int | None = None,
    private_key: Ed25519PrivateKey | None = None,
) -> str:
    """Build a signed Alert wire string."""
    if ts is None:
        ts = int(time.time())
    flags = "S" if is_simulation else "R"
    coords = f"{_fmt5(lat)},{_fmt5(lon)}"
    # Everything up to and including the last '*' before sig
    payload = f"{PREFIX}*A*{alert_id}*{template_code}*{severity}*{coords}*{radius_m}*{flags}*{ts}*"
    sig = sign(payload.encode("utf-8"), private_key)
    wire = payload + sig
    if len(wire) > MAX_WIRE_LEN:
        raise ValueError(f"Alert wire exceeds {MAX_WIRE_LEN} chars: {len(wire)}")
    return wire


def build_shelter(
    shelter_id: str,
    status: str,
    ts: int | None = None,
    private_key: Ed25519PrivateKey | None = None,
) -> str:
    """Build a signed Shelter-status wire string. status: O/F/C."""
    if ts is None:
        ts = int(time.time())
    if status not in SHELTER_STATUS_LETTERS:
        raise ValueError(f"Invalid shelter status: {status}")
    payload = f"{PREFIX}*S*{shelter_id}*{status}*{ts}*"
    sig = sign(payload.encode("utf-8"), private_key)
    wire = payload + sig
    if len(wire) > MAX_WIRE_LEN:
        raise ValueError(f"Shelter wire exceeds {MAX_WIRE_LEN} chars: {len(wire)}")
    return wire


def build_group(
    lat: float,
    lon: float,
    size: int,
    status: str,
    ts: int | None = None,
    private_key: Ed25519PrivateKey | None = None,
) -> str:
    """Build a signed Group wire string. status: A/S/R."""
    if ts is None:
        ts = int(time.time())
    if status not in GROUP_STATUS_LETTERS:
        raise ValueError(f"Invalid group status: {status}")
    coords = f"{_fmt3(lat)},{_fmt3(lon)}"
    payload = f"{PREFIX}*G*{coords}*{size}*{status}*{ts}*"
    sig = sign(payload.encode("utf-8"), private_key)
    wire = payload + sig
    if len(wire) > MAX_WIRE_LEN:
        raise ValueError(f"Group wire exceeds {MAX_WIRE_LEN} chars: {len(wire)}")
    return wire


def build_report(
    type_code: str,
    lat: float,
    lon: float,
    severity: int,
    ts: int | None = None,
    uid8: str = "anon0000",
) -> str:
    """Build an unsigned Report wire string."""
    if ts is None:
        ts = int(time.time())
    if type_code not in REPORT_TYPE_CODES:
        raise ValueError(f"Invalid report type: {type_code}")
    coords = f"{_fmt5(lat)},{_fmt5(lon)}"
    wire = f"{PREFIX}*R*{type_code}*{coords}*{severity}*{ts}*{uid8}"
    if len(wire) > MAX_WIRE_LEN:
        raise ValueError(f"Report wire exceeds {MAX_WIRE_LEN} chars: {len(wire)}")
    return wire


def build_presence(
    lat: float,
    lon: float,
    ts: int | None = None,
    uid8: str = "anon0000",
) -> str:
    """Build an unsigned Presence wire string."""
    if ts is None:
        ts = int(time.time())
    coords = f"{_fmt3(lat)},{_fmt3(lon)}"
    wire = f"{PREFIX}*P*{coords}*{ts}*{uid8}"
    if len(wire) > MAX_WIRE_LEN:
        raise ValueError(f"Presence wire exceeds {MAX_WIRE_LEN} chars: {len(wire)}")
    return wire


def build_beacon(
    lat: float,
    lon: float,
    ts: int | None = None,
    uid8: str = "anon0000",
) -> str:
    """Build an unsigned Beacon wire string."""
    if ts is None:
        ts = int(time.time())
    coords = f"{_fmt3(lat)},{_fmt3(lon)}"
    wire = f"{PREFIX}*B*{coords}*{ts}*{uid8}"
    if len(wire) > MAX_WIRE_LEN:
        raise ValueError(f"Beacon wire exceeds {MAX_WIRE_LEN} chars: {len(wire)}")
    return wire


# ---------------------------------------------------------------------------
# Parser
# ---------------------------------------------------------------------------
def parse(
    wire: str,
    public_key: Ed25519PublicKey | None = None,
    check_time_window: bool = True,
) -> WireMessage:
    """Parse and validate a wire string. Returns a typed dataclass.

    Raises ValueError on any validation failure.
    """
    # Length check
    if len(wire) > MAX_WIRE_LEN:
        raise ValueError(f"Wire exceeds {MAX_WIRE_LEN} chars: {len(wire)}")

    parts = wire.split("*")

    # Prefix check
    if not parts or parts[0] != PREFIX:
        raise ValueError(f"Invalid prefix: expected '{PREFIX}'")

    if len(parts) < 2:
        raise ValueError("Missing type field")

    wire_type = parts[1]

    # Field count check
    expected = FIELD_COUNTS.get(wire_type)
    if expected is None:
        raise ValueError(f"Unknown wire type: {wire_type}")
    if len(parts) != expected:
        raise ValueError(
            f"Wrong field count for type {wire_type}: expected {expected}, got {len(parts)}"
        )

    # Signature verification for signed types
    if wire_type in SIGNED_TYPES:
        sig = parts[-1]
        # Signed bytes = everything up to and including the last '*' before sig
        signed_payload = "*".join(parts[:-1]) + "*"
        if not verify(signed_payload.encode("utf-8"), sig, public_key):
            raise ValueError("Invalid signature")

    # Timestamp validation
    ts_index = -2 if wire_type in SIGNED_TYPES else -2
    ts_str = parts[ts_index]
    try:
        ts = int(ts_str)
    except ValueError as exc:
        raise ValueError(f"Invalid timestamp: {ts_str}") from exc

    if check_time_window:
        now = int(time.time())
        if ts < now - TIME_WINDOW_PAST:
            raise ValueError("Timestamp too old (>48h)")
        if ts > now + TIME_WINDOW_FUTURE:
            raise ValueError("Timestamp too far in the future (>10min)")

    # Type-specific parsing
    if wire_type == "A":
        lat_s, lon_s = parts[5].split(",")
        return WireAlert(
            id=parts[2],
            template_code=parts[3],
            severity=int(parts[4]),
            lat=_round5(float(lat_s)),
            lon=_round5(float(lon_s)),
            radius_m=int(parts[6]),
            flags=parts[7],
            ts=ts,
            signature=parts[9],
        )

    if wire_type == "S":
        return WireShelter(
            shelter_id=parts[2],
            status=parts[3],
            ts=ts,
            signature=parts[5],
        )

    if wire_type == "G":
        lat_s, lon_s = parts[2].split(",")
        return WireGroup(
            lat=_round3(float(lat_s)),
            lon=_round3(float(lon_s)),
            size=int(parts[3]),
            status=parts[4],
            ts=ts,
            signature=parts[6],
        )

    if wire_type == "R":
        lat_s, lon_s = parts[3].split(",")
        return WireReport(
            type_code=parts[2],
            lat=_round5(float(lat_s)),
            lon=_round5(float(lon_s)),
            severity=int(parts[4]),
            ts=ts,
            uid8=parts[6],
        )

    if wire_type == "P":
        lat_s, lon_s = parts[2].split(",")
        return WirePresence(
            lat=_round3(float(lat_s)),
            lon=_round3(float(lon_s)),
            ts=ts,
            uid8=parts[4],
        )

    # wire_type == "B"
    lat_s, lon_s = parts[2].split(",")
    return WireBeacon(
        lat=_round3(float(lat_s)),
        lon=_round3(float(lon_s)),
        ts=ts,
        uid8=parts[4],
    )
