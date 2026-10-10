package com.sahay.engine.map

import com.sahay.core.contracts.GeoPoint
import com.sahay.engine.pack.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoCircleTest {

    private val mahabalipuram = GeoPoint(12.6208, 80.1945)

    @Test fun `ring is closed and has 64 points plus the closing one`() {
        val ring = GeoCircle.ring(mahabalipuram, 2000.0)
        assertEquals(65, ring.size)
        assertEquals(ring.first(), ring.last())
    }

    @Test fun `every point is the requested distance from the centre`() {
        listOf(50.0, 2000.0, 25_000.0).forEach { radius ->
            GeoCircle.ring(mahabalipuram, radius).forEach { p ->
                assertEquals(radius, GeoMath.haversineM(mahabalipuram, p), radius * 0.001)
            }
        }
    }

    @Test fun `longitudes wrap at the antimeridian`() {
        val ring = GeoCircle.ring(GeoPoint(0.0, 179.99), 5_000.0)
        assertTrue(ring.all { it.lon in -180.0..180.0 })
        assertTrue("some points should have wrapped to the west side", ring.any { it.lon < 0 })
    }

    @Test fun `latitudes stay on the globe near a pole`() {
        val ring = GeoCircle.ring(GeoPoint(89.99, 10.0), 50_000.0)
        assertTrue(ring.all { it.lat in -90.0..90.0 && !it.lon.isNaN() })
    }

    @Test fun `custom step counts work and tiny circles do not collapse to NaN`() {
        assertEquals(9, GeoCircle.ring(mahabalipuram, 10.0, steps = 8).size)
        assertTrue(GeoCircle.ring(mahabalipuram, 0.0).all { !it.lat.isNaN() && !it.lon.isNaN() })
    }

    @Test(expected = IllegalArgumentException::class) fun `fewer than three steps is refused`() {
        GeoCircle.ring(mahabalipuram, 100.0, steps = 2)
    }
}
