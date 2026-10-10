package com.sahay.engine.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Camera-follow state that screens can observe. [SahayMap] stops following the user's location as soon as the
 * user pans the map; a screen can then show a "Centre on me" button when [isFollowing] is false and call
 * [resumeFollowing] from it. Provide your own with [LocalMapCameraState]; otherwise the map keeps a private one.
 *
 * Setting `MapViewState.followUser` from false to true also resumes following.
 */
@Stable
class MapCameraState {
    /** True while the camera is really following the user (asked for by the state, and not panned away). */
    var isFollowing: Boolean by mutableStateOf(false)
        internal set

    internal var userPanned: Boolean by mutableStateOf(false)

    fun resumeFollowing() {
        userPanned = false
    }
}

@Composable
fun rememberMapCameraState(): MapCameraState = remember { MapCameraState() }

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
