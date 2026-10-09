package com.sahay.engine.routing

import com.sahay.engine.pack.GeoMath
import java.util.Arrays
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/**
 * The road network of one pack version in flat primitive arrays: node `i` has coordinates `lat[i]`, `lon[i]`
 * and its outgoing edges are the indices `offsets[i] until offsets[i + 1]` (CSR adjacency); edge `e` leads to
 * node `targets[e]`. Immutable after construction, so it can be shared between threads without locking.
 */
internal class RoadGraph(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val offsets: IntArray,
    val targets: IntArray,
    val lengthM: FloatArray,
    val riskCost: FloatArray,
    /** Edges of the pack that were dropped while loading (unknown node, self loop, bad numbers). */
    val skippedEdges: Int = 0,
) {
    val nodeCount: Int get() = lat.size
    val edgeCount: Int get() = targets.size

    /** cos(latitude) per node, so the A* heuristic does not recompute it for every expansion. */
    val cosLat = DoubleArray(lat.size) { cos(Math.toRadians(lat[it])) }

    private val grid = GridIndex(lat, lon)

    /** Index of the node closest to the point if it is within [maxDistanceM], else -1. */
    fun nearestNode(latDeg: Double, lonDeg: Double, maxDistanceM: Double): Int {
        var best = -1
        var bestDistance = Double.MAX_VALUE
        grid.forEachNodeNear(latDeg, lonDeg, maxDistanceM) { node ->
            val d = GeoMath.haversineM(latDeg, lonDeg, lat[node], lon[node])
            if (d < bestDistance) {
                bestDistance = d
                best = node
            }
        }
        return if (best >= 0 && bestDistance <= maxDistanceM) best else -1
    }

    /** Indices of every node within [radiusM] of the point (possibly empty). */
    fun nodesWithin(latDeg: Double, lonDeg: Double, radiusM: Double): IntArray {
        var found = IntArray(INITIAL_RESULT_CAPACITY)
        var count = 0
        grid.forEachNodeNear(latDeg, lonDeg, radiusM) { node ->
            if (GeoMath.haversineM(latDeg, lonDeg, lat[node], lon[node]) <= radiusM) {
                if (count == found.size) found = found.copyOf(count * 2)
                found[count++] = node
            }
        }
        return found.copyOf(count)
    }

    private companion object {
        const val INITIAL_RESULT_CAPACITY = 16
    }
}

/**
 * Uniform grid over the node bounding box: `cellStart[c] until cellStart[c + 1]` indexes [cellNodes] for cell
 * `c = row * cols + col`. Cells are about 0.003° (≈ 330 m) wide, so a 300 m snap or 50 m block radius touches
 * at most a 3×3 block of cells.
 */
private class GridIndex(lat: DoubleArray, lon: DoubleArray) {
    private val minLat = lat.minOrNull() ?: 0.0
    private val minLon = lon.minOrNull() ?: 0.0
    private val cellDeg: Double
    private val rows: Int
    private val cols: Int
    private val cellStart: IntArray
    private val cellNodes: IntArray

    init {
        val spanLat = (lat.maxOrNull() ?: 0.0) - minLat
        val spanLon = (lon.maxOrNull() ?: 0.0) - minLon
        // A corrupt pack with a huge extent must not allocate millions of cells: coarsen the grid instead.
        var size = CELL_DEG
        while ((spanLat / size + 1) * (spanLon / size + 1) > MAX_CELLS) size *= 2
        cellDeg = size
        rows = (spanLat / size).toInt() + 1
        cols = (spanLon / size).toInt() + 1

        val cellOf = IntArray(lat.size) { row(lat[it]) * cols + col(lon[it]) }
        cellStart = IntArray(rows * cols + 1)
        for (cell in cellOf) cellStart[cell + 1]++
        for (c in 0 until rows * cols) cellStart[c + 1] += cellStart[c]
        val fill = cellStart.copyOf(rows * cols)
        cellNodes = IntArray(lat.size)
        for (node in lat.indices) cellNodes[fill[cellOf[node]]++] = node
    }

    private fun row(latDeg: Double) = ((latDeg - minLat) / cellDeg).toInt().coerceIn(0, rows - 1)
    private fun col(lonDeg: Double) = ((lonDeg - minLon) / cellDeg).toInt().coerceIn(0, cols - 1)

    /** Calls [action] for every node in the cells that can hold a node within [radiusM] of the point. */
    inline fun forEachNodeNear(latDeg: Double, lonDeg: Double, radiusM: Double, action: (Int) -> Unit) {
        if (!latDeg.isFinite() || !lonDeg.isFinite() || cellNodes.isEmpty()) return
        val dLat = radiusM / METRES_PER_DEGREE_LAT
        val dLon = radiusM / (METRES_PER_DEGREE_LAT * max(cos(Math.toRadians(latDeg)), MIN_COS_LAT))
        val rowLow = floor((latDeg - dLat - minLat) / cellDeg).toInt().coerceAtLeast(0)
        val rowHigh = floor((latDeg + dLat - minLat) / cellDeg).toInt().coerceAtMost(rows - 1)
        val colLow = floor((lonDeg - dLon - minLon) / cellDeg).toInt().coerceAtLeast(0)
        val colHigh = floor((lonDeg + dLon - minLon) / cellDeg).toInt().coerceAtMost(cols - 1)
        for (r in rowLow..rowHigh) {
            for (c in colLow..colHigh) {
                val cell = r * cols + c
                for (i in cellStart[cell] until cellStart[cell + 1]) action(cellNodes[i])
            }
        }
    }
}

private const val CELL_DEG = 0.003
private const val MAX_CELLS = 1_000_000.0
private const val METRES_PER_DEGREE_LAT = 110_000.0     // slightly low on purpose: widens the searched block
private const val MIN_COS_LAT = 0.05

/**
 * Collects nodes and edges as they are read from the pack, then [build]s the compact [RoadGraph].
 * Pack node ids can be sparse and large, so edges refer to nodes by id and are resolved to indices here.
 */
internal class RoadGraphBuilder(nodeCapacity: Int = 1_024, edgeCapacity: Int = 4_096) {
    private var nodeIds = LongArray(nodeCapacity.coerceIn(16, MAX_PREALLOCATION))
    private var nodeLat = DoubleArray(nodeIds.size)
    private var nodeLon = DoubleArray(nodeIds.size)
    private var nodes = 0

    private var edgeFrom = LongArray(edgeCapacity.coerceIn(16, MAX_PREALLOCATION))
    private var edgeTo = LongArray(edgeFrom.size)
    private var edgeLength = FloatArray(edgeFrom.size)
    private var edgeRisk = FloatArray(edgeFrom.size)
    private var edges = 0

    fun addNode(id: Long, lat: Double, lon: Double) {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return   // edges to it get skipped
        if (nodes == nodeIds.size) {
            val size = nodes * 2
            nodeIds = nodeIds.copyOf(size); nodeLat = nodeLat.copyOf(size); nodeLon = nodeLon.copyOf(size)
        }
        nodeIds[nodes] = id; nodeLat[nodes] = lat; nodeLon[nodes] = lon
        nodes++
    }

    fun addEdge(fromId: Long, toId: Long, lengthM: Double, riskCost: Double) {
        if (edges == edgeFrom.size) {
            val size = edges * 2
            edgeFrom = edgeFrom.copyOf(size); edgeTo = edgeTo.copyOf(size)
            edgeLength = edgeLength.copyOf(size); edgeRisk = edgeRisk.copyOf(size)
        }
        edgeFrom[edges] = fromId; edgeTo[edges] = toId
        // NaN / negative values would break the cost model: treat them as "unknown" (length is repaired in build()).
        edgeLength[edges] = if (lengthM.isFinite() && lengthM > 0) lengthM.toFloat() else 0f
        edgeRisk[edges] = if (riskCost.isFinite()) riskCost.coerceIn(0.0, MAX_RISK).toFloat() else 0f
        edges++
    }

    fun build(): RoadGraph {
        val sortedIds = LongArray(nodes)
        val lat = DoubleArray(nodes)
        val lon = DoubleArray(nodes)
        val count = sortNodes(sortedIds, lat, lon)

        // Resolve edges to node indices; drop the ones that cannot be used.
        val from = IntArray(edges)
        val to = IntArray(edges)
        val length = FloatArray(edges)
        val risk = FloatArray(edges)
        var kept = 0
        for (e in 0 until edges) {
            val f = Arrays.binarySearch(sortedIds, 0, count, edgeFrom[e])
            val t = Arrays.binarySearch(sortedIds, 0, count, edgeTo[e])
            if (f < 0 || t < 0 || f == t) continue
            // The A* heuristic is the straight-line distance, which is only admissible if no edge is shorter than that.
            // nextUp covers float rounding, which could otherwise put the stored length a hair below the true distance.
            val straight = Math.nextUp(GeoMath.haversineM(lat[f], lon[f], lat[t], lon[t]).toFloat())
            from[kept] = f; to[kept] = t
            length[kept] = max(edgeLength[e], straight)
            risk[kept] = edgeRisk[e]
            kept++
        }

        // Counting sort by source node → CSR.
        val offsets = IntArray(count + 1)
        for (e in 0 until kept) offsets[from[e] + 1]++
        for (n in 0 until count) offsets[n + 1] += offsets[n]
        val next = offsets.copyOf(count)
        val targets = IntArray(kept)
        val lengths = FloatArray(kept)
        val risks = FloatArray(kept)
        for (e in 0 until kept) {
            val slot = next[from[e]]++
            targets[slot] = to[e]; lengths[slot] = length[e]; risks[slot] = risk[e]
        }
        return RoadGraph(lat.copyOf(count), lon.copyOf(count), offsets, targets, lengths, risks, skippedEdges = edges - kept)
    }

    /** Fills the output arrays with the nodes ordered by id, without duplicate ids. Returns the node count. */
    private fun sortNodes(ids: LongArray, lat: DoubleArray, lon: DoubleArray): Int {
        val order: IntArray? = if (isStrictlyAscending()) null
        else (0 until nodes).sortedBy { nodeIds[it] }.toIntArray()        // rare: the loader asks SQL for ORDER BY id
        var count = 0
        for (k in 0 until nodes) {
            val source = order?.get(k) ?: k
            if (count > 0 && ids[count - 1] == nodeIds[source]) continue   // duplicate id: first one wins
            ids[count] = nodeIds[source]; lat[count] = nodeLat[source]; lon[count] = nodeLon[source]
            count++
        }
        return count
    }

    private fun isStrictlyAscending(): Boolean {
        for (i in 1 until nodes) if (nodeIds[i] <= nodeIds[i - 1]) return false
        return true
    }

    private companion object {
        const val MAX_RISK = 5.0
        const val MAX_PREALLOCATION = 5_000_000      // a wrong row count must not trigger a giant allocation
    }
}
