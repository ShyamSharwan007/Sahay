package com.sahay.engine.routing

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RouteWarning
import com.sahay.core.contracts.SahayConfig
import com.sahay.core.contracts.ShelterStatus
import com.sahay.engine.pack.GeoMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class RealRoutingEngineTest {

    private val logs = mutableListOf<String>()

    private fun engine(data: FakeRoutingData, timeoutMs: Long = 2_000L) =
        RealRoutingEngine(data, Dispatchers.Default, timeoutMs) { synchronized(logs) { logs += it } }

    // ---------------------------------------------------------------- cost model

    @Test
    fun longerLowRiskPathBeatsShortHighRiskPath() = runBlocking {
        // 3 rows × 5 columns, 100 m apart (row 0 is the southernmost). The straight middle row is 400 m but risk 4
        // (cost ×5); the detour along row 0 is 600 m at risk 0; the detour along row 2 is 600 m at risk 1 (cost ×2).
        val grid = TestGrid(3, 5) { r1, _, r2, _ -> if (r1 == 1 && r2 == 1) 4.0 else if (r1 == 2 && r2 == 2) 1.0 else 0.0 }
        val route = engine(FakeRoutingData(graph = grid.graph)).routeTo(grid.point(1, 0), grid.point(1, 4))!!

        assertEquals(600.0, route.distanceM, 5.0)
        assertTrue("goes via row 0, south of the middle row", route.points.any { it.lat < grid.point(1, 0).lat - 1e-6 })
        assertTrue("never uses the risky row 2", route.points.none { it.lat > grid.point(1, 0).lat + 1e-6 })
        assertTrue(route.avoidsRiskZones)
        assertFalse(route.isStraightLine)
        assertTrue(route.warnings.isEmpty())
        assertEquals(ceil(route.distanceM / SahayConfig.WALKING_M_PER_MIN).toInt(), route.etaMin)
    }

    @Test
    fun routeThroughHighRiskEdgeIsFlaggedWhenThereIsNoAlternative() = runBlocking {
        val grid = TestGrid(1, 5) { _, _, _, _ -> 3.0 }                 // only road, risk exactly 3
        val route = engine(FakeRoutingData(graph = grid.graph)).routeTo(grid.point(0, 0), grid.point(0, 4))!!

        assertEquals(400.0, route.distanceM, 3.0)
        assertFalse(route.avoidsRiskZones)
        assertEquals(listOf(RouteWarning.PASSES_RISK_AREA), route.warnings)
    }

    @Test
    fun riskBelowThreeStillCountsAsAvoiding() = runBlocking {
        val grid = TestGrid(1, 4) { _, _, _, _ -> 2.9 }
        val route = engine(FakeRoutingData(graph = grid.graph)).routeTo(grid.point(0, 0), grid.point(0, 3))!!
        assertTrue(route.avoidsRiskZones)
    }

    @Test
    fun distanceIncludesWalkToTheRoadAndSimplifiedPointsKeepEndpoints() = runBlocking {
        val grid = TestGrid(1, 6)                                       // straight 500 m road
        val start = GeoPoint(grid.point(0, 0).lat + 0.0009, grid.point(0, 0).lon)          // ~100 m north of the road
        val end = grid.point(0, 5)
        val route = engine(FakeRoutingData(graph = grid.graph)).routeTo(start, end)!!

        assertEquals(500.0 + GeoMath.haversineM(start, grid.point(0, 0)), route.distanceM, 5.0)
        assertEquals(start, route.points.first())
        assertEquals(end, route.points.last())
        assertTrue("collinear road nodes are simplified away", route.points.size < 7)
    }

    // ---------------------------------------------------------------- blocked points

    @Test
    fun blockedPointForcesDetourAndClearingRestoresDirectRoute() = runBlocking {
        val grid = TestGrid(3, 5)
        val engine = engine(FakeRoutingData(graph = grid.graph))
        val start = grid.point(1, 0)
        val goal = grid.point(1, 4)
        val blocked = grid.point(1, 2)

        assertEquals(400.0, engine.routeTo(start, goal)!!.distanceM, 5.0)

        engine.setBlockedPoints(listOf(blocked))                         // graph already loaded: mask built immediately
        val detour = engine.routeTo(start, goal)!!
        assertEquals(600.0, detour.distanceM, 5.0)
        assertTrue(detour.points.all { GeoMath.haversineM(it, blocked) > 50.0 })

        engine.setBlockedPoints(emptyList())
        assertEquals(400.0, engine.routeTo(start, goal)!!.distanceM, 5.0)
    }

    @Test
    fun blockedPointsSetBeforeTheGraphIsLoadedAreApplied() = runBlocking {
        val grid = TestGrid(3, 5)
        val engine = engine(FakeRoutingData(graph = grid.graph))
        engine.setBlockedPoints(listOf(grid.point(1, 2)))
        assertEquals(600.0, engine.routeTo(grid.point(1, 0), grid.point(1, 4))!!.distanceM, 5.0)
    }

    @Test
    fun nodesWithin50mAreBlockedButNodes100mAwayAreNot() = runBlocking {
        val grid = TestGrid(1, 5)                                       // single road: blocking its middle cuts it
        val engine = engine(FakeRoutingData(graph = grid.graph))
        val almostOnNode = GeoPoint(grid.point(0, 2).lat + 0.0003, grid.point(0, 2).lon)   // ~33 m from node (0,2)
        engine.setBlockedPoints(listOf(almostOnNode))
        assertNull("no other way round", engine.routeTo(grid.point(0, 0), grid.point(0, 4)))

        val farFromNode = GeoPoint(grid.point(0, 2).lat + 0.0009, grid.point(0, 2).lon)    // ~100 m from every node
        engine.setBlockedPoints(listOf(farFromNode))
        assertNotNull(engine.routeTo(grid.point(0, 0), grid.point(0, 4)))
    }

    @Test
    fun userStandingInsideABlockedAreaCanStillWalkOut() = runBlocking {
        val grid = TestGrid(3, 5)
        val engine = engine(FakeRoutingData(graph = grid.graph))
        engine.setBlockedPoints(listOf(grid.point(1, 0)))                // blocked point right at the start node
        val route = engine.routeTo(grid.point(1, 0), grid.point(1, 4))
        assertNotNull(route)
        assertEquals(400.0, route!!.distanceM, 5.0)
    }

    // ---------------------------------------------------------------- snapping, no pack, timeout

    @Test
    fun startFarFromAnyRoadGivesStraightLineWithWarning() = runBlocking {
        val grid = TestGrid(3, 5)
        val farAway = GeoPoint(grid.point(0, 0).lat + 0.05, grid.point(0, 0).lon)           // ~5.5 km north
        val route = engine(FakeRoutingData(graph = grid.graph)).routeTo(farAway, grid.point(1, 4))!!

        assertTrue(route.isStraightLine)
        assertEquals(listOf(RouteWarning.START_FAR_FROM_ROAD), route.warnings)
        assertEquals(listOf(farAway, grid.point(1, 4)), route.points)
        assertEquals(GeoMath.haversineM(farAway, grid.point(1, 4)), route.distanceM, 0.5)
        assertFalse(route.avoidsRiskZones)
    }

    @Test
    fun destinationFarFromAnyRoadGivesStraightLineWithWarning() = runBlocking {
        val grid = TestGrid(3, 5)
        val farAway = GeoPoint(grid.point(0, 0).lat, grid.point(0, 0).lon + 0.05)
        val route = engine(FakeRoutingData(graph = grid.graph)).routeTo(grid.point(1, 0), farAway)!!
        assertTrue(route.isStraightLine)
        assertTrue(RouteWarning.START_FAR_FROM_ROAD in route.warnings)
    }

    @Test
    fun snapRadiusIs300Metres() = runBlocking {
        val grid = TestGrid(1, 5)
        val engine = engine(FakeRoutingData(graph = grid.graph))
        val inside = GeoPoint(grid.point(0, 0).lat + 280 / 111_195.0, grid.point(0, 0).lon)
        val outside = GeoPoint(grid.point(0, 0).lat + 320 / 111_195.0, grid.point(0, 0).lon)
        assertFalse(engine.routeTo(inside, grid.point(0, 4))!!.isStraightLine)
        assertTrue(engine.routeTo(outside, grid.point(0, 4))!!.isStraightLine)
    }

    @Test
    fun routeToMatchesDestinationPoi() = runBlocking {
        val grid = TestGrid(1, 5)
        val shelter = testPoi("shelter", PoiType.SHELTER, grid.point(0, 4))
        val engine = engine(FakeRoutingData(graph = grid.graph, pois = listOf(shelter)))
        assertEquals(shelter, engine.routeTo(grid.point(0, 0), grid.point(0, 4))!!.destination)
        assertNull(engine.routeTo(grid.point(0, 0), grid.point(0, 3))!!.destination)
    }

    @Test
    fun noPackGivesStraightLineWithNoPackWarningForRouteToAndNullForNearestSafe() = runBlocking {
        val engine = engine(FakeRoutingData(pack = null))
        val a = GeoPoint(12.62, 80.19)
        val b = GeoPoint(12.63, 80.19)
        val route = engine.routeTo(a, b)!!
        assertTrue(route.isStraightLine)
        assertEquals(listOf(RouteWarning.NO_PACK), route.warnings)
        assertNull(engine.routeToNearestSafe(a))
    }

    @Test
    fun unreachableDestinationGivesNull() = runBlocking {
        val two = RoadGraphBuilder().apply {                            // two islands
            addNode(1, 12.60, 80.17); addNode(2, 12.601, 80.17)
            addNode(3, 12.62, 80.17); addNode(4, 12.621, 80.17)
            addEdge(1, 2, 111.0, 0.0); addEdge(2, 1, 111.0, 0.0)
            addEdge(3, 4, 111.0, 0.0); addEdge(4, 3, 111.0, 0.0)
        }.build()
        assertNull(engine(FakeRoutingData(graph = two)).routeTo(GeoPoint(12.60, 80.17), GeoPoint(12.621, 80.17)))
    }

    @Test
    fun searchTimeoutGivesNull() = runBlocking {
        val grid = TestGrid(3, 5)
        val engine = engine(FakeRoutingData(graph = grid.graph, pois = listOf(testPoi("s", PoiType.SHELTER, grid.point(1, 4)))), timeoutMs = 0)
        assertNull(engine.routeTo(grid.point(1, 0), grid.point(1, 4)))
        assertNull(engine.routeToNearestSafe(grid.point(1, 0)))
    }

    @Test
    fun invalidCoordinatesGiveNull() = runBlocking {
        val engine = engine(FakeRoutingData(graph = TestGrid(1, 3).graph))
        assertNull(engine.routeTo(GeoPoint(Double.NaN, 80.0), GeoPoint(12.6, 80.17)))
        assertNull(engine.routeTo(GeoPoint(12.6, 80.17), GeoPoint(95.0, 80.17)))
        assertNull(engine.routeToNearestSafe(GeoPoint(Double.NaN, Double.NaN)))
    }

    // ---------------------------------------------------------------- loading

    @Test
    fun graphIsLoadedOncePerPackVersionEvenUnderConcurrentCalls() = runBlocking {
        val grid = TestGrid(3, 5)
        val data = FakeRoutingData(graph = grid.graph)
        val engine = engine(data)
        List(16) { async(Dispatchers.Default) { engine.routeTo(grid.point(1, 0), grid.point(1, 4)) } }.awaitAll().forEach { assertNotNull(it) }
        assertEquals(1, data.graphLoads.get())
        assertEquals(1, logs.count { it.contains("15 nodes") && it.contains("loaded in") })

        engine.routeTo(grid.point(1, 0), grid.point(1, 4))
        assertEquals("same version: no reload", 1, data.graphLoads.get())

        data.pack = testPack(version = "2")
        engine.routeTo(grid.point(1, 0), grid.point(1, 4))
        assertEquals("new pack version: reload", 2, data.graphLoads.get())
    }

    @Test
    fun deletedPackDropsTheGraph() = runBlocking {
        val grid = TestGrid(3, 5)
        val data = FakeRoutingData(graph = grid.graph)
        val engine = engine(data)
        assertFalse(engine.routeTo(grid.point(1, 0), grid.point(1, 4))!!.isStraightLine)
        data.pack = null
        assertEquals(listOf(RouteWarning.NO_PACK), engine.routeTo(grid.point(1, 0), grid.point(1, 4))!!.warnings)
    }

    // ---------------------------------------------------------------- routeToNearestSafe (CONTRACTS §5.3)

    @Test
    fun fullClosedAndHighZoneSheltersAreSkipped() = runBlocking {
        val grid = TestGrid(5, 5)
        val from = grid.point(2, 0)
        val highZoneSpot = grid.point(3, 1)
        val pois = listOf(
            testPoi("full", PoiType.SHELTER, grid.point(2, 1), ShelterStatus.FULL),             // nearest
            testPoi("closed", PoiType.CANDIDATE_SHELTER, grid.point(1, 1), ShelterStatus.CLOSED),
            testPoi("in_high_zone", PoiType.SHELTER, highZoneSpot),
            testPoi("open_far", PoiType.CANDIDATE_SHELTER, grid.point(2, 4), ShelterStatus.OPEN),
            testPoi("unknown_farther", PoiType.SHELTER, grid.point(4, 4), ShelterStatus.UNKNOWN),
            testPoi("police", PoiType.POLICE, grid.point(2, 2)),
        )
        val data = FakeRoutingData(graph = grid.graph, pois = pois, zones = listOf(testZone("z", RiskLevel.HIGH, highZoneSpot)))
        val route = engine(data).routeToNearestSafe(from)!!

        assertEquals("open_far", route.destination?.id)
        assertEquals(400.0, route.distanceM, 5.0)
        assertTrue(RouteWarning.NO_OPEN_SHELTER_USING_HOSPITAL !in route.warnings)
        assertEquals(grid.point(2, 4), route.points.last())
    }

    @Test
    fun mediumZoneDoesNotExcludeAShelter() = runBlocking {
        val grid = TestGrid(1, 4)
        val spot = grid.point(0, 3)
        val data = FakeRoutingData(graph = grid.graph, pois = listOf(testPoi("s", PoiType.SHELTER, spot)), zones = listOf(testZone("z", RiskLevel.MEDIUM, spot)))
        assertEquals("s", engine(data).routeToNearestSafe(grid.point(0, 0))?.destination?.id)
    }

    @Test
    fun picksLowestCostAmongNearestNotJustTheNearestByStraightLine() = runBlocking {
        // A is 200 m away in a straight line but the direct road to it is dangerous; B is 224 m away with a clean road.
        val grid = TestGrid(5, 5) { r1, _, r2, _ -> if (r1 == 2 && r2 == 2) 4.0 else 0.0 }
        val from = grid.point(2, 0)
        val a = testPoi("A", PoiType.SHELTER, grid.point(2, 2))
        val b = testPoi("B", PoiType.SHELTER, grid.point(0, 1))
        val route = engine(FakeRoutingData(graph = grid.graph, pois = listOf(a, b))).routeToNearestSafe(from)!!
        assertEquals("B", route.destination?.id)
        assertEquals(300.0, route.distanceM, 5.0)
        assertTrue(route.avoidsRiskZones)
    }

    @Test
    fun onlyTheFiveNearestCandidatesAreConsidered() = runBlocking {
        // Star around S: five shelters 100–140 m away on risk-5 roads (cost ≈ 6× length) and a sixth 400 m away on a
        // clean road. The sixth would be cheaper, but it is not among the five nearest by straight line.
        val s = GeoPoint(12.60, 80.17)
        fun north(metres: Double) = GeoPoint(s.lat + metres / 111_195.0, s.lon)
        val builder = RoadGraphBuilder()
        builder.addNode(1, s.lat, s.lon)
        val dangerous = (1..5).map { i ->
            val place = north(90.0 + 10.0 * i)
            builder.addNode(1L + i, place.lat, place.lon)
            val length = GeoMath.haversineM(s, place)
            builder.addEdge(1, 1L + i, length, 5.0); builder.addEdge(1L + i, 1, length, 5.0)
            testPoi("d$i", PoiType.SHELTER, place)
        }
        val farClean = GeoPoint(s.lat, s.lon + 400.0 / (111_195.0 * Math.cos(Math.toRadians(s.lat))))
        builder.addNode(7, farClean.lat, farClean.lon)
        builder.addEdge(1, 7, GeoMath.haversineM(s, farClean), 0.0); builder.addEdge(7, 1, GeoMath.haversineM(s, farClean), 0.0)

        val data = FakeRoutingData(graph = builder.build(), pois = dangerous + testPoi("clean", PoiType.SHELTER, farClean))
        assertEquals("d1", engine(data).routeToNearestSafe(s)?.destination?.id)
    }

    @Test
    fun fallsBackToNearestHospitalWhenNoShelterIsOpen() = runBlocking {
        val grid = TestGrid(3, 5)
        val pois = listOf(
            testPoi("full", PoiType.SHELTER, grid.point(1, 1), ShelterStatus.FULL),
            testPoi("closed", PoiType.CANDIDATE_SHELTER, grid.point(1, 2), ShelterStatus.CLOSED),
            testPoi("hospital_far", PoiType.HOSPITAL, grid.point(1, 4)),
            testPoi("hospital_near", PoiType.HOSPITAL, grid.point(2, 1)),
        )
        val route = engine(FakeRoutingData(graph = grid.graph, pois = pois)).routeToNearestSafe(grid.point(1, 0))!!
        assertEquals("hospital_near", route.destination?.id)
        assertEquals(listOf(RouteWarning.NO_OPEN_SHELTER_USING_HOSPITAL), route.warnings)
    }

    @Test
    fun hospitalFallbackAlsoWhenNoShelterExistsAtAll() = runBlocking {
        val grid = TestGrid(1, 4)
        val data = FakeRoutingData(graph = grid.graph, pois = listOf(testPoi("h", PoiType.HOSPITAL, grid.point(0, 3))))
        val route = engine(data).routeToNearestSafe(grid.point(0, 0))!!
        assertEquals("h", route.destination?.id)
        assertTrue(RouteWarning.NO_OPEN_SHELTER_USING_HOSPITAL in route.warnings)
    }

    @Test
    fun nothingToGoToGivesNull() = runBlocking {
        val grid = TestGrid(1, 4)
        val full = testPoi("full", PoiType.SHELTER, grid.point(0, 3), ShelterStatus.FULL)
        assertNull(engine(FakeRoutingData(graph = grid.graph, pois = listOf(full))).routeToNearestSafe(grid.point(0, 0)))
    }

    @Test
    fun shelterThatIsCutOffIsSkippedForTheNextOne() = runBlocking {
        val grid = TestGrid(1, 8)                                       // one road, user at column 3
        val cutOff = testPoi("cut_off", PoiType.SHELTER, grid.point(0, 5))      // 200 m: nearest, but behind the block
        val reachable = testPoi("reachable", PoiType.SHELTER, grid.point(0, 0)) // 300 m, the other way
        val engine = engine(FakeRoutingData(graph = grid.graph, pois = listOf(cutOff, reachable)))
        engine.setBlockedPoints(listOf(grid.point(0, 4)))
        assertEquals("reachable", engine.routeToNearestSafe(grid.point(0, 3))?.destination?.id)
    }

    @Test
    fun startFarFromRoadPointsStraightAtNearestOpenShelter() = runBlocking {
        val grid = TestGrid(3, 5)
        val from = GeoPoint(grid.point(0, 0).lat + 0.05, grid.point(0, 0).lon)
        val pois = listOf(
            testPoi("full_nearest", PoiType.SHELTER, GeoPoint(from.lat - 0.001, from.lon), ShelterStatus.FULL),
            testPoi("open", PoiType.SHELTER, grid.point(1, 2)),
        )
        val route = engine(FakeRoutingData(graph = grid.graph, pois = pois)).routeToNearestSafe(from)!!
        assertTrue(route.isStraightLine)
        assertEquals(listOf(RouteWarning.START_FAR_FROM_ROAD), route.warnings)
        assertEquals("open", route.destination?.id)
    }
}
