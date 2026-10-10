package com.sahay.app

import com.sahay.app.map.MapFilter
import com.sahay.app.map.allows
import com.sahay.app.navigate.ARRIVAL_RADIUS_M
import com.sahay.app.navigate.NavTarget
import com.sahay.app.navigate.encode
import com.sahay.app.navigate.hasArrived
import com.sahay.app.navigate.parseNavTarget
import com.sahay.app.navigate.remainingDistanceM
import com.sahay.app.navigate.walkingMinutes
import com.sahay.app.common.ageMinutes
import com.sahay.app.common.distanceMeters
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigateRulesTest {

    private val a = GeoPoint(12.6200, 80.1900)
    private val b = GeoPoint(12.6210, 80.1900)   // ~111 m north of a
    private val c = GeoPoint(12.6220, 80.1900)   // ~111 m north of b

    private fun route(vararg points: GeoPoint) = Route(
        points = points.toList(), distanceM = 222.0, etaMin = 3, destination = null,
        avoidsRiskZones = true, isStraightLine = false, warnings = emptyList(),
    )

    @Test fun nearestSafeEncodesToNull() {
        assertNull(NavTarget.NearestSafe.encode())
        assertEquals(NavTarget.NearestSafe, parseNavTarget(null))
    }

    @Test fun poiTargetRoundTrips() {
        assertEquals(NavTarget.ToPoi("poi_123"), parseNavTarget(NavTarget.ToPoi("poi_123").encode()))
    }

    @Test fun pointTargetRoundTrips() {
        val parsed = parseNavTarget(NavTarget.ToPoint(a).encode()) as NavTarget.ToPoint
        assertEquals(a.lat, parsed.point.lat, 1e-6)
        assertEquals(a.lon, parsed.point.lon, 1e-6)
    }

    @Test fun malformedTargetsFallBackToNearestSafe() {
        listOf("", "poi:", "pt:abc", "pt:1,2,3", "pt:95.0,10.0", "pt:10.0,200.0", "zzz").forEach {
            assertEquals(it, NavTarget.NearestSafe, parseNavTarget(it))
        }
    }

    @Test fun remainingDistanceFollowsTheRouteFromTheNearestPoint() {
        val r = route(a, b, c)
        val atStart = remainingDistanceM(r, a)
        val midway = remainingDistanceM(r, b)
        assertEquals(distanceMeters(a, b) + distanceMeters(b, c), atStart, 0.5)
        assertEquals(distanceMeters(b, c), midway, 0.5)
    }

    @Test fun remainingDistanceWithoutPointsUsesRouteLength() {
        assertEquals(222.0, remainingDistanceM(route(), a), 0.0)
    }

    @Test fun arrivedOnlyWithinRadiusOfTheLastPoint() {
        val r = route(a, b, c)
        assertTrue(hasArrived(r, c))
        assertFalse(hasArrived(r, b))
        val justInside = GeoPoint(c.lat - (ARRIVAL_RADIUS_M - 5) / 111_000.0, c.lon)
        assertTrue(hasArrived(r, justInside))
        assertFalse(hasArrived(route(), a))
    }

    @Test fun walkingMinutesRoundUpAndNeverZero() {
        assertEquals(1, walkingMinutes(0.0))
        assertEquals(1, walkingMinutes(75.0))
        assertEquals(2, walkingMinutes(76.0))
        assertEquals(9, walkingMinutes(650.0))
    }

    @Test fun filtersControlPoiTypes() {
        val onlyShelters = setOf(MapFilter.SHELTERS)
        assertTrue(onlyShelters.allows(PoiType.SHELTER))
        assertTrue(onlyShelters.allows(PoiType.CANDIDATE_SHELTER))
        assertFalse(onlyShelters.allows(PoiType.HOSPITAL))
        assertFalse(onlyShelters.allows(PoiType.POLICE))
    }

    @Test fun ageNeverNegative() {
        assertEquals(0, ageMinutes(1_000, 900))
        assertEquals(2, ageMinutes(1_000, 1_130))
    }
}
