package com.sahay.engine.routing

import com.sahay.engine.pack.GeoMath
import kotlin.math.asin
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A found path: node indices from start to goal, plus the numbers the route needs. */
internal class PathResult(
    val nodes: IntArray,
    /** Sum of `length × (1 + risk)` over the edges: what A* minimised. */
    val cost: Double,
    val lengthM: Double,
    /** True if any used edge has `risk_cost ≥ 3` (docs/CONTRACTS.md §5.3). */
    val usesHighRiskEdge: Boolean,
)

internal sealed interface SearchOutcome {
    class Found(val path: PathResult) : SearchOutcome
    data object Unreachable : SearchOutcome
    /** The guard asked to stop (deadline passed). Cancellation is signalled by the guard throwing instead. */
    data object Stopped : SearchOutcome
}

/** Polled every [GraphSearch.CHECK_INTERVAL] pops. May throw `CancellationException`; returns true to abort. */
internal fun interface SearchGuard {
    fun shouldStop(): Boolean
}

/**
 * A* over a [RoadGraph] with edge cost `length_m × (1 + risk_cost)` and a haversine heuristic. The heuristic is
 * admissible because the cost is never below the length, and the length is never below the straight-line
 * distance between the endpoints ([RoadGraphBuilder] enforces that).
 *
 * Works on primitive arrays only; the hot loop allocates nothing. An instance owns scratch arrays sized to the
 * graph and is NOT thread-safe: use one per request and reuse it for several searches of that request.
 */
internal class GraphSearch(private val graph: RoadGraph) {
    private val cost = DoubleArray(graph.nodeCount)            // best known cost from the start
    private val parentNode = IntArray(graph.nodeCount)
    private val parentEdge = IntArray(graph.nodeCount)
    private val closed = BooleanArray(graph.nodeCount)
    private val heap = MinHeap(1_024)

    /**
     * @param blocked nodes that must not be entered (null = none). The goal itself may be blocked and is still
     * reached; the start may be blocked and is still left, so a user standing inside a closed area can leave it.
     */
    fun search(start: Int, goal: Int, blocked: BooleanArray?, guard: SearchGuard): SearchOutcome {
        cost.fill(Double.POSITIVE_INFINITY)
        closed.fill(false)
        heap.clear()

        val goalLat = graph.lat[goal]
        val goalLon = graph.lon[goal]
        val goalCos = graph.cosLat[goal]

        cost[start] = 0.0
        heap.push(heuristicM(start, goalLat, goalLon, goalCos), start)
        var pops = 0
        while (!heap.isEmpty) {
            if (pops % CHECK_INTERVAL == 0 && guard.shouldStop()) return SearchOutcome.Stopped
            pops++
            val u = heap.pop()
            if (closed[u]) continue                              // stale duplicate left by a cheaper path
            closed[u] = true
            if (u == goal) return SearchOutcome.Found(pathTo(start, goal))

            val costU = cost[u]
            for (e in graph.offsets[u] until graph.offsets[u + 1]) {
                val v = graph.targets[e]
                if (closed[v]) continue
                if (blocked != null && blocked[v] && v != goal) continue
                val costV = costU + graph.lengthM[e] * (1.0 + graph.riskCost[e])
                if (costV < cost[v]) {
                    cost[v] = costV
                    parentNode[v] = u
                    parentEdge[v] = e
                    heap.push(costV + heuristicM(v, goalLat, goalLon, goalCos), v)
                }
            }
        }
        return SearchOutcome.Unreachable
    }

    /** Great-circle distance from node [v] to the goal (haversine with a cached cos(latitude)). */
    private fun heuristicM(v: Int, goalLat: Double, goalLon: Double, goalCos: Double): Double {
        val sinLat = sin(Math.toRadians(goalLat - graph.lat[v]) / 2)
        val sinLon = sin(Math.toRadians(goalLon - graph.lon[v]) / 2)
        val h = sinLat * sinLat + graph.cosLat[v] * goalCos * sinLon * sinLon
        return 2 * GeoMath.EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    private fun pathTo(start: Int, goal: Int): PathResult {
        var steps = 0
        var node = goal
        while (node != start) {
            node = parentNode[node]; steps++
        }
        // `steps` edges → `steps + 1` nodes. Walk again, filling from the back.
        val nodes = IntArray(steps + 1)
        var lengthM = 0.0
        var highRisk = false
        node = goal
        for (i in steps downTo 1) {
            nodes[i] = node
            val e = parentEdge[node]
            lengthM += graph.lengthM[e]
            if (graph.riskCost[e] >= HIGH_RISK) highRisk = true
            node = parentNode[node]
        }
        nodes[0] = node
        return PathResult(nodes, cost[goal], lengthM, highRisk)
    }

    companion object {
        const val CHECK_INTERVAL = 1_000
        private const val HIGH_RISK = 3f
    }
}

/** Binary min-heap of (priority, node) pairs in two parallel primitive arrays. Duplicates allowed. */
internal class MinHeap(initialCapacity: Int) {
    private var keys = DoubleArray(initialCapacity.coerceAtLeast(2))
    private var values = IntArray(keys.size)
    private var size = 0

    val isEmpty: Boolean get() = size == 0

    fun clear() {
        size = 0
    }

    fun push(key: Double, value: Int) {
        if (size == keys.size) {
            keys = keys.copyOf(size * 2)
            values = values.copyOf(size * 2)
        }
        var i = size++
        while (i > 0) {                                          // sift up
            val parent = (i - 1) ushr 1
            if (keys[parent] <= key) break
            keys[i] = keys[parent]; values[i] = values[parent]
            i = parent
        }
        keys[i] = key; values[i] = value
    }

    /** Removes and returns the node with the smallest key. The heap must not be empty. */
    fun pop(): Int {
        val top = values[0]
        size--
        if (size > 0) {
            val key = keys[size]
            val value = values[size]
            var i = 0
            while (true) {                                       // sift down
                var child = 2 * i + 1
                if (child >= size) break
                if (child + 1 < size && keys[child + 1] < keys[child]) child++
                if (keys[child] >= key) break
                keys[i] = keys[child]; values[i] = values[child]
                i = child
            }
            keys[i] = key; values[i] = value
        }
        return top
    }
}
