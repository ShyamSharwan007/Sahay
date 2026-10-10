package com.sahay.app.navigate

import com.sahay.app.common.distanceMeters
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.Route
import com.sahay.core.contracts.SahayConfig
import kotlin.math.ceil
import kotlin.math.max

/** The user counts as arrived this close to the destination. */
const val ARRIVAL_RADIUS_M = 30.0

/** Where the route ends: the destination place if there is one, otherwise the last route point. */
fun destinationPoint(route: Route): GeoPoint? = route.destination?.point ?: route.points.lastOrNull()

/**
 * Distance still to walk: from [position] to the nearest route point, then along the rest of the route.
 * Falls back to the route length when the route has no points.
 */
fun remainingDistanceM(route: Route, position: GeoPoint): Double {
    val points = route.points
    if (points.isEmpty()) return route.distanceM
    var nearest = 0
    var nearestDistance = Double.MAX_VALUE
    points.forEachIndexed { index, point ->
        val d = distanceMeters(position, point)
        if (d < nearestDistance) {
            nearestDistance = d
            nearest = index
        }
    }
    var remaining = nearestDistance
    for (i in nearest until points.lastIndex) remaining += distanceMeters(points[i], points[i + 1])
    return remaining
}

fun hasArrived(route: Route, position: GeoPoint): Boolean {
    val destination = destinationPoint(route) ?: return false
    return distanceMeters(position, destination) <= ARRIVAL_RADIUS_M
}

/** Whole minutes at walking speed, at least 1. */
fun walkingMinutes(distanceM: Double): Int =
    max(1, ceil(distanceM / SahayConfig.WALKING_M_PER_MIN).toInt())
