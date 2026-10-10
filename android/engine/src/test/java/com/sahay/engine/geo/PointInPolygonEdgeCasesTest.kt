package com.sahay.engine.geo

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Awkward geometry the risk monitor will meet in real packs: shared vertices, slivers, bad numbers. */
class PointInPolygonEdgeCasesTest {

    // Diamond: vertices at (lat, lon) = (0,1) (1,2) (2,1) (1,0).
    private val diamond = PolygonRing(
        listOf(GeoPoint(0.0, 1.0), GeoPoint(1.0, 2.0), GeoPoint(2.0, 1.0), GeoPoint(1.0, 0.0)),
    )

    @Test
    fun rayThroughAVertexIsCountedOnce() {
        // Latitude 1.0 passes exactly through two vertices of the diamond.
        assertTrue(diamond.contains(1.0, 1.0))            // centre
        assertFalse(diamond.contains(1.0, -1.0))          // left of the shape: the ray enters and leaves
        assertFalse(diamond.contains(1.0, 3.0))           // right of the shape: nothing to cross
    }

    @Test
    fun pointsOutsideTheBoundingBoxAreRejected() {
        assertFalse(diamond.contains(-0.001, 1.0))
        assertFalse(diamond.contains(2.001, 1.0))
        assertFalse(diamond.contains(1.0, -0.001))
        assertFalse(diamond.contains(1.0, 2.001))
        // Inside the bounding box but outside the diamond (a corner of the box).
        assertFalse(diamond.contains(0.1, 0.1))
    }

    @Test
    fun repeatedAndCollinearPointsDoNotBreakTheRing() {
        val ring = PolygonRing(
            listOf(
                GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0),            // duplicated vertex
                GeoPoint(0.0, 2.0), GeoPoint(0.0, 4.0),            // collinear along the bottom edge
                GeoPoint(4.0, 4.0), GeoPoint(4.0, 0.0),
            ),
        )
        assertTrue(ring.contains(2.0, 2.0))
        assertFalse(ring.contains(5.0, 2.0))
    }

    @Test
    fun zeroAreaRingContainsNothing() {
        val line = PolygonRing(listOf(GeoPoint(1.0, 1.0), GeoPoint(2.0, 2.0), GeoPoint(3.0, 3.0)))
        assertFalse(line.contains(2.0, 2.0))
        val singlePoint = PolygonRing(listOf(GeoPoint(1.0, 1.0), GeoPoint(1.0, 1.0), GeoPoint(1.0, 1.0)))
        assertFalse(singlePoint.contains(1.0, 1.0))
    }

    @Test
    fun thinSliverStillWorksAtRealCoordinates() {
        // ~10 m wide strip along a road near Mahabalipuram.
        val strip = PolygonRing(
            listOf(GeoPoint(12.6200, 80.1900), GeoPoint(12.6200, 80.19009), GeoPoint(12.6300, 80.19009), GeoPoint(12.6300, 80.1900)),
        )
        assertTrue(strip.contains(12.6250, 80.19004))
        assertFalse(strip.contains(12.6250, 80.19020))
    }

    @Test
    fun nonFiniteInputNeverMatches() {
        assertFalse(diamond.contains(Double.POSITIVE_INFINITY, 1.0))
        assertFalse(diamond.contains(1.0, Double.NEGATIVE_INFINITY))
        assertFalse(diamond.contains(Double.NaN, Double.NaN))
    }

    @Test
    fun zoneWithoutPolygonsOrWithDegenerateOnesIsNeverFound() {
        val noPolygons = RiskZone("none", null, RiskLevel.HIGH, emptyList())
        val degenerate = RiskZone("bad", null, RiskLevel.HIGH, listOf(listOf(GeoPoint(1.0, 1.0), GeoPoint(2.0, 2.0))))
        val index = RiskZoneIndex(listOf(noPolygons, degenerate))
        assertNull(index.zoneAt(GeoPoint(1.5, 1.5)))
    }

    @Test
    fun separateZonesAreToldApart() {
        val west = RiskZone("west", null, RiskLevel.HIGH, listOf(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0), GeoPoint(1.0, 1.0), GeoPoint(1.0, 0.0))))
        val east = RiskZone("east", null, RiskLevel.HIGH, listOf(listOf(GeoPoint(0.0, 2.0), GeoPoint(0.0, 3.0), GeoPoint(1.0, 3.0), GeoPoint(1.0, 2.0))))
        val index = RiskZoneIndex(listOf(west, east))
        assertEquals("west", index.zoneAt(GeoPoint(0.5, 0.5))?.id)
        assertEquals("east", index.zoneAt(GeoPoint(0.5, 2.5))?.id)
        assertNull(index.zoneAt(GeoPoint(0.5, 1.5)))          // the gap between them
    }
}
