package com.sahay.app.home

import com.sahay.app.common.distanceMeters
import com.sahay.core.contracts.ForecastDay
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone
import com.sahay.core.contracts.SahayAlert
import java.time.LocalDate

/** What the top card on Home says. Answers "Am I safe?" (DESIGN §1). */
sealed interface HomeStatus {
    /** An unread emergency (severity 3) alert that applies to the user. */
    data class Danger(val alert: SahayAlert) : HomeStatus

    /** In a HIGH flood zone and/or a severity-2 alert exists. [alert] is null when only the zone applies. */
    data class Warning(val inFloodZone: Boolean, val alert: SahayAlert?) : HomeStatus

    data object Safe : HomeStatus
}

/**
 * Priority: Danger > Warning > Safe.
 * - Danger: unread severity-3 alert whose circle contains [location], or that has no area.
 *   Without a location fix we cannot rule an area alert out, so it still counts (fail safe).
 * - Warning: [zone] is HIGH, or any severity-2 alert is present.
 */
fun computeHomeStatus(alerts: List<SahayAlert>, location: GeoPoint?, zone: RiskZone?): HomeStatus {
    val danger = alerts.firstOrNull { !it.read && it.severity >= 3 && it.appliesTo(location) }
    if (danger != null) return HomeStatus.Danger(danger)

    val inFloodZone = zone?.level == RiskLevel.HIGH
    val warningAlert = alerts.firstOrNull { it.severity == 2 }
    if (inFloodZone || warningAlert != null) return HomeStatus.Warning(inFloodZone, warningAlert)

    return HomeStatus.Safe
}

private fun SahayAlert.appliesTo(location: GeoPoint?): Boolean {
    val center = area ?: return true
    val radius = radiusM ?: return true          // area without a radius: treat as everywhere
    location ?: return true
    return distanceMeters(center, location) <= radius
}

/** Today's forecast line from the pack, or null when the pack has none for [today]. */
fun todaysForecast(pack: PackInfo?, today: LocalDate): ForecastDay? =
    pack?.forecast?.firstOrNull { it.date == today }

/** The next [count] forecast days after [today], in date order. */
fun upcomingForecast(pack: PackInfo?, today: LocalDate, count: Int = 4): List<ForecastDay> =
    pack?.forecast.orEmpty().filter { it.date.isAfter(today) }.sortedBy { it.date }.take(count)
