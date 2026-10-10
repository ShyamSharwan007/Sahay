package com.sahay.app.navigate

import com.sahay.app.common.distanceMeters
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.Route
import com.sahay.core.contracts.ShelterStatus
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

/** How many alternative safe places the card lists. */
const val NEARBY_SAFE_COUNT = 3

/** The nearest shelters (not full or closed) other than the current destination, closest first. */
fun nearbySafePlaces(pois: List<Poi>, excludeId: String?, from: GeoPoint): List<Pair<Poi, Double>> =
    pois.asSequence()
        .filter { it.type == PoiType.SHELTER || it.type == PoiType.CANDIDATE_SHELTER }
        .filter { it.status != ShelterStatus.FULL && it.status != ShelterStatus.CLOSED && it.id != excludeId }
        .map { it to distanceMeters(from, it.point) }
        .sortedBy { it.second }
        .take(NEARBY_SAFE_COUNT)
        .toList()
