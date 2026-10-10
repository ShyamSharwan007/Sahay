package com.sahay.engine.geo

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone

/**
 * One polygon ring with a precomputed bounding box. [contains] rejects points outside the box with four
 * comparisons and only then runs the even-odd ray-casting test (ray towards +longitude).
 * Points exactly on the boundary may count as inside or outside. Holes are not modelled (zones carry outer rings only).
 */
internal class PolygonRing(points: List<GeoPoint>) {
    private val lats = DoubleArray(points.size) { points[it].lat }
    private val lons = DoubleArray(points.size) { points[it].lon }
    private val minLat = lats.minOrNull() ?: Double.NaN
    private val maxLat = lats.maxOrNull() ?: Double.NaN
    private val minLon = lons.minOrNull() ?: Double.NaN
    private val maxLon = lons.maxOrNull() ?: Double.NaN

    fun contains(point: GeoPoint): Boolean = contains(point.lat, point.lon)

    fun contains(lat: Double, lon: Double): Boolean {
        if (lats.size < MIN_RING_POINTS) return false
        if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false     // NaN input falls through to false below
        var inside = false
        var j = lats.size - 1
        for (i in lats.indices) {
            // The edge i–j straddles the ray's latitude: flip when the crossing lies east of the point.
            if ((lats[i] > lat) != (lats[j] > lat)) {
                val crossingLon = lons[i] + (lat - lats[i]) / (lats[j] - lats[i]) * (lons[j] - lons[i])
                if (lon < crossingLon) inside = !inside
            }
            j = i
        }
        return inside
    }

    private companion object {
        const val MIN_RING_POINTS = 3
    }
}

/**
 * Point-in-zone lookup over all polygons of a pack's risk zones. Built once per pack, then read-only, so it
 * is safe to share between threads. Used by routing now and by the risk monitor later.
 */
internal class RiskZoneIndex(zones: List<RiskZone>) {
    private class Entry(val zone: RiskZone, val ring: PolygonRing)

    // HIGH sorts before MEDIUM (enum order), so the first hit is always the most severe zone.
    private val entries: List<Entry> = zones
        .flatMap { zone -> zone.polygons.map { Entry(zone, PolygonRing(it)) } }
        .sortedBy { it.zone.level }

    /** The most severe zone containing [point] (HIGH wins over MEDIUM), or null. */
    fun zoneAt(point: GeoPoint): RiskZone? = entries.firstOrNull { it.ring.contains(point) }?.zone

    fun isInHighZone(point: GeoPoint): Boolean = zoneAt(point)?.level == RiskLevel.HIGH
}
