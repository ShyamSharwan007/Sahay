package com.sahay.app.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sahay.R
import com.sahay.core.contracts.ForecastDay
import com.sahay.designsystem.components.StatusKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The forecast covers this many days ahead (CONTRACTS §5.1). */
const val FORECAST_HORIZON_DAYS = 16L

/** What to tell the user about the forecast of a trip. */
enum class ForecastNote {
    /** Days are listed. */
    SHOWN,

    /** No forecast although the trip is within reach: say we show past patterns. */
    UNAVAILABLE_PAST_PATTERNS,

    /** The trip starts too far ahead for any forecast. */
    BEYOND_RANGE,
}

fun forecastNote(forecast: List<ForecastDay>, tripStart: LocalDate, today: LocalDate): ForecastNote = when {
    forecast.isNotEmpty() -> ForecastNote.SHOWN
    tripStart.isAfter(today.plusDays(FORECAST_HORIZON_DAYS)) -> ForecastNote.BEYOND_RANGE
    else -> ForecastNote.UNAVAILABLE_PAST_PATTERNS
}

/** Whole numbers stay whole ("0 mm"), others get one decimal. */
fun formatForecastNumber(value: Double): String =
    if (value % 1.0 == 0.0) String.format(Locale.getDefault(), "%.0f", value) else String.format(Locale.getDefault(), "%.1f", value)

/** Risk word and color: Low = safe, Medium = watch, High = warning, Severe = danger (DESIGN §2). */
fun riskPresentation(riskLevel: String): Pair<StatusKind, Int> = when (riskLevel.uppercase()) {
    "LOW" -> StatusKind.Safe to R.string.risk_low
    "MODERATE", "MEDIUM" -> StatusKind.Watch to R.string.risk_moderate
    "HIGH" -> StatusKind.Warning to R.string.risk_high
    "SEVERE" -> StatusKind.Danger to R.string.risk_severe
    else -> StatusKind.Info to R.string.risk_unknown
}

/** "Sat 11 Oct · 0 mm rain · 32°C · wind 22 km/h" */
@Composable
fun forecastLine(day: ForecastDay, dayFormat: DateTimeFormatter): String = stringResource(
    R.string.forecast_line,
    day.date.format(dayFormat),
    formatForecastNumber(day.rainMm),
    formatForecastNumber(day.maxTempC),
    formatForecastNumber(day.windKmh),
)

/** Source line under any forecast. */
@Composable
fun ForecastCaption(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.forecast_source),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
