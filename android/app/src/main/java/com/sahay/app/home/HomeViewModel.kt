package com.sahay.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.core.contracts.AlertRepository
import com.sahay.core.contracts.ForecastDay
import com.sahay.app.common.forecastNote
import com.sahay.app.common.ForecastNote
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.Precaution
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.RiskMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val status: HomeStatus = HomeStatus.Safe,
    val language: String = "en",
    val hasPack: Boolean = false,
    /** Null when there is no pack or the pack has no entry for today. */
    val todayForecast: ForecastDay? = null,
    val forecastNote: ForecastNote = ForecastNote.UNAVAILABLE_PAST_PATTERNS,
    val precautions: List<Precaution> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    alerts: AlertRepository,
    location: LocationProvider,
    risk: RiskMonitor,
    packs: PackRepository,
    profiles: ProfileStore,
    private val clock: Clock,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        alerts.alerts,
        location.lastFix,
        risk.currentZone,
        packs.activePack,
        profiles.profile,
    ) { alertList, fix, zone, pack, profile ->
        HomeUiState(
            loading = false,
            status = computeHomeStatus(alertList, fix?.point, zone),
            language = profile?.language ?: "en",
            hasPack = pack != null,
            todayForecast = todaysForecast(pack, LocalDate.now(clock)),
            forecastNote = pack?.let { forecastNote(it.forecast, it.tripStart, LocalDate.now(clock)) }
                ?: ForecastNote.UNAVAILABLE_PAST_PATTERNS,
            precautions = pack?.precautions.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
