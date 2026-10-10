package com.sahay.app

import com.sahay.app.common.ForecastNote
import com.sahay.app.common.forecastNote
import com.sahay.app.common.formatForecastNumber
import com.sahay.core.contracts.ForecastDay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ForecastNoteTest {
    private val today = LocalDate.of(2026, 10, 10)
    private val day = ForecastDay(today, 0.0, 22.0, 32.0, "LOW")

    @Test fun `entries are shown`() = assertEquals(ForecastNote.SHOWN, forecastNote(listOf(day), today, today))

    @Test fun `empty forecast within 16 days says past patterns`() {
        assertEquals(ForecastNote.UNAVAILABLE_PAST_PATTERNS, forecastNote(emptyList(), today.plusDays(16), today))
        assertEquals(ForecastNote.UNAVAILABLE_PAST_PATTERNS, forecastNote(emptyList(), today, today))
    }

    @Test fun `trip starting after 16 days is out of range`() =
        assertEquals(ForecastNote.BEYOND_RANGE, forecastNote(emptyList(), today.plusDays(17), today))

    @Test fun `whole numbers have no decimals`() {
        java.util.Locale.setDefault(java.util.Locale.US)
        assertEquals("0", formatForecastNumber(0.0))
        assertEquals("12.4", formatForecastNumber(12.4))
    }
}
