package com.sahay.engine.map

/**
 * Overlay colours as `#RRGGBB` strings, copied from docs/DESIGN.md §2 (the engine may not depend on the design
 * system module). Every overlay feature carries its colours as properties, so a theme change only means
 * pushing new GeoJSON; no layer has to be rebuilt.
 */
internal data class MapPalette(
    val primary: String,
    val safe: String,
    val watch: String,
    val warning: String,
    val danger: String,
    val info: String,
    /** Outline around the route and markers: white on the light map, black on dark maps. */
    val casing: String,
    /** Text and glyphs drawn on top of a filled status colour. */
    val onStatus: String,
    /** Greyed-out markers, e.g. shelters that are full or closed. */
    val disabled: String,
    /** Unconfirmed reports and other "not sure" things. */
    val muted: String,
) {
    /** Severity 0 info, 1 watch, 2 warning, 3 emergency (docs/CONTRACTS.md §4). */
    fun severity(level: Int): String = when {
        level >= 3 -> danger
        level == 2 -> warning
        level == 1 -> watch
        else -> info
    }

    companion object {
        val LIGHT = MapPalette(
            primary = "#0B6E6E", safe = "#18794C", watch = "#9A6700", warning = "#C2410C", danger = "#C62828",
            info = "#2457C5", casing = "#FFFFFF", onStatus = "#FFFFFF", disabled = "#8A9895", muted = "#4A5A57",
        )
        val DARK = MapPalette(
            primary = "#5FD3CA", safe = "#4ADE95", watch = "#F2C14E", warning = "#FF9F5A", danger = "#FF6B6B",
            info = "#86ABFF", casing = "#000000", onStatus = "#0D1413", disabled = "#6B7A77", muted = "#A3B3B0",
        )
        /** Emergency theme: pure black UI, brighter status colours. */
        val EMERGENCY = DARK.copy(primary = "#64D2FF", safe = "#30D158", danger = "#FF453A", muted = "#BDBDBD")

        fun forTheme(dark: Boolean, emergency: Boolean): MapPalette = when {
            emergency -> EMERGENCY
            dark -> DARK
            else -> LIGHT
        }
    }
}
