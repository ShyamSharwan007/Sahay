package com.sahay.engine.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.LocationMode
import com.sahay.core.contracts.LocationProvider
import com.sahay.engine.state.EngineStateStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** How often and how precisely the system is asked for a position in one [LocationMode]. */
internal data class LocationRequestSettings(val priority: Int, val intervalMs: Long) {
    companion object {
        fun forMode(mode: LocationMode): LocationRequestSettings = when (mode) {
            LocationMode.LOW_POWER -> LocationRequestSettings(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L)
            LocationMode.BALANCED -> LocationRequestSettings(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000L)
            LocationMode.NAVIGATION -> LocationRequestSettings(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
        }
    }
}

/**
 * Location from Google's fused provider. Every failure (no permission, location switched off, no Google
 * Play services) ends up as "no fix": an empty flow or null, never a crash.
 *
 * The newest fix is kept in [lastFix] and saved to disk (at most every [PERSIST_INTERVAL_MS]) so SOS still
 * has a position right after the app restarts.
 */
@Singleton
class RealLocationProvider internal constructor(
    private val context: Context,
    private val client: FusedLocationProviderClient,
    private val store: EngineStateStore,
    private val scope: CoroutineScope,
) : LocationProvider {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        client = LocationServices.getFusedLocationProviderClient(context),
        store = EngineStateStore(context),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val lastFixState = MutableStateFlow<LocationFix?>(null)
    override val lastFix: StateFlow<LocationFix?> = lastFixState.asStateFlow()

    @Volatile private var lastPersistedAtMs = 0L

    init {
        // Load the saved fix, but never replace a fresher one that arrived while we were reading.
        scope.launch { store.readLastFix()?.let { saved -> lastFixState.update { it ?: saved } } }
    }

    override fun hasPermission(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * Fixes at the rate of [mode]; the system request is removed when the collector stops. Completes without
     * emitting when permission is missing or the provider fails. While location is merely switched off the
     * flow stays open and starts emitting once the user turns it back on.
     */
    override fun updates(mode: LocationMode): Flow<LocationFix> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val settings = LocationRequestSettings.forMode(mode)
        val request = LocationRequest.Builder(settings.priority, settings.intervalMs)
            .setMinUpdateIntervalMillis(settings.intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.toFixOrNull()?.let { fix ->
                    remember(fix)
                    trySend(fix)
                }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { e ->
                    Log.w(TAG, "Location updates unavailable: ${e.message}")
                    close()
                }
        } catch (e: SecurityException) {           // permission revoked between the check and the request
            Log.w(TAG, "Location permission lost: ${e.message}")
            close()
            return@callbackFlow
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }.conflate()       // a slow collector only ever needs the newest position

    /** A fresh high-accuracy fix, or [lastFix] when none arrives within [timeoutMs]. Null without permission. */
    override suspend fun currentFix(timeoutMs: Long): LocationFix? {
        if (!hasPermission()) return null
        if (timeoutMs <= 0) return lastFixState.value
        val fresh = try {
            withTimeoutOrNull(timeoutMs) { requestCurrentLocation(timeoutMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {            // SecurityException, ApiException (no Play services), ...
            Log.w(TAG, "Current location unavailable: ${e.message}")
            null
        }
        return fresh?.also(::remember) ?: lastFixState.value
    }

    @OptIn(ExperimentalCoroutinesApi::class)      // await(CancellationTokenSource) is still marked experimental
    private suspend fun requestCurrentLocation(timeoutMs: Long): LocationFix? {
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(timeoutMs)
            .setMaxUpdateAgeMillis(MAX_CACHED_FIX_AGE_MS)
            .build()
        val cancellation = CancellationTokenSource()       // cancelled by await() if this coroutine is cancelled
        return client.getCurrentLocation(request, cancellation.token).await(cancellation)?.toFixOrNull()
    }

    private fun remember(fix: LocationFix) {
        lastFixState.value = fix
        if (fix.timeMs - lastPersistedAtMs < PERSIST_INTERVAL_MS) return
        lastPersistedAtMs = fix.timeMs
        scope.launch { store.writeLastFix(fix) }
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.toFixOrNull(): LocationFix? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        return LocationFix(
            point = GeoPoint(latitude, longitude),
            accuracyM = if (hasAccuracy()) accuracy else UNKNOWN_ACCURACY_M,
            timeMs = if (time > 0) time else System.currentTimeMillis(),
        )
    }

    private companion object {
        const val TAG = "RealLocationProvider"
        const val PERSIST_INTERVAL_MS = 30_000L
        const val MAX_CACHED_FIX_AGE_MS = 10_000L
        const val UNKNOWN_ACCURACY_M = 1_000f
    }
}
