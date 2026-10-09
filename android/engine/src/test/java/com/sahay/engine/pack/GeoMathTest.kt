package com.sahay.engine.pack

import com.sahay.core.contracts.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GeoMathTest {

    @Test
    fun haversine_oneDegreeOfLatitudeIsAbout111km() {
        assertEquals(111_195.0, GeoMath.haversineM(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 50.0)
    }

    @Test
    fun haversine_isSymmetricAndZeroForSamePoint() {
        val a = GeoPoint(12.6208, 80.1945)
        val b = GeoPoint(13.0827, 80.2707)
        assertEquals(0.0, GeoMath.haversineM(a, a), 0.0)
        assertEquals(GeoMath.haversineM(a, b), GeoMath.haversineM(b, a), 1e-6)
        assertEquals(52_000.0, GeoMath.haversineM(a, b), 2_000.0)       // Mahabalipuram ↔ Chennai
    }

    @Test
    fun boxAround_containsEveryPointWithinRadius() {
        val random = Random(42)
        for (center in listOf(GeoPoint(12.62, 80.19), GeoPoint(60.0, 10.0), GeoPoint(-33.9, 151.2), GeoPoint(0.0, 0.0))) {
            val radius = 5_000.0
            val box = GeoMath.boxAround(center, radius)
            repeat(2_000) {
                // random point in a ±0.3° neighbourhood; whenever it is within the radius it must be inside the box
                val p = GeoPoint(center.lat + random.nextDouble(-0.3, 0.3), center.lon + random.nextDouble(-0.3, 0.3))
                if (GeoMath.haversineM(center, p) <= radius) {
                    assertTrue("lat of $p for $center", p.lat in box.minLat..box.maxLat)
                    if (box.minLon != null && box.maxLon != null) assertTrue("lon of $p for $center", p.lon in box.minLon..box.maxLon)
                }
            }
        }
    }

    @Test
    fun boxAround_dropsLongitudeFilterNearPolesAndAntimeridian() {
        assertNull(GeoMath.boxAround(GeoPoint(89.99, 0.0), 5_000.0).minLon)
        assertNull(GeoMath.boxAround(GeoPoint(10.0, 179.99), 5_000.0).maxLon)
        assertNull(GeoMath.boxAround(GeoPoint(10.0, -179.99), 5_000.0).minLon)
    }
}
