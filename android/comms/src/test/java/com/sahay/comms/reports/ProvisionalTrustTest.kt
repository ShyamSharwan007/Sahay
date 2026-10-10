package com.sahay.comms.reports

import com.sahay.core.contracts.AlertSource
import com.sahay.core.contracts.Channel
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.HazardType
import com.sahay.core.contracts.SahayAlert
import com.sahay.core.contracts.TrustLabel
import com.sahay.core.contracts.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.E

/** docs/CONTRACTS.md §6: trust = (0.25·P + 0.30·C + 0.20·O + 0.10·E + 0.15·H) × D, with E = 0 and H = 0.5 on the phone. */
class ProvisionalTrustTest {

    private val now = 1_760_000_000L
    private val spot = GeoPoint(12.6208, 80.1945)

    /** About [metres] north of [spot]. */
    private fun north(metres: Double) = GeoPoint(spot.lat + metres / 111_195.0, spot.lon)

    private fun input(
        reporter: GeoPoint? = spot,
        createdAt: Long = now,
        type: HazardType = HazardType.FLOOD,
    ) = TrustInput(type, spot, reporter, createdAt)

    private fun other(
        type: HazardType = HazardType.FLOOD,
        at: GeoPoint = spot,
        createdAt: Long = now,
        mine: Boolean = false,
    ) = HazardReport(
        "r", type, at, null, null, createdAt, 0.5, TrustLabel.LIKELY, mine, Channel.INTERNET, pendingSync = false,
    )

    private fun alert(
        code: String? = "FLD_EVAC",
        severity: Int = 3,
        at: GeoPoint? = spot,
        radiusM: Int? = 2_000,
        issuedAt: Long = now - 600,
        verification: Verification = Verification.VERIFIED_OFFICIAL,
        simulation: Boolean = false,
    ) = SahayAlert(
        "a1", code, severity, at, radiusM, issuedAt, simulation, "t", "b", "t", "b", null,
        AlertSource.INTERNET, verification, issuedAt, false,
    )

    private fun score(input: TrustInput, others: List<HazardReport> = emptyList(), alerts: List<SahayAlert> = emptyList()) =
        ProvisionalTrust.score(input, others, alerts, now)

    // ------------------------------------------------------------------ the formula

    @Test fun `standing at the spot with nothing else known is 0_25 plus 0_075`() {
        // P = 1, C = 0, O = 0, E = 0, H = 0.5, D = 1
        assertEquals(0.325, score(input()), 1e-9)
        assertEquals(TrustLabel.UNCONFIRMED, ProvisionalTrust.label(0.325))
    }

    @Test fun `three other reporters add the full corroboration weight`() {
        val others = List(3) { other() }
        assertEquals(0.325 + 0.30, score(input(), others), 1e-9)
        assertEquals(TrustLabel.LIKELY, ProvisionalTrust.label(score(input(), others)))
    }

    @Test fun `corroboration grows by a third per reporter and stops at three`() {
        assertEquals(0.325 + 0.10, score(input(), List(1) { other() }), 1e-9)
        assertEquals(0.325 + 0.20, score(input(), List(2) { other() }), 1e-9)
        assertEquals(0.325 + 0.30, score(input(), List(7) { other() }), 1e-9)
    }

    @Test fun `an active official alert of a matching kind adds 0_20 and makes it verified with corroboration`() {
        val alerts = listOf(alert())
        assertEquals(0.325 + 0.20, score(input(), alerts = alerts), 1e-9)
        val full = score(input(), List(3) { other() }, alerts)
        assertEquals(0.825, full, 1e-9)
        assertEquals(TrustLabel.VERIFIED, ProvisionalTrust.label(full))
    }

    @Test fun `proximity falls linearly to zero at one kilometre`() {
        assertEquals(0.25 * 0.5 + 0.075, score(input(reporter = north(500.0))), 1e-3)
        assertEquals(0.075, score(input(reporter = north(1_000.0))), 1e-3)
        assertEquals(0.075, score(input(reporter = north(5_000.0))), 1e-9)
    }

    @Test fun `without a fix proximity is zero`() {
        assertEquals(0.075, score(input(reporter = null)), 1e-9)
    }

    @Test fun `recency halves the score about every 42 minutes`() {
        val hourOld = input(createdAt = now - 3_600)
        assertEquals(0.325 / E, score(hourOld), 1e-9)                      // D = exp(-60 / 60)
        assertEquals(0.325 * Math.exp(-0.5), score(input(createdAt = now - 1_800)), 1e-9)
    }

    @Test fun `a report from the future is treated as brand new`() {
        assertEquals(0.325, score(input(createdAt = now + 300)), 1e-9)
    }

    @Test fun `the score never leaves the range 0 to 1`() {
        val best = score(input(), List(10) { other() }, listOf(alert()))
        assertTrue(best in 0.0..1.0)
        assertTrue(score(input(createdAt = now - 100 * 3_600)) in 0.0..1.0)
    }

    // ------------------------------------------------------------------ what counts as corroboration

    @Test fun `only other people's reports of the same type nearby and at the same time count`() {
        val ignored = listOf(
            other(mine = true),                                        // my own earlier report
            other(type = HazardType.ROAD_BLOCKED),                     // different hazard
            other(at = north(400.0)),                                  // too far (> 300 m)
            other(createdAt = now - 31 * 60),                          // too long ago (> 30 min)
            other(createdAt = now + 31 * 60),
        )
        assertEquals(0.325, score(input(), ignored), 1e-9)
        val counted = listOf(other(at = north(250.0)), other(createdAt = now - 29 * 60), other(createdAt = now + 29 * 60))
        assertEquals(0.325 + 0.30, score(input(), counted), 1e-9)
    }

    // ------------------------------------------------------------------ what counts as an official alert

    @Test fun `simulation alerts count (the demo relies on them)`() {
        assertEquals(0.525, score(input(), alerts = listOf(alert(simulation = true))), 1e-9)
    }

    @Test fun `alerts that do not count`() {
        val notCounted = listOf(
            alert(verification = Verification.MATCHED_OFFICIAL_SMS),   // keyword match, not signed
            alert(verification = Verification.UNVERIFIED),
            alert(code = "ALL_CLEAR", severity = 0),                   // information only
            alert(code = "TEST", severity = 0),
            alert(code = "BOIL_WATER", severity = 1),                  // unrelated to flooding
            alert(code = null),
            alert(at = null),
            alert(radiusM = null),
            alert(at = north(3_000.0)),                                // report is outside the 2 km circle
            alert(issuedAt = now - 13 * 3_600),                        // no longer active
            alert(issuedAt = now + 3_600),
        )
        notCounted.forEachIndexed { i, a -> assertEquals("alert #$i", 0.325, score(input(), alerts = listOf(a)), 1e-9) }
    }

    @Test fun `related templates by hazard type`() {
        fun official(type: HazardType, code: String) =
            score(input(type = type), alerts = listOf(alert(code = code))) > 0.4

        assertTrue(official(HazardType.FLOOD, "FLD_WARN") && official(HazardType.FLOOD, "RAIN_HVY") &&
            official(HazardType.FLOOD, "COAST_EVAC") && official(HazardType.FLOOD, "SURGE") && official(HazardType.FLOOD, "CYC_WARN"))
        assertTrue(official(HazardType.ROAD_BLOCKED, "ROAD_CLOSED") && official(HazardType.ROAD_BLOCKED, "FLD_EVAC"))
        assertTrue(official(HazardType.POWER_LINE, "POWER_OUT") && official(HazardType.POWER_LINE, "WIND_HIGH"))
        assertTrue(official(HazardType.LANDSLIDE, "RAIN_XHVY"))
        assertTrue(official(HazardType.SHELTER_OPEN, "SHELTER_OPEN") && official(HazardType.SHELTER_FULL, "FLD_EVAC"))
        assertTrue(!official(HazardType.POWER_LINE, "BOIL_WATER") && !official(HazardType.FLOOD, "LIGHTNING"))
        assertTrue(!official(HazardType.OTHER, "FLD_EVAC"))
    }

    // ------------------------------------------------------------------ labels

    @Test fun `label thresholds are 0_40 and 0_70`() {
        assertEquals(TrustLabel.UNCONFIRMED, ProvisionalTrust.label(0.3999))
        assertEquals(TrustLabel.LIKELY, ProvisionalTrust.label(0.40))
        assertEquals(TrustLabel.LIKELY, ProvisionalTrust.label(0.6999))
        assertEquals(TrustLabel.VERIFIED, ProvisionalTrust.label(0.70))
    }
}
