package com.sahay.engine.map

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Style used when there is no trip pack (or its map style cannot load): a plain background in the app's
 * background colour, so overlays such as the route and "my location" still show and nothing crashes.
 */
internal object MapStyleJson {
    /**
     * `glyphs` points at the OpenFreeMap fonts so group counts can be drawn; if those fonts are not in the
     * offline cache the labels are simply missing, the circles still show.
     */
    private const val GLYPHS_URL = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"

    fun background(colorHex: String): String = buildJsonObject {
        put("version", 8)
        put("name", "sahay-background")
        put("glyphs", GLYPHS_URL)
        putJsonObject("sources") {}
        putJsonArray("layers") {
            add(
                buildJsonObject {
                    put("id", "background")
                    put("type", "background")
                    putJsonObject("paint") { put("background-color", colorHex) }
                },
            )
        }
    }.toString()
}
