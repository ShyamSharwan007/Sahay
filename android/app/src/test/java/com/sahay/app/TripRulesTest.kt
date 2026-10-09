package com.sahay.app

import com.sahay.app.main.Capability
import com.sahay.app.main.capabilityAvailable
import com.sahay.app.main.connectivityLevel
import com.sahay.app.trip.StepStatus
import com.sahay.app.trip.TripDateError
import com.sahay.app.trip.dateToUtcMillis
import com.sahay.app.trip.downloadPercent
import com.sahay.app.trip.downloadStepStatuses
import com.sahay.app.trip.utcMillisToDate
import com.sahay.app.trip.validateTripDates
import com.sahay.core.contracts.ConnectivityState
import com.sahay.core.contracts.PackDownloadState
import com.sahay.designsystem.components.ConnectivityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TripRulesTest {

    private val today = LocalDate.of(2026, 10, 10)

    @Test fun `valid range passes, including a single day and exactly 30 days`() {
        assertNull(validateTripDates(today, today.plusDays(5), today))
        assertNull(validateTripDates(today, today, today))
        assertNull(validateTripDates(today, today.plusDays(29), today))
    }

    @Test fun `31 days is too long`() {
        assertEquals(TripDateError.TOO_LONG, validateTripDates(today, today.plusDays(30), today))
    }

    @Test fun `start before today is rejected`() {
        assertEquals(TripDateError.IN_THE_PAST, validateTripDates(today.minusDays(1), today.plusDays(2), today))
    }

    @Test fun `missing or reversed dates are rejected`() {
        assertEquals(TripDateError.MISSING, validateTripDates(null, null, today))
        assertEquals(TripDateError.MISSING, validateTripDates(today, null, today))
        assertEquals(TripDateError.END_BEFORE_START, validateTripDates(today.plusDays(3), today.plusDays(1), today))
    }

    @Test fun `picker millis round-trip in UTC`() {
        assertEquals(today, utcMillisToDate(dateToUtcMillis(today)))
    }

    @Test fun `step statuses follow the active step`() {
        val s = downloadStepStatuses(PackDownloadState.Downloading("MAP", 0.5f))
        assertEquals(
            listOf(StepStatus.DONE, StepStatus.DONE, StepStatus.ACTIVE, StepStatus.PENDING, StepStatus.PENDING),
            s,
        )
    }

    @Test fun `idle and unknown steps are all pending, done is all done`() {
        assertTrue(downloadStepStatuses(PackDownloadState.Idle).all { it == StepStatus.PENDING })
        assertTrue(downloadStepStatuses(PackDownloadState.Downloading("???", 0.3f)).all { it == StepStatus.PENDING })
    }

    @Test fun `percent combines finished steps and current progress`() {
        assertEquals(0, downloadPercent(PackDownloadState.Idle))
        assertEquals(0, downloadPercent(PackDownloadState.Downloading("MANIFEST", 0f)))
        assertEquals(30, downloadPercent(PackDownloadState.Downloading("DATA", 0.5f)))
        assertEquals(100, downloadPercent(PackDownloadState.Downloading("VERIFY", 5f)))   // progress clamped
    }

    @Test fun `connectivity level prefers internet, then SMS, then offline`() {
        fun level(internet: Boolean, cellular: Boolean) =
            connectivityLevel(ConnectivityState(internet, cellular, meshActive = false, meshPeers = 0))
        assertEquals(ConnectivityLevel.Online, level(true, true))
        assertEquals(ConnectivityLevel.Online, level(true, false))
        assertEquals(ConnectivityLevel.SmsOnly, level(false, true))
        assertEquals(ConnectivityLevel.Offline, level(false, false))
    }

    @Test fun `saved map always works, others follow the connection`() {
        val offline = ConnectivityState(internet = false, cellular = false, meshActive = true, meshPeers = 2)
        assertTrue(capabilityAvailable(Capability.SAVED_MAP, offline))
        assertFalse(capabilityAvailable(Capability.ALERTS_ONLINE, offline))
        assertFalse(capabilityAvailable(Capability.SMS, offline))
        assertTrue(capabilityAvailable(Capability.NEARBY_PHONES, offline))
    }
}
