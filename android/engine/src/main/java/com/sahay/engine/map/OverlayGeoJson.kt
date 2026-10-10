package com.sahay.engine.map

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.GroupStatus
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.PeopleGroup
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone
import com.sahay.core.contracts.Route
import com.sahay.core.contracts.SahayAlert
import com.sahay.core.contracts.ShelterStatus
import com.sahay.core.contracts.TrustLabel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.sqrt

/**
 * Turns [com.sahay.core.contracts.MapViewState] parts into GeoJSON text for the overlay sources.
 * Pure Kotlin so it can be unit-tested. Everything a layer needs (colours, sizes, icon names) is a feature
 * property, and every clickable feature has an `id` property to find the original object again.
 */
internal object OverlayGeoJson {

    const val PROP_ID = "id"

    // ------------------------------------------------------------------ risk zones (DESIGN §2)

    /** HIGH: danger, 28% fill and a 2 px outline. MEDIUM: warning, 20% fill, no outline. */
    fun riskZones(zones: List<RiskZone>, palette: MapPalette): String = collection(
        zones.flatMap { zone ->
            val high = zone.level == RiskLevel.HIGH
            zone.polygons.mapIndexedNotNull { index, ring ->
                polygonFeature(
                    listOf(ring),
                    "${zone.id}#$index",
                    "color" to (if (high) palette.danger else palette.warning),
                    "fillOpacity" to (if (high) 0.28 else 0.20),
                    "lineWidth" to (if (high) 2.0 else 0.0),
                )
            }
        },
    )

    // ------------------------------------------------------------------ alert circles

    /** 64-point polygons in the severity colour with a 15% fill. Alerts without area or radius are skipped. */
    fun alertCircles(alerts: List<SahayAlert>, palette: MapPalette): String = collection(
        alerts.mapNotNull { alert ->
            val area = alert.area ?: return@mapNotNull null
            val radius = alert.radiusM?.takeIf { it > 0 } ?: return@mapNotNull null
            polygonFeature(
                listOf(GeoCircle.ring(area, radius.toDouble().coerceAtMost(MAX_ALERT_RADIUS_M))),
                alert.id,
                "color" to palette.severity(alert.severity),
            )
        },
    )

    // ------------------------------------------------------------------ route

    /** One LineString. Straight-line fallbacks are marked `dashed` so they do not look like a real road route. */
    fun route(route: Route?, palette: MapPalette): String {
        val points = route?.points.orEmpty()
        if (route == null || points.size < 2) return collection(emptyList())
        return collection(
            listOf(
                feature(
                    buildJsonObject {
                        put("type", "LineString")
                        put("coordinates", coordinates(points))
                    },
                    "route",
                    "color" to palette.primary,
                    "casing" to palette.casing,
                    "dashed" to route.isStraightLine,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ places

    /** Shelters that are full or closed get the greyed icon. */
    fun pois(pois: List<Poi>): String = collection(
        pois.map { poi -> pointFeature(poi.point, poi.id, "icon" to MapIcons.iconId(poi.type, isOff(poi))) },
    )

    internal fun isOff(poi: Poi): Boolean =
        (poi.type == PoiType.SHELTER || poi.type == PoiType.CANDIDATE_SHELTER) &&
            (poi.status == ShelterStatus.FULL || poi.status == ShelterStatus.CLOSED)

    // ------------------------------------------------------------------ reports

    /** VERIFIED danger, LIKELY warning, UNCONFIRMED a hollow ring (so it never looks like a confirmed hazard). */
    fun reports(reports: List<HazardReport>, palette: MapPalette): String = collection(
        reports.map { report ->
            val (fill, fillOpacity, stroke) = when (report.label) {
                TrustLabel.VERIFIED -> Triple(palette.danger, 1.0, palette.casing)
                TrustLabel.LIKELY -> Triple(palette.warning, 1.0, palette.casing)
                TrustLabel.UNCONFIRMED -> Triple(palette.muted, 0.0, palette.muted)
            }
            pointFeature(report.point, report.id, "fill" to fill, "fillOpacity" to fillOpacity, "stroke" to stroke)
        },
    )

    // ------------------------------------------------------------------ groups

    /** Circle radius (dp) grows with the square root of the size; the count is the label ("99+" above 99). */
    fun groups(groups: List<PeopleGroup>, palette: MapPalette): String = collection(
        groups.map { group ->
            val color = when (group.status) {
                GroupStatus.AT_SHELTER -> palette.safe
                GroupStatus.SAFE_AREA -> palette.primary
                GroupStatus.RISK_ZONE -> palette.danger
            }
            pointFeature(
                group.point,
                group.id,
                "color" to color,
                "stroke" to palette.casing,
                "textColor" to palette.onStatus,
                "radius" to groupRadiusDp(group.size),
                "label" to (if (group.size > MAX_LABEL) "$MAX_LABEL+" else group.size.toString()),
            )
        },
    )

    fun groupRadiusDp(size: Int): Double =
        (MIN_GROUP_RADIUS + GROUP_RADIUS_STEP * sqrt(size.coerceIn(1, 100).toDouble())).coerceAtMost(MAX_GROUP_RADIUS)

    // ------------------------------------------------------------------ my location

    /** Accuracy circle; empty for a fix with no usable accuracy. */
    fun myAccuracy(fix: LocationFix?, palette: MapPalette): String {
        if (fix == null || fix.accuracyM <= 0f || fix.accuracyM.isNaN()) return collection(emptyList())
        val radius = fix.accuracyM.toDouble().coerceAtMost(MAX_ACCURACY_M)
        return collection(listOf(polygonFeature(listOf(GeoCircle.ring(fix.point, radius)), "me-accuracy", "color" to palette.info)))
    }

    fun myDot(fix: LocationFix?, palette: MapPalette): String = collection(
        listOfNotNull(fix?.let { pointFeature(it.point, "me", "color" to palette.info, "casing" to palette.casing) }),
    )

    // ------------------------------------------------------------------ building blocks

    private fun collection(features: List<JsonObject?>): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features.filterNotNull()))
    }.toString()

    private fun feature(geometry: JsonObject, id: String, vararg props: Pair<String, Any>): JsonObject = buildJsonObject {
        put("type", "Feature")
        put("geometry", geometry)
        put("properties", buildJsonObject {
            put(PROP_ID, id)
            props.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Boolean -> put(key, value)
                    is Number -> put(key, value)
                    else -> error("Unsupported property type for $key")
                }
            }
        })
    }

    private fun pointFeature(point: GeoPoint, id: String, vararg props: Pair<String, Any>): JsonObject =
        feature(
            buildJsonObject {
                put("type", "Point")
                put("coordinates", position(point))
            },
            id, *props,
        )

    /** Rings are closed if needed. Returns null (feature skipped) when no ring has 3 distinct points. */
    private fun polygonFeature(rings: List<List<GeoPoint>>, id: String, vararg props: Pair<String, Any>): JsonObject? {
        val usable = rings.map(::closed).filter { it.size >= MIN_RING_POINTS }
        if (usable.isEmpty()) return null
        return feature(
            buildJsonObject {
                put("type", "Polygon")
                put("coordinates", JsonArray(usable.map { coordinates(it) }))
            },
            id, *props,
        )
    }

    private fun closed(ring: List<GeoPoint>): List<GeoPoint> =
        if (ring.size >= 2 && ring.first() != ring.last()) ring + ring.first() else ring

    private fun coordinates(points: List<GeoPoint>): JsonArray = buildJsonArray { points.forEach { add(position(it)) } }

    /** GeoJSON order is [longitude, latitude]. */
    private fun position(p: GeoPoint): JsonArray = buildJsonArray {
        add(JsonPrimitive(p.lon))
        add(JsonPrimitive(p.lat))
    }

    private const val MIN_RING_POINTS = 4            // 3 distinct points + the closing point
    private const val MAX_ALERT_RADIUS_M = 200_000.0
    private const val MAX_ACCURACY_M = 2_000.0
    private const val MAX_LABEL = 99
    private const val MIN_GROUP_RADIUS = 12.0
    private const val GROUP_RADIUS_STEP = 3.0
    private const val MAX_GROUP_RADIUS = 36.0
}
