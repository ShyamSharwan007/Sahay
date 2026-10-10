package com.sahay.engine.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.LocationMode
import com.sahay.core.contracts.LocationProvider
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge

/**
 * Camera-follow state that screens can observe. [SahayMap] stops following the user's location as soon as the
 * user pans the map; a screen can then show a "Centre on me" button when [isFollowing] is false and call
 * [resumeFollowing] from it. Provide your own with [LocalMapCameraState]; otherwise the map keeps a private one.
 *
 * Setting `MapViewState.followUser` from false to true also resumes following.
 *
 * [recenter] is the "Centre on me" action: it animates to the user at [RECENTER_ZOOM] and turns following back on.
 * While no position is known yet, [isWaitingForFix] is true so the screen can say "Waiting for your location…".
 */
@Stable
class MapCameraState(private val location: LocationProvider? = null) {
    /** True while the camera is really following the user (asked for by the state, and not panned away). */
    var isFollowing: Boolean by mutableStateOf(false)
        internal set

    internal var userPanned: Boolean by mutableStateOf(false)

    /** True from a [recenter] call until the first position arrives. */
    var isWaitingForFix: Boolean by mutableStateOf(false)
        internal set

    /** Set by [recenter] once a position is known; [SahayMap] animates the camera there. */
    internal var recenterRequest: RecenterRequest? by mutableStateOf(null)

    private var requestCount = 0

    fun resumeFollowing() {
        userPanned = false
    }

    /**
     * Turns following on and moves to the newest position: [LocationProvider.lastFix], else a fresh fix (waits up to
     * [FIX_TIMEOUT_MS]), else the first one that arrives later. Suspends while waiting; cancel it by leaving the screen.
     */
    suspend fun recenter() {
        resumeFollowing()
        val provider = location ?: return
        val fix = try {
            provider.lastFix.value ?: provider.currentFix(FIX_TIMEOUT_MS) ?: run {
                isWaitingForFix = true
                awaitFirstFix(provider)
            }
        } finally {
            isWaitingForFix = false
        }
        recenterRequest = RecenterRequest(fix, ++requestCount)
    }

    private suspend fun awaitFirstFix(provider: LocationProvider): LocationFix =
        merge(provider.updates(LocationMode.BALANCED), provider.lastFix.filterNotNull()).first()

    companion object {
        const val RECENTER_ZOOM = 16.0
        const val FIX_TIMEOUT_MS = 5_000L
    }
}

/** One "centre on me" request; [id] makes two requests for the same place distinct. */
internal data class RecenterRequest(val fix: LocationFix, val id: Int)

/** Remembers a [MapCameraState] that can reach the location provider through Hilt (a plain one where there is none). */
@Composable
fun rememberMapCameraState(): MapCameraState {
    val context = LocalContext.current
    return remember(context) { MapCameraState(SahayMapEntryPoint.from(context)?.locationProvider()) }
}

/** Optional: a screen provides its own [MapCameraState] to read [MapCameraState.isFollowing]. */
val LocalMapCameraState = compositionLocalOf<MapCameraState?> { null }

/** Lets through at most one camera move per [minIntervalMs] so a 1 Hz GPS does not restart the animation. */
internal class FollowThrottle(private val minIntervalMs: Long = DEFAULT_INTERVAL_MS) {
    private var lastMoveMs: Long? = null

    /** True (and remembers [nowMs]) if enough time passed since the last accepted move. */
    fun tryAcquire(nowMs: Long): Boolean {
        val last = lastMoveMs
        if (last != null && nowMs - last in 0 until minIntervalMs) return false
        lastMoveMs = nowMs
        return true
    }

    /** The next [tryAcquire] succeeds, e.g. right after following resumes. */
    fun reset() {
        lastMoveMs = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 2_000L
    }
}
