package com.sahay.engine.routing

import android.util.Log
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.Route
import com.sahay.core.contracts.RouteWarning
import com.sahay.core.contracts.RoutingEngine
import com.sahay.core.contracts.SahayConfig
import com.sahay.core.contracts.ShelterStatus
import com.sahay.engine.geo.RiskZoneIndex
import com.sahay.engine.pack.GeoMath
import com.sahay.engine.pack.RealPackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Offline walking routes over the road network of the active pack (rules: docs/CONTRACTS.md §5.3).
 *
 * The network is read once per pack version into primitive arrays, on first use. Each request then runs A*
 * on [Dispatchers.Default] with a time budget; cancelling the caller stops the search within ~1,000 node pops.
 */
@Singleton
class RealRoutingEngine internal constructor(
    private val data: RoutingDataSource,
    private val dispatcher: CoroutineDispatcher,
    private val searchTimeoutMs: Long,
    private val log: (String) -> Unit,
) : RoutingEngine {

    @Inject
    constructor(packs: RealPackRepository) : this(
        data = RepositoryRoutingData(packs),
        dispatcher = Dispatchers.Default,
        searchTimeoutMs = SEARCH_TIMEOUT_MS,
        log = { Log.i(TAG, it) },
    )

    /** Everything derived from one pack version. Immutable, replaced as a whole. */
    private class PackRouting(val key: PackKey, val graph: RoadGraph, val zones: RiskZoneIndex)

    /** Identifies a pack install: a re-download of the same version still gets a fresh graph. */
    private data class PackKey(val regionId: String, val packVersion: String, val downloadedAtEpochSec: Long) {
        constructor(pack: PackInfo) : this(pack.regionId, pack.packVersion, pack.downloadedAtEpochSec)
    }

    /** Blocked points with the node mask computed for [graph] (null until a graph is known). */
    private class BlockedState(val points: List<GeoPoint>, val graph: RoadGraph?, val mask: BooleanArray?)

    @Volatile private var loaded: PackRouting? = null
    private val loadMutex = Mutex()
    private val blockedState = AtomicReference(BlockedState(emptyList(), null, null))

    // ================================================================== public API

    override suspend fun routeTo(from: GeoPoint, to: GeoPoint): Route? = withContext(dispatcher) {
        if (!from.isUsable() || !to.isUsable()) return@withContext null
        val routing = routingData() ?: return@withContext straightLine(from, to, null, listOf(RouteWarning.NO_PACK))
        val destination = poiAt(to)
        val startNode = routing.graph.nearestNode(from.lat, from.lon, SNAP_RADIUS_M)
        val goalNode = routing.graph.nearestNode(to.lat, to.lon, SNAP_RADIUS_M)
        if (startNode < 0 || goalNode < 0) {
            return@withContext straightLine(from, to, destination, listOf(RouteWarning.START_FAR_FROM_ROAD))
        }
        when (val outcome = newSession(routing).search(startNode, goalNode)) {
            is SearchOutcome.Found -> roadRoute(routing.graph, from, to, outcome.path, destination, emptyList())
            SearchOutcome.Unreachable, SearchOutcome.Stopped -> null
        }
    }

    override suspend fun routeToNearestSafe(from: GeoPoint): Route? = withContext(dispatcher) {
        if (!from.isUsable()) return@withContext null
        val routing = routingData() ?: return@withContext null
        val session = newSession(routing)

        val shelters = nearestFirst(from, openShelters(routing))
        val startNode = routing.graph.nearestNode(from.lat, from.lon, SNAP_RADIUS_M)
        if (startNode < 0) {
            // No road to start from: point at the nearest shelter, else the nearest hospital, as the crow flies.
            return@withContext straightLineToNearest(from, shelters) { nearestHospitals(from) }
        }

        val shelterPick = cheapestRoute(session, startNode, from, shelters)
        if (shelterPick.route != null || shelterPick.stopped) return@withContext shelterPick.route
        firstReachableRoute(session, startNode, from, nearestHospitals(from))
    }

    override fun setBlockedPoints(points: List<GeoPoint>) {
        val usable = points.filter { it.isUsable() }          // also takes a private copy of the caller's list
        val graph = loaded?.graph
        blockedState.set(BlockedState(usable, graph, graph?.let { blockedMask(it, usable) }))
    }

    // ================================================================== nearest safe place

    /** SHELTER and CANDIDATE_SHELTER that are not FULL/CLOSED and not inside a HIGH risk zone. */
    private suspend fun openShelters(routing: PackRouting): List<Poi> =
        data.pois(setOf(PoiType.SHELTER, PoiType.CANDIDATE_SHELTER)).filter {
            it.status != ShelterStatus.FULL && it.status != ShelterStatus.CLOSED && !routing.zones.isInHighZone(it.point)
        }

    private suspend fun nearestHospitals(from: GeoPoint): List<Poi> =
        nearestFirst(from, data.pois(setOf(PoiType.HOSPITAL)))

    /** Routes to each candidate (already the nearest by straight line) and keeps the one with the lowest cost. */
    private fun cheapestRoute(session: Session, startNode: Int, from: GeoPoint, candidates: List<Poi>): Pick {
        var best: Route? = null
        var bestCost = Double.MAX_VALUE
        for (poi in candidates) {
            when (val attempt = session.tryRoadRoute(startNode, from, poi, emptyList())) {
                is Attempt.Ok -> if (attempt.cost < bestCost) {
                    bestCost = attempt.cost
                    best = attempt.route
                }
                Attempt.NoRoad -> Unit
                Attempt.Stopped -> return Pick(best, stopped = true)      // keep what we found so far
            }
        }
        return Pick(best, stopped = false)
    }

    /** Hospital fallback: the nearest hospital that can be reached by road. */
    private fun firstReachableRoute(session: Session, startNode: Int, from: GeoPoint, candidates: List<Poi>): Route? {
        for (poi in candidates) {
            when (val attempt = session.tryRoadRoute(startNode, from, poi, listOf(RouteWarning.NO_OPEN_SHELTER_USING_HOSPITAL))) {
                is Attempt.Ok -> return attempt.route
                Attempt.NoRoad -> Unit
                Attempt.Stopped -> return null
            }
        }
        return null
    }

    private suspend fun straightLineToNearest(from: GeoPoint, shelters: List<Poi>, hospitals: suspend () -> List<Poi>): Route? {
        shelters.firstOrNull()?.let {
            return straightLine(from, it.point, it, listOf(RouteWarning.START_FAR_FROM_ROAD))
        }
        val hospital = hospitals().firstOrNull() ?: return null
        return straightLine(from, hospital.point, hospital, listOf(RouteWarning.NO_OPEN_SHELTER_USING_HOSPITAL, RouteWarning.START_FAR_FROM_ROAD))
    }

    private fun nearestFirst(from: GeoPoint, pois: List<Poi>): List<Poi> =
        pois.sortedBy { GeoMath.haversineM(from, it.point) }.take(MAX_CANDIDATES)

    /** Best route found by [cheapestRoute], and whether the time budget ran out while looking. */
    private class Pick(val route: Route?, val stopped: Boolean)

    private sealed interface Attempt {
        class Ok(val route: Route, val cost: Double) : Attempt
        /** Destination too far from roads, or no connected path (e.g. blocked). */
        data object NoRoad : Attempt
        data object Stopped : Attempt
    }

    // ================================================================== one request

    /** Scratch arrays, blocked-node mask and time budget shared by the searches of one public call. */
    private inner class Session(
        private val graph: RoadGraph,
        private val searcher: GraphSearch,
        private val blocked: BooleanArray?,
        private val guard: SearchGuard,
    ) {
        fun search(start: Int, goal: Int): SearchOutcome = searcher.search(start, goal, blocked, guard)

        /** Road route to a POI, or why there is none. The POI itself may be off-road by up to the snap radius. */
        fun tryRoadRoute(startNode: Int, from: GeoPoint, destination: Poi, warnings: List<RouteWarning>): Attempt {
            val goalNode = graph.nearestNode(destination.point.lat, destination.point.lon, SNAP_RADIUS_M)
            if (goalNode < 0) return Attempt.NoRoad
            return when (val outcome = search(startNode, goalNode)) {
                is SearchOutcome.Found ->
                    Attempt.Ok(roadRoute(graph, from, destination.point, outcome.path, destination, warnings), outcome.path.cost)
                SearchOutcome.Unreachable -> Attempt.NoRoad
                SearchOutcome.Stopped -> Attempt.Stopped
            }
        }
    }

    private suspend fun newSession(routing: PackRouting): Session {
        val job = currentCoroutineContext()[Job]
        val deadlineNs = System.nanoTime() + searchTimeoutMs * NANOS_PER_MS
        val guard = SearchGuard {
            job?.ensureActive()                                  // caller cancelled: unwind immediately
            System.nanoTime() - deadlineNs >= 0
        }
        return Session(routing.graph, GraphSearch(routing.graph), blockedMaskFor(routing.graph), guard)
    }

    // ================================================================== route assembly

    private fun roadRoute(
        graph: RoadGraph,
        from: GeoPoint,
        to: GeoPoint,
        path: PathResult,
        destination: Poi?,
        extraWarnings: List<RouteWarning>,
    ): Route {
        val first = path.nodes.first()
        val last = path.nodes.last()
        val raw = ArrayList<GeoPoint>(path.nodes.size + 2)
        raw += from
        for (node in path.nodes) raw += GeoPoint(graph.lat[node], graph.lon[node])
        raw += to

        // Walking from the real start to the first road node, and from the last node to the real destination, counts too.
        val distanceM = GeoMath.haversineM(from.lat, from.lon, graph.lat[first], graph.lon[first]) +
            path.lengthM +
            GeoMath.haversineM(graph.lat[last], graph.lon[last], to.lat, to.lon)
        val avoidsRisk = !path.usesHighRiskEdge
        return Route(
            points = PolylineSimplifier.simplify(raw, SIMPLIFY_TOLERANCE_M),
            distanceM = distanceM,
            etaMin = etaMinutes(distanceM),
            destination = destination,
            avoidsRiskZones = avoidsRisk,
            isStraightLine = false,
            warnings = if (avoidsRisk) extraWarnings else extraWarnings + RouteWarning.PASSES_RISK_AREA,
        )
    }

    /** A direct line: we cannot say anything about roads or risk along it, so it never claims to avoid risk zones. */
    private fun straightLine(from: GeoPoint, to: GeoPoint, destination: Poi?, warnings: List<RouteWarning>): Route {
        val distanceM = GeoMath.haversineM(from, to)
        return Route(
            points = listOf(from, to),
            distanceM = distanceM,
            etaMin = etaMinutes(distanceM),
            destination = destination,
            avoidsRiskZones = false,
            isStraightLine = true,
            warnings = warnings,
        )
    }

    private fun etaMinutes(distanceM: Double): Int = ceil(distanceM / SahayConfig.WALKING_M_PER_MIN).toInt()

    /** The POI the user asked to go to, if the target point is (almost exactly) one. */
    private suspend fun poiAt(point: GeoPoint): Poi? =
        data.pois(PoiType.entries.toSet())
            .minByOrNull { GeoMath.haversineM(point, it.point) }
            ?.takeIf { GeoMath.haversineM(point, it.point) <= DESTINATION_MATCH_M }

    // ================================================================== pack data (lazy, once per pack version)

    /** Graph and zone index of the active pack; null if there is no usable pack. */
    private suspend fun routingData(): PackRouting? {
        val pack = data.activePack()
        if (pack == null) {
            loaded = null                                        // pack deleted: let the graph be collected
            return null
        }
        val key = PackKey(pack)
        loaded?.takeIf { it.key == key }?.let { return it }
        return loadMutex.withLock {
            repeat(MAX_LOAD_ATTEMPTS) {
                val current = data.activePack()?.let(::PackKey) ?: return@withLock null
                loaded?.takeIf { it.key == current }?.let { return@withLock it }
                val fresh = withContext(dispatcher) { load(current) } ?: return@withLock null
                // The pack may have been replaced while we were reading; only keep a graph that is still current.
                if (data.activePack()?.let(::PackKey) == current) {
                    loaded = fresh
                    return@withLock fresh
                }
            }
            null
        }
    }

    private suspend fun load(key: PackKey): PackRouting? {
        val startedNs = System.nanoTime()
        val graph = try {
            data.roadGraph()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {                          // unreadable pack must not crash routing
            log("Road graph could not be read: ${e.message}")
            null
        }
        if (graph == null || graph.nodeCount == 0) {
            log("No usable road graph in pack ${key.regionId}/${key.packVersion}")
            return null
        }
        val routing = PackRouting(key, graph, RiskZoneIndex(data.riskZones()))
        val tookMs = (System.nanoTime() - startedNs) / NANOS_PER_MS
        log("Road graph ${key.regionId}/${key.packVersion}: ${graph.nodeCount} nodes, ${graph.edgeCount} edges " +
            "(${graph.skippedEdges} skipped), loaded in $tookMs ms")
        return routing
    }

    // ================================================================== blocked points

    /** Mask for [graph] from the current blocked points; recomputed once if the graph changed since they were set. */
    private fun blockedMaskFor(graph: RoadGraph): BooleanArray? {
        val state = blockedState.get()
        if (state.points.isEmpty()) return null
        if (state.graph === graph) return state.mask
        val mask = blockedMask(graph, state.points)
        blockedState.compareAndSet(state, BlockedState(state.points, graph, mask))
        return mask
    }

    private fun blockedMask(graph: RoadGraph, points: List<GeoPoint>): BooleanArray {
        val mask = BooleanArray(graph.nodeCount)
        for (point in points) {
            for (node in graph.nodesWithin(point.lat, point.lon, BLOCK_RADIUS_M)) mask[node] = true
        }
        return mask
    }

    private fun GeoPoint.isUsable() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    private companion object {
        const val TAG = "RealRoutingEngine"
        const val SNAP_RADIUS_M = 300.0
        const val BLOCK_RADIUS_M = 50.0
        const val SIMPLIFY_TOLERANCE_M = 5.0
        const val MAX_CANDIDATES = 5
        const val SEARCH_TIMEOUT_MS = 2_000L
        const val NANOS_PER_MS = 1_000_000L
        const val MAX_LOAD_ATTEMPTS = 2
        const val DESTINATION_MATCH_M = 25.0
    }
}
