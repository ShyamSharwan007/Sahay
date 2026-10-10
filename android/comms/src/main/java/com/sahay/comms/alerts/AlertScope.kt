package com.sahay.comms.alerts

import com.sahay.core.contracts.AlertSource
import com.sahay.core.contracts.PackInfo
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Which alerts the user may see: those for the region of the installed trip pack that overlap the trip dates
 * `[today .. end of trip end date]` and have not expired. Without a pack there is no scope, so no regional alerts.
 */
data class AlertScope(val regionId: String, val windowStartSec: Long, val windowEndSec: Long) {

    companion object {
        /** An alert that comes without an expiry is treated as expiring this long after it was issued. */
        const val DEFAULT_LIFETIME_SEC = 6 * 3600L

        private const val METRES_PER_DEGREE = 111_320.0

        /** Null when no pack is installed, or its trip is already over (nothing regional is left to show). */
        fun forPack(pack: PackInfo?, clock: Clock): AlertScope? {
            pack ?: return null
            val today = LocalDate.now(clock)
            if (pack.tripEnd.isBefore(today)) return null
            return AlertScope(
                regionId = pack.regionId,
                windowStartSec = today.startOfDaySec(clock.zone),
                windowEndSec = pack.tripEnd.plusDays(1).startOfDaySec(clock.zone),
            )
        }

        /**
         * Whether [alert] may be shown at [nowSec] under [scope] (null = no pack).
         *  - Pasted text is something the user asked for: always shown.
         *  - Official SMS arrives on this phone's own network and names no region: shown until it expires.
         *  - Everything else needs the pack's region and a lifetime that overlaps the trip window.
         */
        fun shows(scope: AlertScope?, alert: ScopedAlert, nowSec: Long): Boolean {
            if (alert.source == AlertSource.PASTED.name) return true
            val expiresAt = alert.expiresAtSec(DEFAULT_LIFETIME_SEC)
            if (expiresAt < nowSec) return false
            if (alert.source == AlertSource.SMS_OFFICIAL.name) return true
            if (scope == null || alert.regionId != scope.regionId) return false
            return alert.issuedAtSec < scope.windowEndSec && expiresAt >= scope.windowStartSec
        }

        /**
         * A signed SMS or mesh alert names no region, only a circle. It belongs to the pack's region if the circle
         * touches the pack's bounding box (`[minLon, minLat, maxLon, maxLat]`).
         */
        fun circleTouchesBbox(lat: Double, lon: Double, radiusM: Int, bbox: List<Double>): Boolean {
            if (bbox.size != 4) return false
            val nearestLon = lon.coerceIn(bbox[0], bbox[2])
            val nearestLat = lat.coerceIn(bbox[1], bbox[3])
            val dxM = (lon - nearestLon) * METRES_PER_DEGREE * cos(Math.toRadians(lat))
            val dyM = (lat - nearestLat) * METRES_PER_DEGREE
            return hypot(dxM, dyM) <= radiusM
        }

        private fun LocalDate.startOfDaySec(zone: ZoneId) = atStartOfDay(zone).toEpochSecond()
    }
}

/** The fields of a stored alert that scoping looks at (lets the filter be tested without Room). */
data class ScopedAlert(
    val source: String,
    val regionId: String?,
    val issuedAtSec: Long,
    val expiresAtSec: Long?,
) {
    fun expiresAtSec(defaultLifetimeSec: Long): Long = expiresAtSec ?: (issuedAtSec + defaultLifetimeSec)
}

internal fun AlertEntity.scoped() = ScopedAlert(source, regionId, issuedAtEpochSec, expiresAtEpochSec)
