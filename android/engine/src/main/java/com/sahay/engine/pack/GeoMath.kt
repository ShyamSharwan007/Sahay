package com.sahay.engine.pack

import com.sahay.core.contracts.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Latitude/longitude limits of a search box. A null longitude range means "do not filter on longitude". */
internal data class LatLonBox(val minLat: Double, val maxLat: Double, val minLon: Double?, val maxLon: Double?)

internal object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineM(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /**
     * A box that contains every point within [radiusM] of [center] (great-circle distance), so it is safe to
     * use as an SQL pre-filter before the exact haversine check.
     * Near a pole, or when the box would cross the antimeridian, the longitude filter is dropped.
     */
    fun boxAround(center: GeoPoint, radiusM: Double): LatLonBox {
        val angular = radiusM / EARTH_RADIUS_M                      // radians
        val latRad = Math.toRadians(center.lat)
        val minLat = Math.toDegrees(latRad - angular)
        val maxLat = Math.toDegrees(latRad + angular)
        if (minLat <= -90.0 || maxLat >= 90.0 || angular >= PI / 2) {
            return LatLonBox(maxOf(minLat, -90.0), minOf(maxLat, 90.0), null, null)
        }
        val dLonRad = asin(sin(angular) / cos(latRad))
        val minLon = center.lon - Math.toDegrees(dLonRad)
        val maxLon = center.lon + Math.toDegrees(dLonRad)
        val crossesAntimeridian = minLon < -180.0 || maxLon > 180.0
        return LatLonBox(minLat, maxLat, minLon.takeUnless { crossesAntimeridian }, maxLon.takeUnless { crossesAntimeridian })
    }
}
