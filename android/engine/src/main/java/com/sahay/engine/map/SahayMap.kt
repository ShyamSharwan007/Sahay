package com.sahay.engine.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.MapViewState
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.PeopleGroup
import com.sahay.core.contracts.Poi
import com.sahay.engine.R
import com.sahay.engine.pack.MapStyles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Sahay map (MapLibre). Signature fixed by docs/CONTRACTS.md §2.
 *
 *  - Style: the OpenFreeMap style the trip pack downloaded (light/dark by [MapViewState.darkStyle]; served from the
 *    offline cache). Without an active pack, or if that style cannot load, a plain background in the theme colour.
 *  - Overlays (risk zones, alerts, route, places, reports, groups, my location) are GeoJSON layers that are updated
 *    in place when [state] changes.
 *  - The camera starts on the pack area, and follows [MapViewState.myLocation] while [MapViewState.followUser] is on,
 *    until the user pans (see [MapCameraState] / [LocalMapCameraState]).
 */
@Composable
fun SahayMap(
    state: MapViewState,
    modifier: Modifier = Modifier,
    onPoiClick: (Poi) -> Unit = {},
    onReportClick: (HazardReport) -> Unit = {},
    onGroupClick: (PeopleGroup) -> Unit = {},
    onLongPress: (GeoPoint) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Pack + emergency mode come from Hilt (the signature has no room for them).
    val services = remember(context) { SahayMapEntryPoint.from(context) }
    val noPack = remember { MutableStateFlow<PackInfo?>(null) }
    val notEmergency = remember { MutableStateFlow(false) }
    val pack by (services?.packRepository()?.activePack ?: noPack as StateFlow<PackInfo?>).collectAsState()
    val emergency by (services?.emergencyModeController()?.isActive ?: notEmergency as StateFlow<Boolean>).collectAsState()

    val palette = MapPalette.forTheme(state.darkStyle, emergency)
    val backgroundHex = MaterialTheme.colorScheme.background.toHex()
    val cameraState = LocalMapCameraState.current ?: rememberMapCameraState()

    val currentState by rememberUpdatedState(state)
    val currentOnPoi by rememberUpdatedState(onPoiClick)
    val currentOnReport by rememberUpdatedState(onReportClick)
    val currentOnGroup by rememberUpdatedState(onGroupClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    val controller = remember {
        SahayMapController(
            context = context,
            stateProvider = { currentState },
            callbacks = MapCallbacks(
                onPoiClick = { currentOnPoi(it) },
                onReportClick = { currentOnReport(it) },
                onGroupClick = { currentOnGroup(it) },
                onLongPress = { currentOnLongPress(it) },
                onUserPanned = { cameraState.userPanned = true },
            ),
        )
    }

    // MapView lifecycle: follow the host, catch up if we enter late, and end it when we leave.
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { source, _ -> controller.lifecycle.moveTo(source.lifecycle.currentState) }
        lifecycleOwner.lifecycle.addObserver(observer)
        controller.lifecycle.moveTo(lifecycleOwner.lifecycle.currentState)
        val callbacks = object : ComponentCallbacks2 {
            override fun onLowMemory() = controller.mapView.onLowMemory()
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) controller.mapView.onLowMemory()
            }
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        context.registerComponentCallbacks(callbacks)
        onDispose {
            context.unregisterComponentCallbacks(callbacks)
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
        }
    }

    // Style: the downloaded OpenFreeMap style, or a plain background. Switched in place, never by recreating the view.
    val map = controller.map
    val styleUri = MapStyles.url(state.darkStyle).takeIf { pack != null && it != controller.failedStyleUri }
    LaunchedEffect(map, styleUri, backgroundHex) { controller.applyStyle(StyleSpec(styleUri, backgroundHex)) }

    // Overlays: cheap, diffed inside.
    SideEffect { controller.render(state, palette) }

    // Camera.
    val following = state.followUser && !cameraState.userPanned
    SideEffect { cameraState.isFollowing = following }
    LaunchedEffect(state.followUser) { if (state.followUser) cameraState.resumeFollowing() }
    LaunchedEffect(map, following) { if (following) controller.resetFollowThrottle() }
    LaunchedEffect(map, following, state.myLocation, state.zoom) {
        val fix = state.myLocation
        if (following && fix != null) controller.follow(fix, state.zoom)
    }
    val bbox = pack?.bbox
    LaunchedEffect(map, bbox) {
        // Start on the pack area, unless the screen picked a centre or we are already on the user.
        val onUser = state.followUser && state.myLocation != null
        if (map != null && bbox != null && state.center == null && !onUser) controller.fitBounds(bbox)
    }
    LaunchedEffect(map, state.center, state.zoom) {
        val center = state.center
        // A centre equal to the user's own position means "follow me", not "show this place": don't undo a pan for it.
        if (center != null && !following && center != state.myLocation?.point) controller.showCenter(center, state.zoom)
    }
    // "Centre on me": declared last so its animation wins over the follow animation started in the same frame.
    val recenterRequest = cameraState.recenterRequest
    LaunchedEffect(map, recenterRequest) {
        if (recenterRequest != null) controller.recenter(recenterRequest.fix, MapCameraState.RECENTER_ZOOM)
    }

    val mapDescription = stringResource(R.string.engine_map_content_description)
    Box(modifier.semantics { contentDescription = mapDescription }) {
        AndroidView(factory = { controller.mapView }, modifier = Modifier.fillMaxSize())
        MapAttribution(Modifier.align(Alignment.BottomStart).padding(4.dp))
    }
}

/** OpenStreetMap and OpenFreeMap require visible credit; bottom-start so it mirrors in RTL. */
@Composable
private fun MapAttribution(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.background.copy(alpha = ATTRIBUTION_BACKGROUND_ALPHA),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Text(
            text = stringResource(R.string.engine_map_attribution),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private const val ATTRIBUTION_BACKGROUND_ALPHA = 0.8f

private fun Color.toHex(): String = String.format("#%06X", toArgb() and 0xFFFFFF)
