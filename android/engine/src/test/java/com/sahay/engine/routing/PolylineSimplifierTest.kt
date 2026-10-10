package com.sahay.engine.routing

import com.sahay.core.contracts.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineSimplifierTest {
    private val originLat = 12.60
    private val originLon = 80.17

    /** Point [eastM] metres east and [northM] metres north of the origin. */
    private fun at(eastM: Double, northM: Double) =
        GeoPoint(originLat + northM / 111_195.0, originLon + eastM / (111_195.0 * Math.cos(Math.toRadians(originLat))))

    @Test
    fun shortInputsAreReturnedUnchanged() {
        assertEquals(emptyList<GeoPoint>(), PolylineSimplifier.simplify(emptyList(), 5.0))
        val two = listOf(at(0.0, 0.0), at(10.0, 0.0))
        assertEquals(two, PolylineSimplifier.simplify(two, 5.0))
    }

    @Test
    fun collinearPointsAreDropped() {
        val line = (0..10).map { at(it * 50.0, 0.0) }
        assertEquals(listOf(line.first(), line.last()), PolylineSimplifier.simplify(line, 5.0))
    }

    @Test
    fun wobbleWithinToleranceIsDroppedButCornersAreKept() {
        val points = listOf(at(0.0, 0.0), at(100.0, 3.0), at(200.0, -2.0), at(300.0, 0.0), at(300.0, 150.0), at(300.0, 300.0))
        assertEquals(listOf(points[0], points[3], points[5]), PolylineSimplifier.simplify(points, 5.0))
    }

    @Test
    fun deviationJustOverToleranceIsKept() {
        val points = listOf(at(0.0, 0.0), at(100.0, 6.0), at(200.0, 0.0))
        assertEquals(points, PolylineSimplifier.simplify(points, 5.0))
        assertEquals(listOf(points[0], points[2]), PolylineSimplifier.simplify(points, 7.0))
    }

    @Test
    fun uTurnIsNotFlattened() {
        val points = listOf(at(0.0, 0.0), at(200.0, 0.0), at(1.0, 0.0))     // goes out and comes back to nearly the start
        assertEquals(3, PolylineSimplifier.simplify(points, 5.0).size)
    }

    @Test
    fun veryLongRouteDoesNotOverflowTheStack() {
        val zigzag = (0..10_000).map { at(it * 1.0, if (it % 2 == 0) 0.0 else 20.0) }
        val simplified = PolylineSimplifier.simplify(zigzag, 5.0)
        assertEquals(zigzag.first(), simplified.first())
        assertEquals(zigzag.last(), simplified.last())
        assertTrue("20 m zigzag is mostly kept", simplified.size > 9_000)
    }
}
