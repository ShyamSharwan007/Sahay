package com.sahay.engine.pack

import com.sahay.core.contracts.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoJsonPolygonsTest {

    @Test
    fun polygon_returnsOuterRingAsLatLon() {
        val rings = GeoJsonPolygons.outerRings(
            """{"type":"Polygon","coordinates":[[[80.0,12.0],[80.1,12.0],[80.1,12.1],[80.0,12.0]]]}""",
        )
        assertEquals(1, rings.size)
        // GeoJSON is [lon, lat]; GeoPoint is (lat, lon)
        assertEquals(
            listOf(GeoPoint(12.0, 80.0), GeoPoint(12.0, 80.1), GeoPoint(12.1, 80.1), GeoPoint(12.0, 80.0)),
            rings[0],
        )
    }

    @Test
    fun polygon_ignoresHoles() {
        val rings = GeoJsonPolygons.outerRings(
            """{"type":"Polygon","coordinates":[
                [[0,0],[10,0],[10,10],[0,10],[0,0]],
                [[2,2],[4,2],[4,4],[2,2]]]}""",
        )
        assertEquals(1, rings.size)
        assertEquals(5, rings[0].size)
    }

    @Test
    fun multiPolygon_returnsOneOuterRingPerPolygon() {
        val rings = GeoJsonPolygons.outerRings(
            """{"type":"MultiPolygon","coordinates":[
                [[[0,0],[1,0],[1,1],[0,0]]],
                [[[5,5],[6,5],[6,6],[5,5]],[[5.2,5.2],[5.4,5.2],[5.4,5.4],[5.2,5.2]]]]}""",
        )
        assertEquals(2, rings.size)
        assertEquals(GeoPoint(5.0, 5.0), rings[1][0])
        assertTrue("holes must not become extra rings", rings.all { it.size == 4 })
    }

    @Test
    fun feature_wrapperIsAccepted() {
        val rings = GeoJsonPolygons.outerRings(
            """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}}""",
        )
        assertEquals(1, rings.size)
    }

    @Test
    fun degenerateRings_areDropped() {
        val rings = GeoJsonPolygons.outerRings(
            """{"type":"MultiPolygon","coordinates":[[[[0,0],[1,1]]],[[[0,0],[1,0],[1,1],[0,0]]],[]]}""",
        )
        assertEquals(1, rings.size)
    }

    @Test
    fun extraPositionValues_likeElevation_areIgnored() {
        val rings = GeoJsonPolygons.outerRings("""{"type":"Polygon","coordinates":[[[80,12,5.0],[81,12,5.0],[81,13,5.0]]]}""")
        assertEquals(GeoPoint(12.0, 80.0), rings[0][0])
    }

    @Test
    fun invalidInput_throwsIllegalArgument() {
        listOf(
            "not json",
            """{"type":"Point","coordinates":[1,2]}""",
            """{"type":"Polygon"}""",
            """{"type":"Polygon","coordinates":[[[1],[2],[3]]]}""",
            """{"type":"Polygon","coordinates":[[["a","b"],[1,1],[2,2]]]}""",
            """{"type":"Polygon","coordinates":[[[200,95],[1,1],[2,2]]]}""",       // lon/lat out of range (swapped values)
            """{"type":"Feature"}""",
        ).forEach { bad ->
            assertThrows("input: $bad", IllegalArgumentException::class.java) { GeoJsonPolygons.outerRings(bad) }
        }
    }
}
