package com.sahay.comms.groups

import android.util.Log
import com.sahay.comms.net.ApiException
import com.sahay.comms.net.GroupDto
import com.sahay.comms.net.GroupsApi
import com.sahay.comms.net.PresenceRequest
import com.sahay.comms.sms.GroupWireCache
import com.sahay.core.contracts.AuthTokenProvider
import com.sahay.core.contracts.Channel
import com.sahay.core.contracts.ConnectivityMonitor
import com.sahay.core.contracts.EmergencyModeController
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.GroupService
import com.sahay.core.contracts.GroupStatus
import com.sahay.core.contracts.GroupsSnapshot
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.PeopleGroup
import com.sahay.core.contracts.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong

/**
 * Groups of people nearby (docs/CONTRACTS.md §3, §7).
 *
 * - Presence: while Emergency Mode is on AND the user opted in to the group finder, the rounded position is sent
 *   to `POST /presence` every [PRESENCE_INTERVAL_MS]. A skipped or failed heartbeat is retried after [PRESENCE_RETRY_MS].
 * - [refresh] reads `GET /groups`; [snapshot] merges that with the groups received by SMS, so it still shows
 *   something offline. Age is re-evaluated every [TICK_MS], so a snapshot goes stale or loses a group by itself.
 * - Buddy beacons are cut in this build: [setBeacon] does nothing and [myBeaconActive] stays false.
 *
 * The presence loop starts when this singleton is first created, so something has to inject it at app start.
 */
@Singleton
class RealGroupService internal constructor(
    private val api: GroupsApi,
    private val auth: AuthTokenProvider,
    profileStore: ProfileStore,
    emergency: EmergencyModeController,
    private val location: LocationProvider,
    private val isOnline: () -> Boolean,
    groupCache: GroupWireCache,
    private val clock: Clock,
    scope: CoroutineScope,
    private val presenceIntervalMs: Long,
    private val presenceRetryMs: Long,
    tickMs: Long,
) : GroupService {

    @Inject constructor(
        api: GroupsApi,
        auth: AuthTokenProvider,
        profileStore: ProfileStore,
        emergency: EmergencyModeController,
        location: LocationProvider,
        connectivity: ConnectivityMonitor,
        groupCache: GroupWireCache,
    ) : this(
        api, auth, profileStore, emergency, location,
        { connectivity.state.value.internet },
        groupCache,
        Clock.systemUTC(),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        PRESENCE_INTERVAL_MS, PRESENCE_RETRY_MS, TICK_MS,
    )

    private val serverGroups = MutableStateFlow<ServerGroups?>(null)
    private val ticks = flow { while (true) { emit(Unit); delay(tickMs) } }

    override val snapshot: StateFlow<GroupsSnapshot> = combine(serverGroups, groupCache.groups, ticks) { server, sms, _ ->
        GroupSnapshotBuilder.build(server, sms, nowSec())
    }.stateIn(scope, SharingStarted.Eagerly, GroupSnapshotBuilder.build(null, emptyList(), nowSec()))

    override val myBeaconActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    init {
        scope.launch {
            val sharing = profileStore.profile.map { it?.groupFinderOptIn == true }
            combine(emergency.isActive, sharing) { active, optedIn -> active && optedIn }
                .distinctUntilChanged()
                .collectLatest { enabled -> if (enabled) heartbeatLoop() }   // cancelled the moment it turns off
        }
    }

    // ------------------------------------------------------------------ GroupService

    override suspend fun refresh(around: GeoPoint): Result<Unit> {
        if (!isOnline()) return Result.failure(IOException("Offline"))
        return try {
            val lat = round3(around.lat)
            val lon = round3(around.lon)
            val answer = fetchGroups(lat, lon)
            serverGroups.value = ServerGroups(
                groups = answer.groups.mapNotNull(::toPeopleGroup),
                minGroupSize = (answer.minSize ?: GroupSnapshotBuilder.DEFAULT_MIN_GROUP_SIZE).coerceAtLeast(1),
                fetchedAtEpochSec = nowSec(),
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Group refresh failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** Beacons are cut in this build. */
    override suspend fun setBeacon(active: Boolean, at: GeoPoint?) = Unit

    // ------------------------------------------------------------------ presence

    private suspend fun heartbeatLoop() {
        while (coroutineContext.isActive) {
            delay(if (sendPresence()) presenceIntervalMs else presenceRetryMs)
        }
    }

    /** True if the server took the heartbeat. Never throws except for cancellation. */
    private suspend fun sendPresence(): Boolean {
        if (!isOnline()) return false
        return try {
            val point = freshPosition() ?: return false
            val token = token() ?: return false          // signed out
            val request = PresenceRequest(round3(point.lat), round3(point.lon))
            try {
                api.postPresence(request, token)
            } catch (e: ApiException) {
                if (e.httpCode != HTTP_UNAUTHORIZED) throw e
                val fresh = token(forceRefresh = true)?.takeIf { it != token } ?: throw e
                api.postPresence(request, fresh)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Presence not sent: ${e.message}")
            false
        }
    }

    /** Last fix if it is young enough, else one quick attempt. An old position would put the user in the wrong group. */
    private suspend fun freshPosition(): GeoPoint? {
        val last = location.lastFix.value?.takeIf { clock.millis() - it.timeMs <= FIX_MAX_AGE_MS }
        return (last ?: location.currentFix(FIX_TIMEOUT_MS))?.point
    }

    // ------------------------------------------------------------------ helpers

    /** The login is optional for reading, so an expired token falls back to an anonymous read. */
    private suspend fun fetchGroups(lat: Double, lon: Double) = try {
        api.groups(lat, lon, SEARCH_RADIUS_M, token())
    } catch (e: ApiException) {
        if (e.httpCode != HTTP_UNAUTHORIZED) throw e
        api.groups(lat, lon, SEARCH_RADIUS_M, null)
    }

    private suspend fun token(forceRefresh: Boolean = false): String? = try {
        auth.idToken(forceRefresh)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No login token: ${e.message}")
        null
    }

    /** Drops anything the UI could not show sensibly (unknown status, empty group, bad coordinates). */
    private fun toPeopleGroup(dto: GroupDto): PeopleGroup? {
        val status = GroupStatus.entries.firstOrNull { it.name == dto.status } ?: return null
        val valid = dto.size >= 1 && dto.lastSeen > 0 && dto.lat.isFinite() && dto.lon.isFinite() &&
            dto.lat in -90.0..90.0 && dto.lon in -180.0..180.0
        if (!valid) return null
        return PeopleGroup(dto.id, GeoPoint(dto.lat, dto.lon), dto.size, status, dto.lastSeen, Channel.INTERNET)
    }

    private fun nowSec() = clock.instant().epochSecond

    internal companion object {
        private const val TAG = "GroupService"
        const val PRESENCE_INTERVAL_MS = 5 * 60_000L
        const val PRESENCE_RETRY_MS = 60_000L
        const val TICK_MS = 60_000L
        const val SEARCH_RADIUS_M = 3000
        const val FIX_MAX_AGE_MS = 10 * 60_000L
        const val FIX_TIMEOUT_MS = 10_000L
        private const val HTTP_UNAUTHORIZED = 401

        /** 3 decimals ≈ 110 m, the privacy level of docs/CONTRACTS.md §4. */
        fun round3(value: Double): Double = (value * 1000).roundToLong() / 1000.0
    }
}
