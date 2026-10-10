package com.sahay.app.navigate

import com.sahay.core.contracts.GeoPoint
import java.util.Locale

/** Where the Navigate screen should take the user. */
sealed interface NavTarget {
    /** The closest open shelter (falls back to a hospital). */
    data object NearestSafe : NavTarget
    data class ToPoi(val poiId: String) : NavTarget
    data class ToPoint(val point: GeoPoint) : NavTarget
}

private const val POI_PREFIX = "poi:"
private const val POINT_PREFIX = "pt:"

/** Compact string form carried in the navigation route (null = nearest safe place). */
fun NavTarget.encode(): String? = when (this) {
    NavTarget.NearestSafe -> null
    is NavTarget.ToPoi -> POI_PREFIX + poiId
    is NavTarget.ToPoint -> POINT_PREFIX + String.format(Locale.ROOT, "%.6f,%.6f", point.lat, point.lon)
}

/** Inverse of [encode]. Anything malformed falls back to the nearest safe place: never a dead end. */
fun parseNavTarget(raw: String?): NavTarget {
    if (raw == null) return NavTarget.NearestSafe
    return when {
        raw.startsWith(POI_PREFIX) ->
            raw.removePrefix(POI_PREFIX).takeIf { it.isNotBlank() }?.let { NavTarget.ToPoi(it) }
        raw.startsWith(POINT_PREFIX) -> parsePoint(raw.removePrefix(POINT_PREFIX))?.let { NavTarget.ToPoint(it) }
        else -> null
    } ?: NavTarget.NearestSafe
}

private fun parsePoint(text: String): GeoPoint? {
    val parts = text.split(",")
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    return if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
}
