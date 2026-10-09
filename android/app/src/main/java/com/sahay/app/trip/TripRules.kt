package com.sahay.app.trip

import com.sahay.core.contracts.PackDownloadState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

const val MAX_TRIP_DAYS = 30
const val FORECAST_HORIZON_DAYS = 16

enum class TripDateError { MISSING, IN_THE_PAST, END_BEFORE_START, TOO_LONG }

/** Start must be today or later; the trip may span at most [MAX_TRIP_DAYS] days, counting both ends. */
fun validateTripDates(start: LocalDate?, end: LocalDate?, today: LocalDate): TripDateError? = when {
    start == null || end == null -> TripDateError.MISSING
    start.isBefore(today) -> TripDateError.IN_THE_PAST
    end.isBefore(start) -> TripDateError.END_BEFORE_START
    ChronoUnit.DAYS.between(start, end) + 1 > MAX_TRIP_DAYS -> TripDateError.TOO_LONG
    else -> null
}

/** Material date pickers speak UTC midnight millis. */
fun utcMillisToDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

fun dateToUtcMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Download steps in the order the engine reports them (PackDownloadState.Downloading.step). */
val DOWNLOAD_STEP_KEYS = listOf("MANIFEST", "DATA", "MAP", "ASSETS", "VERIFY")

enum class StepStatus { DONE, ACTIVE, PENDING }

/** Status of each of [DOWNLOAD_STEP_KEYS] for the given state. Unknown step names leave everything pending. */
fun downloadStepStatuses(state: PackDownloadState): List<StepStatus> = when (state) {
    is PackDownloadState.Done -> DOWNLOAD_STEP_KEYS.map { StepStatus.DONE }
    is PackDownloadState.Downloading -> {
        val active = DOWNLOAD_STEP_KEYS.indexOf(state.step)
        DOWNLOAD_STEP_KEYS.indices.map { i ->
            when {
                active < 0 -> StepStatus.PENDING
                i < active -> StepStatus.DONE
                i == active -> StepStatus.ACTIVE
                else -> StepStatus.PENDING
            }
        }
    }
    else -> DOWNLOAD_STEP_KEYS.map { StepStatus.PENDING }
}

/** Overall percent 0..100: finished steps plus the fraction of the current one. */
fun downloadPercent(state: PackDownloadState): Int = when (state) {
    is PackDownloadState.Done -> 100
    is PackDownloadState.Downloading -> {
        val index = DOWNLOAD_STEP_KEYS.indexOf(state.step).coerceAtLeast(0)
        val fraction = state.progress.coerceIn(0f, 1f)
        (((index + fraction) / DOWNLOAD_STEP_KEYS.size) * 100).toInt().coerceIn(0, 100)
    }
    else -> 0
}
