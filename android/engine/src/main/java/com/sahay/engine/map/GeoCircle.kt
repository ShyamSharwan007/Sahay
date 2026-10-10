package com.sahay.engine.map

import com.sahay.core.contracts.GeoPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Circles on the globe, drawn as polygons (MapLibre circle layers are screen-sized, not metre-sized). */
internal object GeoCircle {
    private const val EARTH_RADIUS_M = 6_371_000.0
    const val DEFAULT_STEPS = 64

    /**
     * A closed ring of [steps] + 1 points (first == last) at [radiusM] metres around [center].
     * Longitudes are wrapped into -180..180; latitudes are kept inside the poles.
     */
    fun ring(center: GeoPoint, radiusM: Double, steps: Int = DEFAULT_STEPS): List<GeoPoint> {
        require(steps >= 3) { "A circle needs at least 3 steps" }
        val angular = radiusM / EARTH_RADIUS_M
        val lat1 = Math.toRadians(center.lat)
        val lon1 = Math.toRadians(center.lon)
        val points = (0 until steps).map { i ->
            val bearing = 2 * Math.PI * i / steps
            val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
            val lon2 = lon1 + atan2(
                sin(bearing) * sin(angular) * cos(lat1),
                cos(angular) - sin(lat1) * sin(lat2),
            )
            GeoPoint(Math.toDegrees(lat2).coerceIn(-90.0, 90.0), wrapLon(Math.toDegrees(lon2)))
        }
        return points + points.first()
    }

    private fun wrapLon(lon: Double): Double = ((lon + 540.0) % 360.0) - 180.0
}
