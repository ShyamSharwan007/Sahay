package com.sahay.engine.routing

import com.sahay.core.contracts.GeoPoint
import kotlin.math.cos
import kotlin.math.hypot

/** Douglas–Peucker line simplification with the tolerance in metres. */
internal object PolylineSimplifier {
    private const val METRES_PER_DEGREE = 111_195.0

    /**
     * Keeps the first and last point and every point that deviates more than [toleranceM] from the line through
     * its neighbours. Works on a local flat projection, which is accurate to well under a metre at city scale.
     * Iterative (explicit stack), so a route with tens of thousands of points cannot overflow the call stack.
     */
    fun simplify(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
        if (points.size <= 2) return points
        val originLat = points.first().lat
        val originLon = points.first().lon
        val lonScale = METRES_PER_DEGREE * cos(Math.toRadians(originLat))
        val x = DoubleArray(points.size) { (points[it].lon - originLon) * lonScale }
        val y = DoubleArray(points.size) { (points[it].lat - originLat) * METRES_PER_DEGREE }

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.lastIndex))
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var farthest = -1
            var farthestDistance = toleranceM
            for (i in first + 1 until last) {
                val d = distanceToSegmentM(x[i], y[i], x[first], y[first], x[last], y[last])
                if (d > farthestDistance) {
                    farthestDistance = d
                    farthest = i
                }
            }
            if (farthest >= 0) {
                keep[farthest] = true
                stack.addLast(intArrayOf(first, farthest))
                stack.addLast(intArrayOf(farthest, last))
            }
        }
        return points.filterIndexed { index, _ -> keep[index] }
    }

    /** Distance from (px, py) to the segment a–b (not the infinite line), so U-turns are not flattened. */
    private fun distanceToSegmentM(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
