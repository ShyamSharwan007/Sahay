package com.sahay.engine.geo

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointInPolygonTest {
    private fun square(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = listOf(
        GeoPoint(minLat, minLon), GeoPoint(minLat, maxLon), GeoPoint(maxLat, maxLon), GeoPoint(maxLat, minLon), GeoPoint(minLat, minLon),
    )

    @Test
    fun squareContainsInnerPointsOnly() {
        val ring = PolygonRing(square(12.60, 80.17, 12.62, 80.19))
        assertTrue(ring.contains(12.61, 80.18))
        assertFalse(ring.contains(12.63, 80.18))
        assertFalse(ring.contains(12.61, 80.20))
        assertFalse(ring.contains(12.59, 80.16))
    }

    @Test
    fun concaveShapeExcludesTheNotch() {
        // "U" opening to the north: the notch (x 2..3, y 1..4) is outside.
        val u = listOf(
            GeoPoint(0.0, 0.0), GeoPoint(0.0, 5.0), GeoPoint(4.0, 5.0), GeoPoint(4.0, 3.0), GeoPoint(1.0, 3.0),
            GeoPoint(1.0, 2.0), GeoPoint(4.0, 2.0), GeoPoint(4.0, 0.0),
        )
        val ring = PolygonRing(u)
        assertTrue(ring.contains(0.5, 2.5))             // bottom of the U
        assertTrue(ring.contains(3.0, 1.0))             // left arm
        assertTrue(ring.contains(3.0, 4.0))             // right arm
        assertFalse(ring.contains(3.0, 2.5))            // inside the notch
    }

    @Test
    fun ringWorksWithOrWithoutClosingPointAndInEitherWinding() {
        val closed = square(0.0, 0.0, 1.0, 1.0)
        val open = closed.dropLast(1)
        for (ring in listOf(PolygonRing(closed), PolygonRing(open), PolygonRing(closed.reversed()))) {
            assertTrue(ring.contains(0.5, 0.5))
            assertFalse(ring.contains(1.5, 0.5))
        }
    }

    @Test
    fun degenerateRingsAndBadInputNeverContainAnything() {
        assertFalse(PolygonRing(emptyList()).contains(0.0, 0.0))
        assertFalse(PolygonRing(listOf(GeoPoint(0.0, 0.0), GeoPoint(1.0, 1.0))).contains(0.5, 0.5))
        assertFalse(PolygonRing(square(0.0, 0.0, 1.0, 1.0)).contains(Double.NaN, 0.5))
    }

    @Test
    fun zoneIndexReturnsHighBeforeMediumAndHandlesMultiPolygons() {
        val medium = RiskZone("m", null, RiskLevel.MEDIUM, listOf(square(0.0, 0.0, 10.0, 10.0)))
        val high = RiskZone("h", "Low coast", RiskLevel.HIGH, listOf(square(2.0, 2.0, 3.0, 3.0), square(6.0, 6.0, 7.0, 7.0)))
        val index = RiskZoneIndex(listOf(medium, high))        // medium listed first on purpose

        assertEquals("h", index.zoneAt(GeoPoint(2.5, 2.5))?.id)       // overlap: HIGH wins
        assertEquals("h", index.zoneAt(GeoPoint(6.5, 6.5))?.id)       // second polygon of the same zone
        assertEquals("m", index.zoneAt(GeoPoint(5.0, 5.0))?.id)
        assertNull(index.zoneAt(GeoPoint(20.0, 20.0)))
        assertTrue(index.isInHighZone(GeoPoint(2.5, 2.5)))
        assertFalse(index.isInHighZone(GeoPoint(5.0, 5.0)))
        assertFalse(index.isInHighZone(GeoPoint(20.0, 20.0)))
    }

    @Test
    fun emptyIndexFindsNothing() {
        assertNull(RiskZoneIndex(emptyList()).zoneAt(GeoPoint(1.0, 1.0)))
    }
}
