package com.sahay.engine.routing

import com.sahay.core.contracts.GeoPoint
import com.sahay.engine.pack.GeoMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class RoadGraphTest {

    @Test
    fun builderCreatesCsrAdjacencyAndSkipsBrokenEdges() {
        val graph = RoadGraphBuilder().apply {
            addNode(10, 12.600, 80.170); addNode(20, 12.601, 80.170); addNode(30, 12.602, 80.170)
            addEdge(10, 20, 111.0, 1.0)
            addEdge(20, 30, 111.0, 2.0)
            addEdge(20, 10, 111.0, 1.0)
            addEdge(10, 99, 50.0, 0.0)          // unknown node
            addEdge(30, 30, 10.0, 0.0)          // self loop
        }.build()

        assertEquals(3, graph.nodeCount)
        assertEquals(3, graph.edgeCount)
        assertEquals(2, graph.skippedEdges)
        assertArrayEquals(intArrayOf(0, 1, 3, 3), graph.offsets)
        assertEquals(1, graph.targets[0])                           // 10 → 20
        assertEquals(setOf(0, 2), setOf(graph.targets[1], graph.targets[2]))      // 20 → 10 and 20 → 30
    }

    @Test
    fun builderAcceptsUnsortedAndDuplicateNodeIds() {
        val graph = RoadGraphBuilder().apply {
            addNode(30, 12.602, 80.170); addNode(10, 12.600, 80.170); addNode(20, 12.601, 80.170); addNode(20, 50.0, 50.0)
            addEdge(10, 20, 111.0, 0.0); addEdge(30, 20, 111.0, 0.0)
        }.build()
        assertEquals(3, graph.nodeCount)
        assertEquals(12.601, graph.lat[1], 0.0)                     // first copy of duplicate id 20 wins
        assertEquals(2, graph.edgeCount)
    }

    @Test
    fun builderRepairsUnusableEdgeNumbers() {
        val graph = RoadGraphBuilder().apply {
            addNode(1, 12.600, 80.170); addNode(2, 12.601, 80.170)
            addEdge(1, 2, Double.NaN, 99.0)             // unknown length, risk above range
            addEdge(2, 1, 1.0, -3.0)                    // shorter than the straight line, negative risk
        }.build()
        val straight = GeoMath.haversineM(12.600, 80.170, 12.601, 80.170).toFloat()
        assertEquals(straight, graph.lengthM[0], 0.01f)
        assertEquals(5f, graph.riskCost[0], 0f)
        assertEquals(straight, graph.lengthM[1], 0.01f)            // never shorter than the straight line: keeps A* admissible
        assertEquals(0f, graph.riskCost[1], 0f)
    }

    @Test
    fun builderIgnoresNodesWithInvalidCoordinatesAndTheirEdges() {
        val graph = RoadGraphBuilder().apply {
            addNode(1, 12.6, 80.17); addNode(2, Double.NaN, 80.17); addNode(3, 95.0, 80.17)
            addEdge(1, 2, 10.0, 0.0); addEdge(1, 3, 10.0, 0.0)
        }.build()
        assertEquals(1, graph.nodeCount)
        assertEquals(0, graph.edgeCount)
    }

    @Test
    fun emptyGraphBuildsAndAnswersQueries() {
        val graph = RoadGraphBuilder().build()
        assertEquals(0, graph.nodeCount)
        assertEquals(-1, graph.nearestNode(12.6, 80.17, 300.0))
        assertEquals(0, graph.nodesWithin(12.6, 80.17, 50.0).size)
    }

    @Test
    fun nearestNodeAndNodesWithinAgreeWithBruteForce() {
        val random = Random(7)
        val builder = RoadGraphBuilder()
        val points = List(3_000) { GeoPoint(12.59 + random.nextDouble() * 0.06, 80.16 + random.nextDouble() * 0.05) }
        points.forEachIndexed { i, p -> builder.addNode(i + 1L, p.lat, p.lon) }
        val graph = builder.build()

        repeat(300) {
            val q = GeoPoint(12.58 + random.nextDouble() * 0.08, 80.15 + random.nextDouble() * 0.07)    // also outside the extent
            val distances = points.map { GeoMath.haversineM(q, it) }
            for (radius in listOf(50.0, 300.0)) {
                val expectedWithin = distances.indices.filter { distances[it] <= radius }.toSet()
                assertEquals(expectedWithin, graph.nodesWithin(q.lat, q.lon, radius).toSet())
                val nearest = graph.nearestNode(q.lat, q.lon, radius)
                if (expectedWithin.isEmpty()) assertEquals(-1, nearest)
                else assertEquals(distances.min(), distances[nearest], 1e-9)
            }
        }
    }

    @Test
    fun hugeExtentDoesNotExplodeTheGrid() {
        val graph = RoadGraphBuilder().apply {
            addNode(1, -80.0, -170.0); addNode(2, 80.0, 170.0); addNode(3, 0.0, 0.0)
            addEdge(1, 3, 1.0, 0.0)
        }.build()
        assertEquals(2, graph.nearestNode(0.0, 0.0, 10.0))          // index 2 = id 3
        assertEquals(1, graph.nearestNode(80.0, 170.0, 10.0))
        assertEquals(-1, graph.nearestNode(40.0, 40.0, 300.0))
    }
}
