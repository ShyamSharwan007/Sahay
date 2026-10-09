package com.sahay.engine.pack

import com.sahay.core.contracts.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads the `risk_zone.geojson` column (a GeoJSON Polygon or MultiPolygon geometry). */
internal object GeoJsonPolygons {
    private const val MIN_RING_POINTS = 3

    /**
     * Outer rings only (holes are ignored), as [GeoPoint]s. GeoJSON positions are `[lon, lat]`.
     * Rings with fewer than 3 points are dropped. Rings are returned as stored (closing point kept).
     * @throws IllegalArgumentException if the text is not a valid Polygon/MultiPolygon (a Feature wrapper is accepted).
     */
    fun outerRings(geojson: String): List<List<GeoPoint>> {
        val root = Json.parseToJsonElement(geojson).jsonObject
        val geometry = if (root.type() == "Feature") root["geometry"]?.jsonObject ?: bad("Feature without geometry") else root
        val coordinates = geometry["coordinates"]?.jsonArray ?: bad("missing coordinates")
        val polygons: List<JsonArray> = when (geometry.type()) {
            "Polygon" -> listOf(coordinates)
            "MultiPolygon" -> coordinates.map { it.jsonArray }
            else -> bad("unsupported geometry type '${geometry.type()}'")
        }
        return polygons.mapNotNull { polygon ->
            val outer = polygon.firstOrNull()?.jsonArray ?: return@mapNotNull null
            outer.map(::toPoint).takeIf { it.size >= MIN_RING_POINTS }
        }
    }

    private fun JsonObject.type(): String? = (this["type"] as? JsonPrimitive)?.content

    private fun toPoint(position: JsonElement): GeoPoint {
        val values = position.jsonArray
        if (values.size < 2) bad("position needs lon and lat")
        val lon = values[0].jsonPrimitive.doubleOrNull ?: bad("longitude is not a number")
        val lat = values[1].jsonPrimitive.doubleOrNull ?: bad("latitude is not a number")
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) bad("position out of range: [$lon, $lat]")
        return GeoPoint(lat, lon)
    }

    private fun bad(message: String): Nothing = throw IllegalArgumentException("Invalid GeoJSON: $message")
}
