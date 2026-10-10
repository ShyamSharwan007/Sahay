"""Tests for app.wire — builders and parser."""

from __future__ import annotations

import pytest

from app.wire import (
    MAX_WIRE_LEN,
    WireAlert,
    WireBeacon,
    WireGroup,
    WirePresence,
    WireReport,
    WireShelter,
    build_alert,
    build_beacon,
    build_group,
    build_presence,
    build_report,
    build_shelter,
    parse,
)

TS = 1760000000


class TestBuilders:
    """Verify wire builders produce correct format strings."""

    def test_build_alert(self, test_keypair):
        priv, pub, _, _ = test_keypair
        wire = build_alert("a1b2c3", "FLD_EVAC", 3, 12.6208, 80.1945, 2000, True, TS, priv)
        assert wire.startswith("SH1*A*a1b2c3*FLD_EVAC*3*")
        assert len(wire) <= MAX_WIRE_LEN
        parts = wire.split("*")
        assert len(parts) == 10  # correct field count

    def test_build_shelter(self, test_keypair):
        priv, pub, _, _ = test_keypair
        wire = build_shelter("poi_123", "F", TS, priv)
        assert wire.startswith("SH1*S*poi_123*F*")
        parts = wire.split("*")
        assert len(parts) == 6

    def test_build_group(self, test_keypair):
        priv, pub, _, _ = test_keypair
        wire = build_group(12.621, 80.193, 7, "A", TS, priv)
        assert "SH1*G*12.621,80.193*7*A*" in wire
        parts = wire.split("*")
        assert len(parts) == 7

    def test_build_report(self):
        wire = build_report("FL", 12.62, 80.19, 2, TS, "abc12345")
        assert wire.startswith("SH1*R*FL*")
        parts = wire.split("*")
        assert len(parts) == 7

    def test_build_presence(self):
        wire = build_presence(12.621, 80.193, TS, "abc12345")
        assert wire.startswith("SH1*P*12.621,80.193*")
        parts = wire.split("*")
        assert len(parts) == 5

    def test_build_beacon(self):
        wire = build_beacon(12.62, 80.19, TS, "abc12345")
        assert wire.startswith("SH1*B*12.620,80.190*")
        parts = wire.split("*")
        assert len(parts) == 5


class TestParser:
    """Verify parsing and validation logic."""

    def test_parse_alert(self, test_keypair):
        priv, pub, _, _ = test_keypair
        wire = build_alert("a1b2c3", "FLD_EVAC", 3, 12.6208, 80.1945, 2000, True, TS, priv)
        msg = parse(wire, pub, check_time_window=False)
        assert isinstance(msg, WireAlert)
        assert msg.id == "a1b2c3"
        assert msg.template_code == "FLD_EVAC"
        assert msg.severity == 3
        assert msg.radius_m == 2000
        assert msg.flags == "S"

    def test_parse_shelter(self, test_keypair):
        priv, pub, _, _ = test_keypair
        wire = build_shelter("poi_123", "O", TS, priv)
        msg = parse(wire, pub, check_time_window=False)
        assert isinstance(msg, WireShelter)
        assert msg.shelter_id == "poi_123"
        assert msg.status == "O"

    def test_parse_group(self, test_keypair):
        priv, pub, _, _ = test_keypair
        wire = build_group(12.621, 80.193, 7, "R", TS, priv)
        msg = parse(wire, pub, check_time_window=False)
        assert isinstance(msg, WireGroup)
        assert msg.size == 7
        assert msg.status == "R"

    def test_parse_report(self):
        wire = build_report("FL", 12.62, 80.19, 2, TS, "uid12345")
        msg = parse(wire, check_time_window=False)
        assert isinstance(msg, WireReport)
        assert msg.type_code == "FL"
        assert msg.uid8 == "uid12345"

    def test_parse_presence(self):
        wire = build_presence(12.621, 80.193, TS, "uid12345")
        msg = parse(wire, check_time_window=False)
        assert isinstance(msg, WirePresence)
        assert msg.uid8 == "uid12345"

    def test_parse_beacon(self):
        wire = build_beacon(0.0, 0.0, TS, "uid12345")
        msg = parse(wire, check_time_window=False)
        assert isinstance(msg, WireBeacon)
        assert msg.lat == 0.0

    def test_reject_wrong_prefix(self, test_keypair):
        priv, pub, _, _ = test_keypair
        wire = build_alert("a1b2c3", "FLD_EVAC", 3, 12.6208, 80.1945, 2000, True, TS, priv)
        bad = "SH2" + wire[3:]
        with pytest.raises(ValueError, match="prefix"):
            parse(bad, pub, check_time_window=False)

    def test_reject_wrong_field_count(self, test_keypair):
        priv, pub, _, _ = test_keypair
        wire = build_alert("a1b2c3", "FLD_EVAC", 3, 12.6208, 80.1945, 2000, True, TS, priv)
        parts = wire.split("*")
        bad = "*".join(parts[:7] + parts[8:])  # remove flags
        with pytest.raises(ValueError, match="field count"):
            parse(bad, pub, check_time_window=False)

    def test_reject_bad_signature(self, test_keypair):
        priv, pub, _, _ = test_keypair
        wire = build_alert("a1b2c3", "FLD_EVAC", 3, 12.6208, 80.1945, 2000, True, TS, priv)
        bad = wire[:-4] + "XXXX"
        with pytest.raises(ValueError, match="signature"):
            parse(bad, pub, check_time_window=False)

    def test_reject_tampered_field(self, test_keypair):
        priv, pub, _, _ = test_keypair
        wire = build_alert("a1b2c3", "FLD_EVAC", 3, 12.6208, 80.1945, 2000, True, TS, priv)
        tampered = wire.replace("*3*12.62080", "*2*12.62080")
        with pytest.raises(ValueError, match="signature"):
            parse(tampered, pub, check_time_window=False)

    def test_reject_over_length(self, test_keypair):
        priv, pub, _, _ = test_keypair
        # Manually craft a wire > 160 chars
        long_id = "x" * 80
        from app.signing import sign

        payload = f"SH1*S*{long_id}*O*{TS}*"
        sig = sign(payload.encode(), priv)
        wire = payload + sig
        assert len(wire) > MAX_WIRE_LEN
        with pytest.raises(ValueError, match="160"):
            parse(wire, pub, check_time_window=False)

    def test_coordinate_rounding_5(self):
        """Alert/report coords are rounded to 5 decimals."""
        wire = build_report("FL", 12.620801234, 80.194501234, 2, TS, "uid12345")
        msg = parse(wire, check_time_window=False)
        assert isinstance(msg, WireReport)
        assert msg.lat == 12.6208
        assert msg.lon == 80.1945

    def test_coordinate_rounding_3(self):
        """Group/presence/beacon coords are rounded to 3 decimals."""
        wire = build_presence(12.6211234, 80.1931234, TS, "uid12345")
        msg = parse(wire, check_time_window=False)
        assert isinstance(msg, WirePresence)
        assert msg.lat == 12.621
        assert msg.lon == 80.193
