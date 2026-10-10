package com.sahay.engine.map

import android.content.Context
import com.sahay.core.contracts.MapViewState
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/** Ids of the overlay sources and layers; clicks query [CLICKABLE_LAYERS] in this priority order. */
internal object OverlayIds {
    const val RISK = "sahay-risk"
    const val ALERTS = "sahay-alerts"
    const val ME_ACCURACY = "sahay-me-accuracy"
    const val ROUTE = "sahay-route"
    const val GROUPS = "sahay-groups"
    const val REPORTS = "sahay-reports"
    const val POIS = "sahay-pois"
    const val ME_DOT = "sahay-me-dot"

    const val LAYER_POIS = "sahay-pois-symbols"
    const val LAYER_REPORTS = "sahay-reports-circles"
    const val LAYER_GROUPS = "sahay-groups-circles"

    val CLICKABLE_LAYERS = arrayOf(LAYER_POIS, LAYER_REPORTS, LAYER_GROUPS)
}

/**
 * Owns the overlay sources and layers of ONE loaded [Style]. Layers are added once in [init]; afterwards
 * [render] only calls `setGeoJson` on the sources whose input actually changed (diffing), so a 1 Hz location
 * update touches one small source and nothing else.
 *
 * Sizes are in dp: MapLibre scales style pixels by the screen density itself.
 */
internal class MapOverlayRenderer(private val style: Style, private val context: Context, initialPalette: MapPalette) {

    private val risk = DiffedSource<Pair<*, MapPalette>>(OverlayIds.RISK)
    private val alerts = DiffedSource<Pair<*, MapPalette>>(OverlayIds.ALERTS)
    private val meAccuracy = DiffedSource<Pair<*, MapPalette>>(OverlayIds.ME_ACCURACY)
    private val route = DiffedSource<Pair<*, MapPalette>>(OverlayIds.ROUTE)
    private val groups = DiffedSource<Pair<*, MapPalette>>(OverlayIds.GROUPS)
    private val reports = DiffedSource<Pair<*, MapPalette>>(OverlayIds.REPORTS)
    private val pois = DiffedSource<Any>(OverlayIds.POIS)
    private val meDot = DiffedSource<Pair<*, MapPalette>>(OverlayIds.ME_DOT)

    private var iconPalette: MapPalette? = null

    init {
        addSourcesAndLayers()
        updateIcons(initialPalette)
    }

    fun render(state: MapViewState, palette: MapPalette) {
        updateIcons(palette)
        risk.update(state.riskZones to palette) { OverlayGeoJson.riskZones(state.riskZones, palette) }
        alerts.update(state.alerts to palette) { OverlayGeoJson.alertCircles(state.alerts, palette) }
        meAccuracy.update(state.myLocation to palette) { OverlayGeoJson.myAccuracy(state.myLocation, palette) }
        route.update(state.route to palette) { OverlayGeoJson.route(state.route, palette) }
        groups.update(state.groups to palette) { OverlayGeoJson.groups(state.groups, palette) }
        reports.update(state.reports to palette) { OverlayGeoJson.reports(state.reports, palette) }
        pois.update(state.pois) { OverlayGeoJson.pois(state.pois) }
        meDot.update(state.myLocation to palette) { OverlayGeoJson.myDot(state.myLocation, palette) }
    }

    /** Marker bitmaps depend on the palette; re-add them only when it changes. */
    private fun updateIcons(palette: MapPalette) {
        if (iconPalette == palette) return
        MapIcons.build(context, palette).forEach { (id, bitmap) -> style.addImage(id, bitmap) }
        iconPalette = palette
    }

    // ------------------------------------------------------------------ one-time layer setup

    private fun addSourcesAndLayers() {
        listOf(
            OverlayIds.RISK, OverlayIds.ALERTS, OverlayIds.ME_ACCURACY, OverlayIds.ROUTE,
            OverlayIds.GROUPS, OverlayIds.REPORTS, OverlayIds.POIS, OverlayIds.ME_DOT,
        ).forEach { id ->
            val source = GeoJsonSource(id, EMPTY)
            style.addSource(source)
            sourceFor(id).bind(source)
        }

        // Bottom to top.
        style.addLayer(FillLayer("sahay-risk-fill", OverlayIds.RISK).withProperties(
            fillColor(get("color")), fillOpacity(get("fillOpacity")),
        ))
        style.addLayer(LineLayer("sahay-risk-line", OverlayIds.RISK).withProperties(
            lineColor(get("color")), lineWidth(get("lineWidth")),
        ))
        style.addLayer(FillLayer("sahay-alerts-fill", OverlayIds.ALERTS).withProperties(
            fillColor(get("color")), fillOpacity(ALERT_FILL_OPACITY),
        ))
        style.addLayer(LineLayer("sahay-alerts-line", OverlayIds.ALERTS).withProperties(
            lineColor(get("color")), lineWidth(ALERT_LINE_DP), lineOpacity(ALERT_LINE_OPACITY),
        ))
        style.addLayer(FillLayer("sahay-me-accuracy-fill", OverlayIds.ME_ACCURACY).withProperties(
            fillColor(get("color")), fillOpacity(ACCURACY_FILL_OPACITY),
        ))

        // Route: casing under the line (DESIGN §2: primary 6 px with a 2 px casing). A straight-line fallback is dashed.
        style.addLayer(LineLayer("sahay-route-casing", OverlayIds.ROUTE).withProperties(
            lineColor(get("casing")), lineWidth(ROUTE_DP + 2 * CASING_DP), lineCap("round"), lineJoin("round"),
        ))
        style.addLayer(LineLayer("sahay-route-line", OverlayIds.ROUTE).withProperties(
            lineColor(get("color")), lineWidth(ROUTE_DP), lineCap("round"), lineJoin("round"),
        ).also { it.setFilter(Expression.eq(get("dashed"), false)) })
        style.addLayer(LineLayer("sahay-route-line-dashed", OverlayIds.ROUTE).withProperties(
            lineColor(get("color")), lineWidth(ROUTE_DP), lineJoin("round"), lineDasharray(arrayOf(1.5f, 1.5f)),
        ).also { it.setFilter(Expression.eq(get("dashed"), true)) })

        style.addLayer(CircleLayer(OverlayIds.LAYER_GROUPS, OverlayIds.GROUPS).withProperties(
            circleRadius(get("radius")), circleColor(get("color")), circleOpacity(GROUP_OPACITY),
            circleStrokeColor(get("stroke")), circleStrokeWidth(2f),
        ))
        style.addLayer(SymbolLayer("sahay-groups-labels", OverlayIds.GROUPS).withProperties(
            textField(Expression.toString(get("label"))), textFont(arrayOf(LABEL_FONT)), textSize(LABEL_SP),
            textColor(get("textColor")), textAllowOverlap(true), textIgnorePlacement(true),
        ))
        style.addLayer(CircleLayer(OverlayIds.LAYER_REPORTS, OverlayIds.REPORTS).withProperties(
            circleRadius(REPORT_DP), circleColor(get("fill")), circleOpacity(get("fillOpacity")),
            circleStrokeColor(get("stroke")), circleStrokeWidth(REPORT_STROKE_DP), circleStrokeOpacity(1f),
        ))
        style.addLayer(SymbolLayer(OverlayIds.LAYER_POIS, OverlayIds.POIS).withProperties(
            iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true),
        ))

        // My location: halo, then dot with a casing ring.
        style.addLayer(CircleLayer("sahay-me-halo", OverlayIds.ME_DOT).withProperties(
            circleRadius(HALO_DP), circleColor(get("color")), circleOpacity(HALO_OPACITY),
        ))
        style.addLayer(CircleLayer("sahay-me-dot", OverlayIds.ME_DOT).withProperties(
            circleRadius(DOT_DP), circleColor(get("color")), circleStrokeColor(get("casing")), circleStrokeWidth(3f),
        ))
    }

    private fun sourceFor(id: String): DiffedSource<*> = when (id) {
        OverlayIds.RISK -> risk
        OverlayIds.ALERTS -> alerts
        OverlayIds.ME_ACCURACY -> meAccuracy
        OverlayIds.ROUTE -> route
        OverlayIds.GROUPS -> groups
        OverlayIds.REPORTS -> reports
        OverlayIds.POIS -> pois
        else -> meDot
    }

    /** Pushes GeoJSON into one source, but only when its input differs from the last push. */
    private class DiffedSource<T : Any>(val id: String) {
        private var source: GeoJsonSource? = null
        private var last: Any? = null

        fun bind(source: GeoJsonSource) {
            this.source = source
            last = null
        }

        fun update(input: T, toJson: () -> String) {
            if (input == last) return
            source?.setGeoJson(toJson())
            last = input
        }
    }

    private companion object {
        const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
        const val LABEL_FONT = "Noto Sans Bold"
        const val LABEL_SP = 14f
        const val ROUTE_DP = 6f
        const val CASING_DP = 2f
        const val ALERT_FILL_OPACITY = 0.15f
        const val ALERT_LINE_DP = 1.5f
        const val ALERT_LINE_OPACITY = 0.8f
        const val ACCURACY_FILL_OPACITY = 0.15f
        const val GROUP_OPACITY = 0.92f
        const val REPORT_DP = 9f
        const val REPORT_STROKE_DP = 3f
        const val HALO_DP = 14f
        const val HALO_OPACITY = 0.25f
        const val DOT_DP = 8f
    }
}
