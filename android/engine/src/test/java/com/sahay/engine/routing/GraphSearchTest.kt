package com.sahay.engine.routing

import com.sahay.engine.pack.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.PriorityQueue
import kotlin.random.Random

class GraphSearchTest {
    private val never = SearchGuard { false }

    @Test
    fun heapPopsInKeyOrder() {
        val random = Random(1)
        val heap = MinHeap(2)                                           // tiny start capacity: exercises growth
        val keys = List(5_000) { random.nextDouble() }
        keys.forEachIndexed { i, k -> heap.push(k, i) }
        val popped = generateSequence { if (heap.isEmpty) null else heap.pop() }.map { keys[it] }.toList()
        assertEquals(keys.sorted(), popped)
    }

    @Test
    fun astarMatchesPlainDijkstraOnRandomRoadNetworks() {
        val random = Random(2024)
        repeat(5) { round ->
            val graph = randomNetwork(random, nodes = 600)
            val search = GraphSearch(graph)
            var compared = 0
            repeat(40) {
                val start = random.nextInt(graph.nodeCount)
                val goal = random.nextInt(graph.nodeCount)
                val expected = dijkstraCost(graph, start, goal)
                val outcome = search.search(start, goal, null, never)
                if (expected == null) {
                    assertTrue("round $round: $start→$goal should be unreachable", outcome is SearchOutcome.Unreachable)
                } else {
                    val path = (outcome as SearchOutcome.Found).path
                    assertEquals("round $round: $start→$goal", expected, path.cost, 1e-6)
                    assertEquals(start, path.nodes.first())
                    assertEquals(goal, path.nodes.last())
                    compared++
                }
            }
            assertTrue("network should be mostly connected", compared > 20)
        }
    }

    @Test
    fun pathReportsLengthCostAndRisk() {
        val graph = TestGrid(1, 4) { _, c1, _, c2 -> if (maxOf(c1, c2) == 2) 3.0 else 0.0 }.graph   // edge 1–2 has risk 3
        val path = (GraphSearch(graph).search(0, 3, null, never) as SearchOutcome.Found).path
        assertEquals(listOf(0, 1, 2, 3), path.nodes.toList())
        assertEquals(300.0, path.lengthM, 1.0)
        assertEquals(100.0 + 100.0 * 4.0 + 100.0, path.cost, 2.0)
        assertTrue(path.usesHighRiskEdge)
    }

    @Test
    fun startEqualsGoalIsAOneNodePath() {
        val graph = TestGrid(2, 2).graph
        val path = (GraphSearch(graph).search(3, 3, null, never) as SearchOutcome.Found).path
        assertEquals(listOf(3), path.nodes.toList())
        assertEquals(0.0, path.cost, 0.0)
        assertFalse(path.usesHighRiskEdge)
    }

    @Test
    fun blockedNodesAreNeverEnteredExceptAsStartOrGoal() {
        val grid = TestGrid(1, 3)
        val search = GraphSearch(grid.graph)
        assertTrue(search.search(0, 2, booleanArrayOf(false, true, false), never) is SearchOutcome.Unreachable)
        assertTrue("blocked goal is still reached", search.search(0, 2, booleanArrayOf(false, false, true), never) is SearchOutcome.Found)
        assertTrue("blocked start can be left", search.search(0, 2, booleanArrayOf(true, false, false), never) is SearchOutcome.Found)
    }

    @Test
    fun guardIsPolledEveryThousandPopsAndCanStopTheSearch() {
        val grid = TestGrid(60, 60)                                     // 3,600 nodes: crossing takes well over 1,000 pops
        var polls = 0
        val outcome = GraphSearch(grid.graph).search(0, grid.graph.nodeCount - 1, null, SearchGuard { ++polls >= 2 })
        assertTrue(outcome is SearchOutcome.Stopped)
        assertEquals("polled at pop 0 and pop 1000, not every pop", 2, polls)
    }

    @Test
    fun cancellationFromTheGuardPropagates() {
        val grid = TestGrid(60, 60)
        try {
            GraphSearch(grid.graph).search(0, grid.graph.nodeCount - 1, null, SearchGuard { throw kotlinx.coroutines.CancellationException("cancelled") })
            fail("expected CancellationException")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            // search unwound without returning a route
        }
    }

    @Test
    fun searchInstanceCanBeReusedForSeveralSearches() {
        val grid = TestGrid(5, 5)
        val search = GraphSearch(grid.graph)
        val first = (search.search(0, 24, null, never) as SearchOutcome.Found).path
        search.search(3, 21, null, never)
        val again = (search.search(0, 24, null, never) as SearchOutcome.Found).path
        assertEquals(first.cost, again.cost, 0.0)
        assertEquals(first.nodes.toList(), again.nodes.toList())
    }

    // ---------------------------------------------------------------- helpers

    /** Random connected-ish road network: each node linked to its few nearest neighbours; roads are 0–40 % longer than straight. */
    private fun randomNetwork(random: Random, nodes: Int): RoadGraph {
        val lat = DoubleArray(nodes) { 12.60 + random.nextDouble() * 0.03 }
        val lon = DoubleArray(nodes) { 80.17 + random.nextDouble() * 0.03 }
        val builder = RoadGraphBuilder()
        for (i in 0 until nodes) builder.addNode(i + 1L, lat[i], lon[i])
        for (i in 0 until nodes) {
            val nearest = (0 until nodes).filter { it != i }.sortedBy { GeoMath.haversineM(lat[i], lon[i], lat[it], lon[it]) }.take(3)
            for (j in nearest) {
                val length = GeoMath.haversineM(lat[i], lon[i], lat[j], lon[j]) * (1.0 + 0.4 * random.nextDouble())
                val risk = if (random.nextInt(4) == 0) random.nextDouble() * 5 else 0.0
                builder.addEdge(i + 1L, j + 1L, length, risk)
                builder.addEdge(j + 1L, i + 1L, length, risk)
            }
        }
        return builder.build()
    }

    private fun dijkstraCost(graph: RoadGraph, start: Int, goal: Int): Double? {
        val best = DoubleArray(graph.nodeCount) { Double.POSITIVE_INFINITY }
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        best[start] = 0.0
        queue.add(0.0 to start)
        while (queue.isNotEmpty()) {
            val (cost, u) = queue.poll() ?: break
            if (cost > best[u]) continue
            if (u == goal) return cost
            for (e in graph.offsets[u] until graph.offsets[u + 1]) {
                val next = cost + graph.lengthM[e] * (1.0 + graph.riskCost[e])
                if (next < best[graph.targets[e]]) {
                    best[graph.targets[e]] = next
                    queue.add(next to graph.targets[e])
                }
            }
        }
        return null
    }
}
