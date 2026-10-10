package com.sahay.comms.reports

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.HazardType
import com.sahay.core.contracts.SahayAlert
import com.sahay.core.contracts.TrustLabel
import com.sahay.core.contracts.Verification
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance in metres. */
internal fun distanceM(a: GeoPoint, b: GeoPoint): Double {
    val earthRadiusM = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * earthRadiusM * atan2(sqrt(h), sqrt(1 - h))
}

/** A report whose trust is being estimated on the phone. */
data class TrustInput(
    val type: HazardType,
    val point: GeoPoint,
    /** Where the reporter stood when they made the report; null when there was no fix. */
    val reporter: GeoPoint?,
    val createdAtEpochSec: Long,
)

/**
 * The phone's own estimate of a report's trust while the server has not scored it yet (docs/CONTRACTS.md §6):
 * `trust = (0.25·P + 0.30·C + 0.20·O + 0.10·E + 0.15·H) × D`.
 *
 * - P proximity: 1 at the reported spot, 0 from 1 km away or without a fix.
 * - C corroboration: other people's reports of the same type within 300 m and 30 min, 3 or more = 1.
 * - O official match: inside the area of an active official alert that fits the hazard.
 * - E evidence: always 0 here, a photo is not checked on the phone.
 * - H history: always 0.5, the phone does not know the reporter's track record.
 * - D recency: halves roughly every 42 minutes (`exp(-minutes / 60)`).
 */
object ProvisionalTrust {
    const val EVIDENCE = 0.0
    const val HISTORY = 0.5
    private const val PROXIMITY_RANGE_M = 1_000.0
    private const val CORROBORATION_RADIUS_M = 300.0
    private const val CORROBORATION_WINDOW_SEC = 30 * 60L
    private const val CORROBORATION_FULL = 3
    private const val RECENCY_TIME_CONSTANT_MIN = 60.0
    /** An alert counts as active for this long after it was issued (the contract does not say; half a day). */
    const val ALERT_ACTIVE_SEC = 12 * 3600L
    const val VERIFIED_AT = 0.70
    const val LIKELY_AT = 0.40

    fun score(input: TrustInput, others: List<HazardReport>, alerts: List<SahayAlert>, nowSec: Long): Double {
        val proximity = input.reporter?.let { max(0.0, 1.0 - distanceM(it, input.point) / PROXIMITY_RANGE_M) } ?: 0.0
        val corroboration = min(1.0, corroborating(input, others).toDouble() / CORROBORATION_FULL)
        val official = if (insideActiveOfficialAlert(input, alerts, nowSec)) 1.0 else 0.0
        val ageMin = max(0L, nowSec - input.createdAtEpochSec) / 60.0
        val recency = exp(-ageMin / RECENCY_TIME_CONSTANT_MIN)
        val base = 0.25 * proximity + 0.30 * corroboration + 0.20 * official + 0.10 * EVIDENCE + 0.15 * HISTORY
        val trust = base * recency
        return if (trust.isFinite()) trust.coerceIn(0.0, 1.0) else 0.0      // a NaN coordinate must not give a NaN score
    }

    fun label(score: Double): TrustLabel = when {
        score >= VERIFIED_AT -> TrustLabel.VERIFIED
        score >= LIKELY_AT -> TrustLabel.LIKELY
        else -> TrustLabel.UNCONFIRMED
    }

    /** Reports by other people (every non-own report is a different reporter). */
    private fun corroborating(input: TrustInput, others: List<HazardReport>): Int = others.count {
        !it.mine && it.type == input.type &&
            abs(it.createdAtEpochSec - input.createdAtEpochSec) <= CORROBORATION_WINDOW_SEC &&
            distanceM(it.point, input.point) <= CORROBORATION_RADIUS_M
    }

    private fun insideActiveOfficialAlert(input: TrustInput, alerts: List<SahayAlert>, nowSec: Long): Boolean =
        alerts.any { alert ->
            val area = alert.area
            val radius = alert.radiusM
            alert.verification == Verification.VERIFIED_OFFICIAL &&
                alert.severity >= 1 &&                               // 0 = info, test, all clear
                area != null && radius != null &&
                nowSec - alert.issuedAtEpochSec in 0..ALERT_ACTIVE_SEC &&
                alert.templateCode?.let { relatedTemplate(input.type, it) } == true &&
                distanceM(area, input.point) <= radius
        }

    /** Which alert templates make a report of this type more believable (docs/CONTRACTS.md §6: flood goes with the FLD, RAIN and COAST templates, "etc."). */
    private fun relatedTemplate(type: HazardType, code: String): Boolean = when (type) {
        HazardType.FLOOD -> code.startsWithAny("FLD_", "RAIN_", "COAST_", "CYC_") || code == "SURGE"
        HazardType.ROAD_BLOCKED -> code == "ROAD_CLOSED" || code.startsWithAny("FLD_", "RAIN_", "CYC_")
        HazardType.SHELTER_FULL, HazardType.SHELTER_OPEN ->
            code == "SHELTER_OPEN" || code.endsWith("_EVAC") || code == "CYC_LANDFALL"
        HazardType.POWER_LINE -> code == "POWER_OUT" || code == "WIND_HIGH" || code == "LIGHTNING" || code.startsWith("CYC_")
        HazardType.LANDSLIDE -> code.startsWithAny("RAIN_", "FLD_", "CYC_")
        HazardType.OTHER -> false
    }

    private fun String.startsWithAny(vararg prefixes: String) = prefixes.any { startsWith(it) }
}
