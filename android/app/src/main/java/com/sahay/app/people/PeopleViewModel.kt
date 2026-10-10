package com.sahay.app.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.app.common.distanceMeters
import com.sahay.core.contracts.GroupService
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.PeopleGroup
import com.sahay.core.contracts.ProfileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How often the list refreshes while the screen is visible (CONTRACTS §0). */
const val GROUP_POLL_INTERVAL_MS = 2 * 60 * 1000L

private const val DEFAULT_MIN_GROUP_SIZE = 5

data class GroupRow(val group: PeopleGroup, val distanceM: Double?)

data class PeopleUiState(
    val optedIn: Boolean = false,
    val enableFailed: Boolean = false,
    val rows: List<GroupRow> = emptyList(),
    val minGroupSize: Int = DEFAULT_MIN_GROUP_SIZE,
    val updatedAtEpochSec: Long? = null,
    val isStale: Boolean = false,
    val nearbyPhones: Int = 0,
    val refreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val noFix: Boolean = false,
)

private data class RefreshState(
    val refreshing: Boolean = false,
    val failed: Boolean = false,
    val noFix: Boolean = false,
    val enableFailed: Boolean = false,
)

@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val groups: GroupService,
    private val location: LocationProvider,
    private val profiles: ProfileStore,
) : ViewModel() {

    private val refresh = MutableStateFlow(RefreshState())

    val state: StateFlow<PeopleUiState> = combine(
        groups.snapshot, profiles.profile, location.lastFix, refresh,
    ) { snapshot, profile, fix, refreshState ->
        PeopleUiState(
            optedIn = profile?.groupFinderOptIn == true,
            enableFailed = refreshState.enableFailed,
            rows = snapshot.groups
                .map { GroupRow(it, fix?.let { f -> distanceMeters(f.point, it.point) }) }
                .sortedBy { it.distanceM ?: Double.MAX_VALUE },
            minGroupSize = snapshot.minGroupSize.takeIf { it > 0 } ?: DEFAULT_MIN_GROUP_SIZE,
            updatedAtEpochSec = snapshot.updatedAtEpochSec,
            isStale = snapshot.isStale,
            nearbyPhones = snapshot.nearbyAppUsers,
            refreshing = refreshState.refreshing,
            refreshFailed = refreshState.failed,
            noFix = refreshState.noFix,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleUiState())

    /** Refreshes now and then every [GROUP_POLL_INTERVAL_MS]. The screen runs this only while visible. */
    suspend fun pollWhileVisible() {
        while (true) {
            refreshOnce()
            delay(GROUP_POLL_INTERVAL_MS)
        }
    }

    fun refreshNow() {
        viewModelScope.launch { refreshOnce() }
    }

    /** Saves the opt-in to the profile; the list then starts loading because the screen starts polling. */
    fun turnOn() {
        viewModelScope.launch {
            val profile = profiles.profile.value
            val saved = profile != null && try {
                profiles.save(profile.copy(groupFinderOptIn = true))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            refresh.update { it.copy(enableFailed = !saved) }
        }
    }

    internal suspend fun refreshOnce() {
        if (refresh.value.refreshing) return
        refresh.update { it.copy(refreshing = true) }
        try {
            val fix = location.lastFix.value ?: location.currentFix()
            if (fix == null) {
                refresh.update { it.copy(noFix = true) }
                return
            }
            val ok = try {
                groups.refresh(fix.point).isSuccess
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            refresh.update { it.copy(noFix = false, failed = !ok) }
        } finally {
            // Also runs when the screen leaves mid-refresh, so the next visit is not blocked.
            refresh.update { it.copy(refreshing = false) }
        }
    }
}
