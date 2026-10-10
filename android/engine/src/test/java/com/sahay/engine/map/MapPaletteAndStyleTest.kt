package com.sahay.engine.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPaletteAndStyleTest {

    private val hex = Regex("#[0-9A-Fa-f]{6}")

    @Test fun `all palette colours are valid hex`() {
        listOf(MapPalette.LIGHT, MapPalette.DARK, MapPalette.EMERGENCY).forEach { p ->
            listOf(p.primary, p.safe, p.watch, p.warning, p.danger, p.info, p.casing, p.onStatus, p.disabled, p.muted)
                .forEach { assertTrue(it, hex.matches(it)) }
        }
    }

    @Test fun `theme picks the palette, emergency wins`() {
        assertSame(MapPalette.LIGHT, MapPalette.forTheme(dark = false, emergency = false))
        assertSame(MapPalette.DARK, MapPalette.forTheme(dark = true, emergency = false))
        assertSame(MapPalette.EMERGENCY, MapPalette.forTheme(dark = true, emergency = true))
        assertSame(MapPalette.EMERGENCY, MapPalette.forTheme(dark = false, emergency = true))
    }

    @Test fun `design tokens`() {
        assertEquals("#C62828", MapPalette.LIGHT.danger)
        assertEquals("#FF6B6B", MapPalette.DARK.danger)
        assertEquals("#FF453A", MapPalette.EMERGENCY.danger)
        assertEquals("#FFFFFF", MapPalette.LIGHT.casing)     // route casing: white on the light map
        assertEquals("#000000", MapPalette.DARK.casing)      // black on dark maps
    }

    @Test fun `severity colours`() {
        val p = MapPalette.LIGHT
        assertEquals(p.info, p.severity(0))
        assertEquals(p.watch, p.severity(1))
        assertEquals(p.warning, p.severity(2))
        assertEquals(p.danger, p.severity(3))
        assertEquals(p.danger, p.severity(9))
        assertEquals(p.info, p.severity(-1))
    }

    @Test fun `background style is a valid MapLibre style with the given colour`() {
        val style = Json.parseToJsonElement(MapStyleJson.background("#F6F8F7")).jsonObject
        assertEquals("8", style.getValue("version").jsonPrimitive.content)
        val layer = style.getValue("layers").jsonArray.single().jsonObject
        assertEquals("background", layer.getValue("type").jsonPrimitive.content)
        assertEquals("#F6F8F7", layer.getValue("paint").jsonObject.getValue("background-color").jsonPrimitive.content)
        assertTrue(style.getValue("glyphs").jsonPrimitive.content.contains("{fontstack}"))
        assertTrue(style.getValue("sources").jsonObject.isEmpty())
    }
}
