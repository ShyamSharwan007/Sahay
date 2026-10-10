package com.sahay.comms.alerts

import com.sahay.core.contracts.AlertSource
import com.sahay.core.contracts.PackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AlertScopeTest {

    private val now = Instant.parse("2025-10-09T10:00:00Z").epochSecond
    private val clock: Clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC)
    private val scope = AlertScope.forPack(pack(tripEnd = LocalDate.of(2025, 10, 11)), clock)!!
    private val hour = 3600L

    private fun pack(regionId: String = "mahabalipuram", tripEnd: LocalDate) = PackInfo(
        regionId = regionId, regionName = regionId, packVersion = "t", bbox = listOf(80.16, 12.59, 80.21, 12.65),
        tripStart = LocalDate.of(2025, 10, 1), tripEnd = tripEnd, downloadedAtEpochSec = 0,
        forecast = emptyList(), historySummary = emptyMap(), incidents = emptyList(), precautions = emptyList(),
        publicKeyB64 = "", sizeBytes = 0,
    )

    private fun alert(
        issuedAt: Long = now - hour,
        expiresAt: Long? = null,
        regionId: String? = "mahabalipuram",
        source: AlertSource = AlertSource.INTERNET,
    ) = ScopedAlert(source.name, regionId, issuedAt, expiresAt)

    // ------------------------------------------------------------------ scope from the pack

    @Test fun `no pack means no scope`() = assertNull(AlertScope.forPack(null, clock))

    @Test fun `a finished trip means no scope`() =
        assertNull(AlertScope.forPack(pack(tripEnd = LocalDate.of(2025, 10, 8)), clock))

    @Test fun `the window runs from the start of today to the end of the trip's last day`() {
        assertEquals(Instant.parse("2025-10-09T00:00:00Z").epochSecond, scope.windowStartSec)
        assertEquals(Instant.parse("2025-10-12T00:00:00Z").epochSecond, scope.windowEndSec)
    }

    @Test fun `a trip that ends today still has a scope`() {
        val today = AlertScope.forPack(pack(tripEnd = LocalDate.of(2025, 10, 9)), clock)
        assertEquals(Instant.parse("2025-10-10T00:00:00Z").epochSecond, today!!.windowEndSec)
    }

    // ------------------------------------------------------------------ which alerts show

    @Test fun `an alert for the pack region inside the trip is shown`() =
        assertTrue(AlertScope.shows(scope, alert(), now))

    @Test fun `no pack hides every regional alert`() {
        assertFalse(AlertScope.shows(null, alert(), now))
        assertFalse(AlertScope.shows(null, alert(source = AlertSource.SMS_SERVER), now))
    }

    @Test fun `an alert for another region is hidden`() =
        assertFalse(AlertScope.shows(scope, alert(regionId = "chennai-central"), now))

    @Test fun `an alert with no region is hidden`() =
        assertFalse(AlertScope.shows(scope, alert(regionId = null), now))

    @Test fun `an expired alert is hidden`() =
        assertFalse(AlertScope.shows(scope, alert(expiresAt = now - 1), now))

    @Test fun `an alert is shown up to and including its expiry second`() =
        assertTrue(AlertScope.shows(scope, alert(expiresAt = now), now))

    @Test fun `without an expiry an alert lasts six hours`() {
        assertTrue(AlertScope.shows(scope, alert(issuedAt = now - 6 * hour), now))
        assertFalse(AlertScope.shows(scope, alert(issuedAt = now - 6 * hour - 1), now))
    }

    @Test fun `an explicit expiry beats the six hour default`() =
        assertTrue(AlertScope.shows(scope, alert(issuedAt = now - 10 * hour, expiresAt = now + hour), now))

    @Test fun `an alert issued after the trip's last day is hidden`() {
        val afterTrip = scope.windowEndSec
        assertFalse(AlertScope.shows(scope, alert(issuedAt = afterTrip, expiresAt = afterTrip + hour), now))
    }

    @Test fun `an alert issued on the trip's last day is shown`() {
        val lastSecond = scope.windowEndSec - 1
        assertTrue(AlertScope.shows(scope, alert(issuedAt = lastSecond, expiresAt = lastSecond + hour), now))
    }

    @Test fun `an alert that ended before today is hidden even if it has no expiry`() =
        assertFalse(AlertScope.shows(scope, alert(issuedAt = scope.windowStartSec - 24 * hour), now))

    @Test fun `an alert from before today that is still running is shown`() =
        assertTrue(AlertScope.shows(scope, alert(issuedAt = scope.windowStartSec - hour, expiresAt = now + hour), now))

    @Test fun `official SMS needs no region but does expire`() {
        val sms = alert(regionId = null, source = AlertSource.SMS_OFFICIAL)
        assertTrue(AlertScope.shows(null, sms, now))
        assertFalse(AlertScope.shows(null, sms.copy(issuedAtSec = now - 7 * hour), now))
    }

    @Test fun `pasted text is always shown`() {
        val pasted = alert(regionId = null, issuedAt = now - 30 * 24 * hour, source = AlertSource.PASTED)
        assertTrue(AlertScope.shows(null, pasted, now))
        assertTrue(AlertScope.shows(scope, pasted, now))
    }

    // ------------------------------------------------------------------ SMS alerts name a circle, not a region

    private val bbox = listOf(80.16, 12.59, 80.21, 12.65)

    @Test fun `a circle centred inside the box touches it`() =
        assertTrue(AlertScope.circleTouchesBbox(12.62, 80.19, 100, bbox))

    @Test fun `a circle outside the box touches it only if its radius reaches the edge`() {
        // 0.01 degrees of latitude north of the box is about 1.1 km.
        assertFalse(AlertScope.circleTouchesBbox(12.66, 80.19, 1_000, bbox))
        assertTrue(AlertScope.circleTouchesBbox(12.66, 80.19, 1_200, bbox))
    }

    @Test fun `a circle in another city does not touch the box`() =
        assertFalse(AlertScope.circleTouchesBbox(13.08, 80.27, 20_000, bbox))

    @Test fun `a malformed box never matches`() =
        assertFalse(AlertScope.circleTouchesBbox(12.62, 80.19, 5_000, emptyList()))
}
