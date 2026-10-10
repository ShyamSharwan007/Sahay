package com.sahay.engine.routing

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.PoiType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Speed check on a synthetic city: 250 × 200 = 50,000 nodes, ~200,000 directed edges, 30 m blocks, about a fifth of
 * the roads carrying a random risk cost of up to 5 (which makes the straight-line heuristic less helpful).
 * Timings are printed with the prefix "BENCHMARK" and also asserted against the 500 ms budget.
 */
class RoutingBenchmarkTest {
    private val rows = 200
    private val cols = 250
    private val budgetMs = 500.0

    private fun symmetricRisk(r1: Int, c1: Int, r2: Int, c2: Int): Double {
        val a = r1 * cols + c1
        val b = r2 * cols + c2
        val random = Random(min(a, b) * 100_003L + max(a, b))
        return if (random.nextInt(5) == 0) random.nextDouble() * 5.0 else 0.0
    }

    @Test
    fun routesAcrossA50kNodeGridWithinBudget() = runBlocking {
        val buildStart = System.nanoTime()
        val grid = TestGrid(rows, cols, spacingM = 30.0, risk = ::symmetricRisk)
        val buildMs = (System.nanoTime() - buildStart) / 1e6
        assertTrue(grid.graph.nodeCount == 50_000)

        val random = Random(99)
        val shelters = List(12) { testPoi("s$it", PoiType.SHELTER, grid.point(random.nextInt(rows), random.nextInt(cols))) }
        val data = FakeRoutingData(graph = grid.graph, pois = shelters)
        val engine = RealRoutingEngine(data, Dispatchers.Default, 2_000L) {}

        fun timed(block: suspend () -> Any?): Pair<Double, Any?> {
            val t0 = System.nanoTime()
            val result = runBlocking { block() }
            return (System.nanoTime() - t0) / 1e6 to result
        }

        // Cold: first request pays JIT warm-up and the once-per-pack-version setup.
        val (coldMs, cold) = timed { engine.routeTo(grid.point(0, 0), grid.point(rows - 1, cols - 1)) }
        assertNotNull(cold)

        val pairTimes = List(30) {
            val from = grid.point(random.nextInt(rows), random.nextInt(cols))
            val to = grid.point(random.nextInt(rows), random.nextInt(cols))
            val (ms, route) = timed { engine.routeTo(from, to) }
            assertNotNull(route)
            assertFalse((route as com.sahay.core.contracts.Route).isStraightLine)
            ms
        }
        val cornerTimes = List(10) { timed { engine.routeTo(grid.point(0, 0), grid.point(rows - 1, cols - 1)) }.first }
        val safeTimes = List(15) {
            val from = grid.point(random.nextInt(rows), random.nextInt(cols))
            val (ms, route) = timed { engine.routeToNearestSafe(from) }
            assertNotNull(route)
            ms
        }

        fun median(values: List<Double>) = values.sorted()[values.size / 2]
        println("BENCHMARK graph build (50,000 nodes, ${grid.graph.edgeCount} edges): ${"%.0f".format(buildMs)} ms")
        println("BENCHMARK routeTo corner->corner, cold first call: ${"%.1f".format(coldMs)} ms")
        println("BENCHMARK routeTo corner->corner, warm: median ${"%.1f".format(median(cornerTimes))} ms, max ${"%.1f".format(cornerTimes.max())} ms")
        println("BENCHMARK routeTo random pairs (30): median ${"%.1f".format(median(pairTimes))} ms, max ${"%.1f".format(pairTimes.max())} ms")
        println("BENCHMARK routeToNearestSafe (15, 12 shelters, 5 searches each): median ${"%.1f".format(median(safeTimes))} ms, max ${"%.1f".format(safeTimes.max())} ms")

        assertTrue("cold call ${coldMs} ms", coldMs < budgetMs)
        assertTrue("random pairs max ${pairTimes.max()} ms", pairTimes.max() < budgetMs)
        assertTrue("corner max ${cornerTimes.max()} ms", cornerTimes.max() < budgetMs)
        assertTrue("nearest safe max ${safeTimes.max()} ms", safeTimes.max() < budgetMs)
    }
}
