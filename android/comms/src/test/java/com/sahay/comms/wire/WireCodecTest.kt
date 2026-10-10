package com.sahay.comms.wire

import com.sahay.core.contracts.GroupStatus
import com.sahay.core.contracts.HazardType
import com.sahay.core.contracts.ShelterStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class WireCodecTest {

    private val codec = WireCodec(TestVectors.clock)
    private val now = TestVectors.NOW_SEC

    private fun ok(wire: String) = (codec.parse(wire) as WireParseResult.Ok).message
    private fun rejectedWith(wire: String) = (codec.parse(wire) as WireParseResult.Rejected).error

    // ------------------------------------------------------------------ signed types

    @Test fun `parses the alert vector`() {
        val alert = ok(TestVectors.firstValid("A")) as WireMessage.Alert
        assertEquals("a1b2c3", alert.id)
        assertEquals("FLD_EVAC", alert.templateCode)
        assertEquals(3, alert.severity)
        assertEquals(12.6208, alert.lat, 1e-9)
        assertEquals(80.1945, alert.lon, 1e-9)
        assertEquals(2000, alert.radiusM)
        assertTrue(alert.isSimulation)
        assertEquals(1_760_000_000L, alert.timestamp)
        assertEquals(86, alert.signature.length)
    }

    @Test fun `parses the shelter and group vectors`() {
        val shelter = ok(TestVectors.firstValid("S")) as WireMessage.ShelterStatusUpdate
        assertEquals("poi_123", shelter.shelterId)
        assertEquals(ShelterStatus.FULL, shelter.status)

        val group = ok(TestVectors.firstValid("G")) as WireMessage.Group
        assertEquals(7, group.size)
        assertEquals(GroupStatus.AT_SHELTER, group.status)
        assertEquals(12.621, group.lat, 1e-9)
    }

    @Test fun `valid vectors survive parse then encode unchanged`() {
        TestVectors.cases.filter { it.valid }.forEach { assertEquals(it.wire, codec.encode(ok(it.wire))) }
    }

    // ------------------------------------------------------------------ unsigned types

    @Test fun `report presence and beacon round trip`() {
        val report = WireMessage.Report(HazardType.FLOOD, 12.62, 80.19, 2, now, "abcd1234")
        assertEquals("SH1*R*FL*12.62000,80.19000*2*$now*abcd1234", codec.encode(report))
        assertEquals(report, ok(codec.encode(report)))

        val presence = WireMessage.Presence(12.6213, 80.1937, now, "anon0000")
        assertEquals("SH1*P*12.621,80.194*$now*anon0000", codec.encode(presence))
        assertEquals(WireMessage.Presence(12.621, 80.194, now, "anon0000"), ok(codec.encode(presence)))

        val cancel = WireMessage.Beacon.cancel(now, "abcd1234")
        assertEquals("SH1*B*0,0*$now*abcd1234", codec.encode(cancel))
        assertTrue((ok(codec.encode(cancel)) as WireMessage.Beacon).isCancel)
        assertFalse((ok("SH1*B*12.620,80.190*$now*abcd1234") as WireMessage.Beacon).isCancel)
    }

    @Test fun `every hazard type code round trips`() {
        HazardType.entries.forEach { type ->
            val wire = codec.encode(WireMessage.Report(type, 12.0, 80.0, 1, now, "u1"))
            assertEquals(type, (ok(wire) as WireMessage.Report).type)
        }
    }

    @Test fun `uid8 takes eight characters or falls back to anon0000`() {
        assertEquals("AbCdEfGh", WireCodec.uid8("AbCdEfGhIjKlMnOp"))
        assertEquals("anon0000", WireCodec.uid8(null))
        assertEquals("anon0000", WireCodec.uid8(""))
        assertEquals("anon0000", WireCodec.uid8("***"))
        assertEquals("ab", WireCodec.uid8("a*b"))
    }

    // ------------------------------------------------------------------ rejection rules

    @Test fun `rejects wrong prefix, unknown type and wrong field counts`() {
        val alert = TestVectors.firstValid("A")
        assertEquals(WireError.WRONG_PREFIX, rejectedWith(alert.replaceFirst("SH1", "SH2")))
        assertEquals(WireError.WRONG_PREFIX, rejectedWith(""))
        assertEquals(WireError.UNKNOWN_TYPE, rejectedWith(alert.replaceFirst("*A*", "*Z*")))
        assertEquals(WireError.WRONG_FIELD_COUNT, rejectedWith("SH1"))
        assertEquals(WireError.WRONG_FIELD_COUNT, rejectedWith(alert.replace("*S*", "*")))
        assertEquals(WireError.WRONG_FIELD_COUNT, rejectedWith("$alert*extra"))
        assertEquals(WireError.WRONG_FIELD_COUNT, rejectedWith("SH1*P*12.621,80.194*$now"))
    }

    @Test fun `rejects wires longer than 160 characters`() {
        val long = TestVectors.cases.first { it.note.contains("180") }.wire
        assertTrue(long.length > 160)
        assertEquals(WireError.TOO_LONG, rejectedWith(long))
        assertTrue(codec.parse(TestVectors.firstValid("A").padEnd(160, 'x')) is WireParseResult.Rejected)
    }

    @Test fun `rejects non-ASCII and control characters`() {
        assertEquals(WireError.NOT_ASCII, rejectedWith("SH1*P*12.621,80.194*$now*anon000é"))
        assertEquals(WireError.NOT_ASCII, rejectedWith("SH1*P*12.621,80.194*$now*anon0000\n"))
    }

    @Test fun `rejects bad field values`() {
        fun bad(wire: String) = assertEquals(wire, WireError.BAD_FIELD, rejectedWith(wire))
        bad("SH1*P*91.000,80.000*$now*anon0000")           // latitude out of range
        bad("SH1*P*12.000,181.000*$now*anon0000")          // longitude out of range
        bad("SH1*P*NaN,80.000*$now*anon0000")
        bad("SH1*P*1e1,80.000*$now*anon0000")
        bad("SH1*P*12.000;80.000*$now*anon0000")
        bad("SH1*P*12.000,80.000*soon*anon0000")
        bad("SH1*P*12.000,80.000*-5*anon0000")
        bad("SH1*P*12.000,80.000*$now*waytoolongid")
        bad("SH1*R*XX*12.00000,80.00000*1*$now*anon0000")   // unknown hazard code
        bad("SH1*R*FL*12.00000,80.00000*4*$now*anon0000")   // severity above 3
        bad("SH1*B*12.000*$now*anon0000")
    }

    @Test fun `alert fields are checked`() {
        val sig = "A".repeat(86)
        fun alert(sev: String = "3", flags: String = "R", radius: String = "2000", code: String = "FLD_EVAC") =
            "SH1*A*a1b2c3*$code*$sev*12.62080,80.19450*$radius*$flags*$now*$sig"
        assertTrue(codec.parse(alert()) is WireParseResult.Ok)
        assertEquals(WireError.BAD_FIELD, rejectedWith(alert(sev = "9")))
        assertEquals(WireError.BAD_FIELD, rejectedWith(alert(flags = "X")))
        assertEquals(WireError.BAD_FIELD, rejectedWith(alert(radius = "-1")))
        assertEquals(WireError.BAD_FIELD, rejectedWith(alert(code = "fld evac")))
    }

    @Test fun `rejects malformed signatures`() {
        val alert = TestVectors.firstValid("A")
        assertEquals(WireError.BAD_SIGNATURE_FORMAT, rejectedWith(alert + "XXXX"))
        assertEquals(WireError.BAD_SIGNATURE_FORMAT, rejectedWith(alert.dropLast(1)))
        assertEquals(WireError.BAD_SIGNATURE_FORMAT, rejectedWith(alert.dropLast(1) + "+"))
    }

    // ------------------------------------------------------------------ time window

    @Test fun `accepts up to 48 hours in the past and 10 minutes ahead`() {
        fun presence(ts: Long) = "SH1*P*12.621,80.194*$ts*anon0000"
        assertTrue(codec.parse(presence(now - 48 * 3600)) is WireParseResult.Ok)
        assertEquals(WireError.STALE, rejectedWith(presence(now - 48 * 3600 - 1)))
        assertTrue(codec.parse(presence(now + 600)) is WireParseResult.Ok)
        assertEquals(WireError.FROM_FUTURE, rejectedWith(presence(now + 601)))
    }

    @Test fun `time is read from the injected clock`() {
        val later = WireCodec(java.time.Clock.offset(TestVectors.clock, java.time.Duration.ofHours(49)))
        assertEquals(WireError.STALE, (later.parse(TestVectors.firstValid("A")) as WireParseResult.Rejected).error)
    }

    // ------------------------------------------------------------------ encode guards

    @Test fun `encode refuses wires that would be invalid`() {
        try {
            codec.encode(WireMessage.Presence(12.0, 80.0, now, "has*star"))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            codec.encode(WireMessage.ShelterStatusUpdate("x".repeat(40), ShelterStatus.OPEN, now, "A".repeat(86)))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            codec.encode(WireMessage.ShelterStatusUpdate("poi_1", ShelterStatus.UNKNOWN, now, "A".repeat(86)))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ------------------------------------------------------------------ fuzz

    @Test fun `random strings never crash the parser`() {
        val random = Random(20_261_010L)
        val alphabet = "SH1*ABGPRSOFC0123456789.,-_ éக\n+/="
        repeat(30_000) {
            val text = buildString { repeat(random.nextInt(200)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            codec.parse(text)
            codec.parse("SH1*" + text)
        }
    }

    @Test fun `mutated real wires never crash the parser`() {
        val random = Random(7)
        val seeds = TestVectors.cases.map { it.wire }
        repeat(30_000) {
            val chars = seeds[random.nextInt(seeds.size)].toCharArray()
            repeat(1 + random.nextInt(3)) { chars[random.nextInt(chars.size)] = (32 + random.nextInt(95)).toChar() }
            val cut = random.nextInt(chars.size + 1)
            codec.parse(String(chars, 0, cut))
            codec.parse(String(chars))
        }
    }
}
