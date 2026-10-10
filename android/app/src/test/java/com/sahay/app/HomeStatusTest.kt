package com.sahay.app

import com.sahay.app.home.HomeStatus
import com.sahay.app.home.computeHomeStatus
import com.sahay.app.home.todaysForecast
import com.sahay.core.contracts.AlertSource
import com.sahay.core.contracts.ForecastDay
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone
import com.sahay.core.contracts.SahayAlert
import com.sahay.core.contracts.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HomeStatusTest {

    private val mahabalipuram = GeoPoint(12.6208, 80.1945)
    private val farAway = GeoPoint(13.0827, 80.2707)   // Chennai, ~50 km north

    private fun alert(
        id: String = "a1",
        severity: Int = 3,
        read: Boolean = false,
        area: GeoPoint? = mahabalipuram,
        radiusM: Int? = 2000,
        simulation: Boolean = false,
    ) = SahayAlert(
        id = id, templateCode = "FLD_EVAC", severity = severity, area = area, radiusM = radiusM,
        issuedAtEpochSec = 0, isSimulation = simulation, title = "t", body = "b", titleEn = "t", bodyEn = "b",
        originalText = null, source = AlertSource.INTERNET, verification = Verification.VERIFIED_OFFICIAL,
        receivedAtEpochSec = 0, read = read,
    )

    private fun zone(level: RiskLevel) = RiskZone("z1", "Low town", level, emptyList())

    @Test fun `no alerts and no zone is safe`() {
        assertEquals(HomeStatus.Safe, computeHomeStatus(emptyList(), mahabalipuram, null))
    }

    @Test fun `unread severity 3 alert covering the user is danger`() {
        val a = alert()
        assertEquals(HomeStatus.Danger(a), computeHomeStatus(listOf(a), mahabalipuram, null))
    }

    @Test fun `severity 3 alert without an area is danger anywhere`() {
        val a = alert(area = null, radiusM = null)
        assertEquals(HomeStatus.Danger(a), computeHomeStatus(listOf(a), farAway, null))
    }

    @Test fun `severity 3 alert elsewhere does not make the user danger`() {
        val status = computeHomeStatus(listOf(alert()), farAway, null)
        assertEquals(HomeStatus.Safe, status)
    }

    @Test fun `severity 3 alert with unknown location still counts`() {
        val a = alert()
        assertEquals(HomeStatus.Danger(a), computeHomeStatus(listOf(a), null, null))
    }

    @Test fun `read severity 3 alert is not danger`() {
        assertEquals(HomeStatus.Safe, computeHomeStatus(listOf(alert(read = true)), mahabalipuram, null))
    }

    @Test fun `just inside the alert radius is covered, just outside is not`() {
        // 0.018 degrees of latitude is about 2 km.
        val inside = GeoPoint(mahabalipuram.lat + 0.017, mahabalipuram.lon)
        val outside = GeoPoint(mahabalipuram.lat + 0.019, mahabalipuram.lon)
        assertTrue(computeHomeStatus(listOf(alert()), inside, null) is HomeStatus.Danger)
        assertEquals(HomeStatus.Safe, computeHomeStatus(listOf(alert()), outside, null))
    }

    @Test fun `high risk zone is a warning for the flood zone`() {
        assertEquals(HomeStatus.Warning(inFloodZone = true, alert = null), computeHomeStatus(emptyList(), mahabalipuram, zone(RiskLevel.HIGH)))
    }

    @Test fun `medium risk zone alone stays safe`() {
        assertEquals(HomeStatus.Safe, computeHomeStatus(emptyList(), mahabalipuram, zone(RiskLevel.MEDIUM)))
    }

    @Test fun `severity 2 alert is a warning`() {
        val a = alert(severity = 2)
        assertEquals(HomeStatus.Warning(inFloodZone = false, alert = a), computeHomeStatus(listOf(a), mahabalipuram, null))
    }

    @Test fun `zone and severity 2 alert are reported together`() {
        val a = alert(severity = 2)
        assertEquals(HomeStatus.Warning(true, a), computeHomeStatus(listOf(a), mahabalipuram, zone(RiskLevel.HIGH)))
    }

    @Test fun `danger wins over zone warning`() {
        val a = alert()
        assertEquals(HomeStatus.Danger(a), computeHomeStatus(listOf(alert(severity = 2, id = "w"), a), mahabalipuram, zone(RiskLevel.HIGH)))
    }

    @Test fun `severity 1 and 0 alerts do not change status`() {
        val alerts = listOf(alert(severity = 1), alert(severity = 0, id = "b"))
        assertEquals(HomeStatus.Safe, computeHomeStatus(alerts, mahabalipuram, null))
    }

    @Test fun `todays forecast is picked by date and is null without a pack`() {
        val today = LocalDate.of(2026, 10, 10)
        val day = ForecastDay(today, 12.4, 22.0, 31.0, "LOW")
        val pack = PackInfo(
            "m", "Mahabalipuram", "v1", emptyList(), today, today, 0,
            listOf(ForecastDay(today.plusDays(1), 1.0, 1.0, 1.0, "LOW"), day), emptyMap(), emptyList(), emptyList(), "k", 0,
        )
        assertEquals(day, todaysForecast(pack, today))
        assertNull(todaysForecast(pack, today.plusDays(5)))
        assertNull(todaysForecast(null, today))
    }
}
