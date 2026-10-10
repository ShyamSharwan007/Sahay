package com.sahay.app.navigate

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.LocationMode
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.RiskZone
import com.sahay.core.contracts.Route
import com.sahay.core.contracts.RoutingEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

sealed interface NavUiState {
    data object Finding : NavUiState
    /** [permissionMissing] distinguishes "allow location" from "no GPS fix yet". */
    data class NoFix(val permissionMissing: Boolean) : NavUiState
    data object NoRoute : NavUiState
    data class Active(
        val route: Route,
        val myLocation: LocationFix,
        val riskZones: List<RiskZone>,
        val remainingM: Double,
        val arrived: Boolean,
        /** Every pack place, for the full-screen map. */
        val allPois: List<Poi> = emptyList(),
        /** The nearest other safe places with their straight-line distance in metres. */
        val nearby: List<Pair<Poi, Double>> = emptyList(),
    ) : NavUiState
}

@HiltViewModel
class NavigateViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val routing: RoutingEngine,
    private val location: LocationProvider,
    private val packs: PackRepository,
) : ViewModel() {

    // Type-safe routes store their arguments under the property name.
    private var target: NavTarget = parseNavTarget(savedState.get<String>("target"))

    private val attempt = MutableStateFlow(0)

    /**
     * Location updates are only collected while the screen is on top: when the UI stops collecting,
     * the upstream (and the NAVIGATION location request) is cancelled after a short grace period.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<NavUiState> = attempt
        .flatMapLatest { navigationFlow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NavUiState.Finding)

    fun retry() = attempt.update { it + 1 }

    /** Re-routes to another safe place picked from the "nearby" list. */
    fun routeTo(poi: Poi) {
        target = NavTarget.ToPoi(poi.id)
        retry()
    }

    private fun navigationFlow(): Flow<NavUiState> = flow {
        emit(NavUiState.Finding)
        val start = location.lastFix.value ?: location.currentFix()
        if (start == null) {
            emit(NavUiState.NoFix(permissionMissing = !location.hasPermission()))
            return@flow
        }
        val route = findRoute(start)
        if (route == null) {
            emit(NavUiState.NoRoute)
            return@flow
        }
        val zones = safely { packs.riskZones() } ?: emptyList()
        val pois = safely { packs.pois() } ?: emptyList()
        emit(active(route, zones, start, pois))
        // A failing location stream keeps the last known state instead of crashing the screen.
        location.updates(LocationMode.NAVIGATION)
            .catch { }
            .collect { emit(active(route, zones, it, pois)) }
    }

    private suspend fun findRoute(start: LocationFix): Route? = safely {
        when (val t = target) {
            NavTarget.NearestSafe -> routing.routeToNearestSafe(start.point)
            is NavTarget.ToPoint -> routing.routeTo(start.point, t.point)
            is NavTarget.ToPoi -> {
                val poi = packs.pois().firstOrNull { it.id == t.poiId }
                poi?.let { routing.routeTo(start.point, it.point)?.withDestination(it) }
            }
        }
    }

    private fun active(route: Route, zones: List<RiskZone>, fix: LocationFix, pois: List<Poi>): NavUiState.Active {
        val arrived = hasArrived(route, fix.point)
        return NavUiState.Active(
            route = route,
            myLocation = fix,
            riskZones = zones,
            remainingM = if (arrived) 0.0 else remainingDistanceM(route, fix.point),
            arrived = arrived,
            allPois = pois,
            nearby = nearbySafePlaces(pois, route.destination?.id, fix.point),
        )
    }

    private fun Route.withDestination(poi: Poi): Route =
        if (destination == null) copy(destination = poi) else this

    /** Runs [block]; any failure other than cancellation becomes null (the UI then offers a retry). */
    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
