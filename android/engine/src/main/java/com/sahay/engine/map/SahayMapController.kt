package com.sahay.engine.map

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.doOnLayout
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.MapViewState
import com.sahay.core.contracts.PeopleGroup
import com.sahay.core.contracts.Poi
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** What the map reports back to the screen. */
internal class MapCallbacks(
    val onPoiClick: (Poi) -> Unit,
    val onReportClick: (HazardReport) -> Unit,
    val onGroupClick: (PeopleGroup) -> Unit,
    val onLongPress: (GeoPoint) -> Unit,
    val onUserPanned: () -> Unit,
)

/** Which style to show: the OpenFreeMap style at [uri], or (uri == null) a plain background. */
internal data class StyleSpec(val uri: String?, val backgroundHex: String)

/**
 * Owns the MapView and everything that talks to MapLibre: style switching without recreating the view,
 * the overlay renderer, camera moves and click handling. [SahayMap] is the thin Compose layer around it.
 * Must be used on the main thread.
 */
internal class SahayMapController(
    context: Context,
    private val stateProvider: () -> MapViewState,
    private val callbacks: MapCallbacks,
    private val throttle: FollowThrottle = FollowThrottle(),
) {
    private val appContext = context.applicationContext

    val mapView: MapView = run {
        MapLibre.getInstance(appContext)        // must happen before the first MapView
        // TextureView mode: composes correctly inside Compose (no black flash, normal z-order).
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true))
    }

    /** Set once the native map is ready; Compose effects key on it. */
    var map: MapLibreMap? by mutableStateOf(null)
        private set

    /** The style URI that failed to load (e.g. offline without a downloaded region); we show the background instead. */
    var failedStyleUri: String? by mutableStateOf(null)
        private set

    val lifecycle = MapLifecycleBridge(MapViewTarget(mapView))

    private var renderer: MapOverlayRenderer? = null
    private var activeSpec: StyleSpec? = null
    private var styleGeneration = 0
    private var latestState: MapViewState? = null
    private var latestPalette: MapPalette = MapPalette.LIGHT
    private var released = false
    private var hasPositionedCamera = false
    private var hasFollowedOnce = false
    private var lastZoomRequest: Double? = null

    private val styleFailListener = MapView.OnDidFailLoadingMapListener { message ->
        val failed = activeSpec?.uri ?: return@OnDidFailLoadingMapListener
        Log.w(TAG, "Map style failed to load, using plain background: $message")
        failedStyleUri = failed
    }

    init {
        mapView.addOnDidFailLoadingMapListener(styleFailListener)
        mapView.getMapAsync { readyMap -> if (!released) onMapReady(readyMap) }
    }

    private fun onMapReady(readyMap: MapLibreMap) {
        readyMap.uiSettings.apply {
            isAttributionEnabled = false          // we draw our own text (SahayMap)
            isLogoEnabled = false
            isCompassEnabled = false
            isTiltGesturesEnabled = false
        }
        readyMap.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) callbacks.onUserPanned()
        }
        readyMap.addOnMapClickListener { latLng -> handleClick(readyMap, latLng) }
        readyMap.addOnMapLongClickListener { latLng ->
            callbacks.onLongPress(GeoPoint(latLng.latitude, latLng.longitude))
            true
        }
        map = readyMap
    }

    // ------------------------------------------------------------------ style + overlays

    /** Switches the style in place. Does nothing if [spec] is already showing. */
    fun applyStyle(spec: StyleSpec) {
        val currentMap = map ?: return
        if (spec == activeSpec) return
        activeSpec = spec
        val generation = ++styleGeneration
        renderer = null                                  // belongs to the old style
        val builder = if (spec.uri != null) {
            Style.Builder().fromUri(spec.uri)
        } else {
            Style.Builder().fromJson(MapStyleJson.background(spec.backgroundHex))
        }
        currentMap.setStyle(builder) { style ->
            if (generation != styleGeneration || released) return@setStyle     // a newer style request won
            renderer = MapOverlayRenderer(style, appContext, latestPalette).also { newRenderer ->
                latestState?.let { newRenderer.render(it, latestPalette) }
            }
        }
    }

    /** Remembers the newest state and draws it as soon as (and whenever) a style is loaded. */
    fun render(state: MapViewState, palette: MapPalette) {
        latestState = state
        latestPalette = palette
        renderer?.render(state, palette)
    }

    // ------------------------------------------------------------------ camera

    /** Shows the whole pack area, once the view has a size. */
    fun fitBounds(bbox: List<Double>) {
        val currentMap = map ?: return
        if (bbox.size != 4) return
        val (minLon, minLat, maxLon, maxLat) = bbox
        if (minLat >= maxLat || minLon >= maxLon) return
        val bounds = LatLngBounds.from(maxLat, maxLon, minLat, minLon)    // north, east, south, west
        mapView.doOnLayout {
            if (released) return@doOnLayout
            val padding = (BBOX_PADDING_DP * appContext.resources.displayMetrics.density).toInt()
            currentMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
            hasPositionedCamera = true
        }
    }

    /** Moves to an explicit centre (e.g. "show this shelter"). Jumps the first time, animates afterwards. */
    fun showCenter(center: GeoPoint, zoom: Double) {
        val currentMap = map ?: return
        val update = CameraUpdateFactory.newLatLngZoom(LatLng(center.lat, center.lon), zoom)
        if (hasPositionedCamera) currentMap.easeCamera(update, EASE_MS) else currentMap.moveCamera(update)
        hasPositionedCamera = true
    }

    /** Eases to [fix] at [zoom] and holds off [follow] for a moment so it cannot cut the animation short. */
    fun recenter(fix: LocationFix, zoom: Double) {
        val currentMap = map ?: return
        throttle.tryAcquire(SystemClock.elapsedRealtime())
        val update = CameraUpdateFactory.newLatLngZoom(LatLng(fix.point.lat, fix.point.lon), zoom)
        if (hasPositionedCamera) currentMap.easeCamera(update, EASE_MS) else currentMap.moveCamera(update)
        hasFollowedOnce = true
        hasPositionedCamera = true
    }

    /** Lets the next [follow] through immediately (called when following resumes). */
    fun resetFollowThrottle() = throttle.reset()

    /**
     * Eases to [fix], at most once per 2 s. The first follow also applies [requestedZoom]; later ones keep the
     * user's zoom, unless the screen asked for a different zoom since.
     */
    fun follow(fix: LocationFix, requestedZoom: Double) {
        val currentMap = map ?: return
        if (!throttle.tryAcquire(SystemClock.elapsedRealtime())) return
        val target = LatLng(fix.point.lat, fix.point.lon)
        val zoomChanged = lastZoomRequest != requestedZoom
        lastZoomRequest = requestedZoom
        val update = if (!hasFollowedOnce || zoomChanged) {
            CameraUpdateFactory.newLatLngZoom(target, requestedZoom)
        } else {
            CameraUpdateFactory.newLatLng(target)
        }
        if (hasFollowedOnce || hasPositionedCamera) currentMap.easeCamera(update, EASE_MS) else currentMap.moveCamera(update)
        hasFollowedOnce = true
        hasPositionedCamera = true
    }

    // ------------------------------------------------------------------ clicks

    /** Topmost tappable thing under the finger: place, then report, then group. Returns true if consumed. */
    private fun handleClick(currentMap: MapLibreMap, latLng: LatLng): Boolean {
        val state = latestState ?: stateProvider()
        val point = currentMap.projection.toScreenLocation(latLng)
        val slop = TAP_SLOP_DP * appContext.resources.displayMetrics.density
        val area = RectF(point.x - slop, point.y - slop, point.x + slop, point.y + slop)
        for (layer in OverlayIds.CLICKABLE_LAYERS) {
            val id = currentMap.queryRenderedFeatures(area, layer)
                .firstNotNullOfOrNull { feature -> feature.getStringProperty(OverlayGeoJson.PROP_ID) } ?: continue
            when (layer) {
                OverlayIds.LAYER_POIS -> state.pois.firstOrNull { it.id == id }?.let { callbacks.onPoiClick(it); return true }
                OverlayIds.LAYER_REPORTS -> state.reports.firstOrNull { it.id == id }?.let { callbacks.onReportClick(it); return true }
                else -> state.groups.firstOrNull { it.id == id }?.let { callbacks.onGroupClick(it); return true }
            }
        }
        return false
    }

    // ------------------------------------------------------------------ end of life

    fun release() {
        released = true
        mapView.removeOnDidFailLoadingMapListener(styleFailListener)
        renderer = null
        lifecycle.release()
    }

    private class MapViewTarget(private val view: MapView) : MapLifecycleTarget {
        override fun create() = view.onCreate(null)
        override fun start() = view.onStart()
        override fun resume() = view.onResume()
        override fun pause() = view.onPause()
        override fun stop() = view.onStop()
        override fun destroy() = view.onDestroy()
    }

    private companion object {
        const val TAG = "SahayMap"
        const val EASE_MS = 600
        const val BBOX_PADDING_DP = 24f
        const val TAP_SLOP_DP = 20f
    }
}
