package com.sahay.engine.risk

import android.content.Context
import android.util.Log
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.LocationMode
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.RiskMonitor
import com.sahay.core.contracts.RiskZone
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Follows the user's position and exposes the risk zone they stand in ([currentZone]).
 *
 * Monitoring runs between [startMonitoring] and [stopMonitoring] (the app calls it while its screens are
 * open; Emergency Mode keeps it running in the background). There is no geofencing and no background
 * location permission: it is plain location updates, so it only works while the app process is alive.
 */
@Singleton
class RealRiskMonitor internal constructor(
    private val location: LocationProvider,
    private val zones: RiskZoneCache,
    private val notifier: RiskNotifier,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long,
    private val retryDelayMs: Long,
) : RiskMonitor {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        location: LocationProvider,
        packs: PackRepository,
    ) : this(
        location = location,
        zones = RiskZoneCache(packs),
        notifier = AndroidRiskNotifier(context),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        clockMs = System::currentTimeMillis,
        retryDelayMs = RETRY_DELAY_MS,
    )

    private val zoneState = MutableStateFlow<RiskZone?>(null)
    override val currentZone: StateFlow<RiskZone?> = zoneState.asStateFlow()

    private val entryDetector = HighZoneEntryDetector()
    private val jobLock = Any()
    private var job: Job? = null

    override suspend fun zoneAt(point: GeoPoint): RiskZone? = zones.index().zoneAt(point)

    override fun startMonitoring() {
        synchronized(jobLock) {
            if (job?.isActive == true) return
            job = scope.launch { monitorLoop() }
        }
    }

    override fun stopMonitoring() {
        synchronized(jobLock) {
            job?.cancel()
            job = null
        }
        zoneState.value = null      // an unwatched zone would go stale; the next fix after a restart sets it again
    }

    /**
     * Collects location updates. The flow ends at once when permission is missing, so we wait and try again:
     * the user may grant it while the app is open.
     */
    private suspend fun monitorLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                location.updates(LocationMode.BALANCED).collect { fix -> onFix(fix) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Location monitoring interrupted: ${e.message}")
            }
            delay(retryDelayMs)
        }
    }

    private suspend fun onFix(fix: LocationFix) {
        try {
            val zone = zones.index().zoneAt(fix.point)
            zoneState.value = zone
            // A rough fix (e.g. approximate location) could be streets away, so it never triggers a notification.
            if (fix.accuracyM <= MAX_NOTIFY_ACCURACY_M && entryDetector.onObservation(zone, clockMs())) {
                notifier.notifyEnteringHighZone()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not check the risk zone: ${e.message}")
        }
    }

    internal companion object {
        private const val TAG = "RealRiskMonitor"
        const val RETRY_DELAY_MS = 30_000L
        const val MAX_NOTIFY_ACCURACY_M = 200f
    }
}
