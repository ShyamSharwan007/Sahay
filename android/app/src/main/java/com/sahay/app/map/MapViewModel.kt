package com.sahay.app.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.core.contracts.AlertRepository
import com.sahay.core.contracts.BuddyBeacon
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.GroupService
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.MapViewState
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.PeopleGroup
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.ReportRepository
import com.sahay.core.contracts.RiskZone
import com.sahay.core.contracts.SahayAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MapFilter { SHELTERS, HOSPITALS, POLICE, REPORTS, GROUPS, RISK_AREAS }

fun Set<MapFilter>.allows(type: PoiType): Boolean = when (type) {
    PoiType.SHELTER, PoiType.CANDIDATE_SHELTER -> MapFilter.SHELTERS in this
    PoiType.HOSPITAL -> MapFilter.HOSPITALS in this
    PoiType.POLICE -> MapFilter.POLICE in this
}

/** What the user tapped; shown in a bottom sheet. */
sealed interface MapSelection {
    data class PoiSelection(val poi: Poi) : MapSelection
    data class ReportSelection(val report: HazardReport) : MapSelection
    data class GroupSelection(val group: PeopleGroup) : MapSelection
}

enum class MapPhase { LOADING, NO_PACK, ERROR, READY }

data class MapUiState(
    val phase: MapPhase = MapPhase.LOADING,
    /** Everything the map draws, already filtered. `darkStyle` is set by the screen from the theme. */
    val view: MapViewState = MapViewState(),
    val filters: Set<MapFilter> = MapFilter.entries.toSet(),
    val selection: MapSelection? = null,
)

/** Pack data that only changes when the pack does. */
private sealed interface PackLoad {
    data object Loading : PackLoad
    data object NoPack : PackLoad
    data object Failed : PackLoad
    data class Ready(val pois: List<Poi>, val riskZones: List<RiskZone>) : PackLoad
}

private data class LiveData(
    val reports: List<HazardReport>,
    val groups: List<PeopleGroup>,
    val beacons: List<BuddyBeacon>,
    val alerts: List<SahayAlert>,
    val fix: LocationFix?,
)

private data class LocalState(
    val filters: Set<MapFilter> = MapFilter.entries.toSet(),
    val selection: MapSelection? = null,
    val center: GeoPoint? = null,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val packs: PackRepository,
    reports: ReportRepository,
    groups: GroupService,
    alerts: AlertRepository,
    private val location: LocationProvider,
) : ViewModel() {

    private val pack = MutableStateFlow<PackLoad>(PackLoad.Loading)
    private val local = MutableStateFlow(LocalState())

    private val live = combine(reports.reports, groups.snapshot, alerts.alerts, location.lastFix) { r, g, a, fix ->
        LiveData(r, g.groups, g.beacons, a, fix)
    }

    val state: StateFlow<MapUiState> = combine(pack, live, local) { packLoad, liveData, localState ->
        buildState(packLoad, liveData, localState)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MapUiState())

    init {
        // Reload when the active pack changes (download finished, pack deleted).
        viewModelScope.launch { packs.activePack.collectLatest { load(hasPack = it != null) } }
    }

    fun retry() {
        viewModelScope.launch { load(hasPack = packs.activePack.value != null) }
    }

    fun toggleFilter(filter: MapFilter) = local.update { s ->
        s.copy(filters = if (filter in s.filters) s.filters - filter else s.filters + filter)
    }

    fun select(selection: MapSelection) = local.update { it.copy(selection = selection) }

    fun clearSelection() = local.update { it.copy(selection = null) }

    /** Centres the map on the user again. Without a fix there is nothing to centre on, so nothing changes. */
    fun recenter() {
        val fix = location.lastFix.value ?: return
        local.update { it.copy(center = fix.point) }
    }

    private suspend fun load(hasPack: Boolean) {
        if (!hasPack) {
            pack.value = PackLoad.NoPack
            return
        }
        pack.value = PackLoad.Loading
        pack.value = try {
            PackLoad.Ready(packs.pois(), packs.riskZones())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PackLoad.Failed
        }
    }

    private fun buildState(packLoad: PackLoad, live: LiveData, local: LocalState): MapUiState {
        val phase = when (packLoad) {
            PackLoad.Loading -> MapPhase.LOADING
            PackLoad.NoPack -> MapPhase.NO_PACK
            PackLoad.Failed -> MapPhase.ERROR
            is PackLoad.Ready -> MapPhase.READY
        }
        val ready = packLoad as? PackLoad.Ready
        val filters = local.filters
        val view = MapViewState(
            center = local.center ?: live.fix?.point,
            followUser = true,
            myLocation = live.fix,
            pois = ready?.pois.orEmpty().filter { filters.allows(it.type) },
            riskZones = if (MapFilter.RISK_AREAS in filters) ready?.riskZones.orEmpty() else emptyList(),
            reports = if (MapFilter.REPORTS in filters) live.reports else emptyList(),
            groups = if (MapFilter.GROUPS in filters) live.groups else emptyList(),
            beacons = if (MapFilter.GROUPS in filters) live.beacons else emptyList(),
            // Official warning areas stay visible whatever the filters say: they are safety critical.
            alerts = live.alerts,
        )
        return MapUiState(phase, view, filters, local.selection)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
