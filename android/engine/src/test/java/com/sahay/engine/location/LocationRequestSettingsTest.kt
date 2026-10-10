package com.sahay.engine.location

import com.google.android.gms.location.Priority
import com.sahay.core.contracts.LocationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationRequestSettingsTest {
    @Test
    fun lowPowerIsBalancedEveryMinute() {
        val s = LocationRequestSettings.forMode(LocationMode.LOW_POWER)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, s.priority)
        assertEquals(60_000L, s.intervalMs)
    }

    @Test
    fun balancedIsBalancedEvery15Seconds() {
        val s = LocationRequestSettings.forMode(LocationMode.BALANCED)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, s.priority)
        assertEquals(15_000L, s.intervalMs)
    }

    @Test
    fun navigationIsHighAccuracyEvery3Seconds() {
        val s = LocationRequestSettings.forMode(LocationMode.NAVIGATION)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, s.priority)
        assertEquals(3_000L, s.intervalMs)
    }
}
