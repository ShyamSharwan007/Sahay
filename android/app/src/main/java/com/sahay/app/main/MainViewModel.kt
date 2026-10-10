package com.sahay.app.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.app.deeplink.DeepLinkRouter
import com.sahay.app.deeplink.DeepLinkTarget
import com.sahay.core.contracts.AlertRepository
import com.sahay.core.contracts.ConnectivityMonitor
import com.sahay.core.contracts.ConnectivityState
import com.sahay.core.contracts.EmergencyModeController
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.RiskMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

data class MainUiState(
    val connectivity: ConnectivityState = ConnectivityState(internet = true, cellular = true, meshActive = false, meshPeers = 0),
    val unreadAlerts: Int = 0,
    val emergency: Boolean = false,
    /** When the data on screen was last fresh, for "Offline — using saved data from 10:42". Null = unknown. */
    val savedDataSinceEpochSec: Long? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val connectivity: ConnectivityMonitor,
    alerts: AlertRepository,
    private val emergencyMode: EmergencyModeController,
    packs: PackRepository,
    private val riskMonitor: RiskMonitor,
    private val clock: Clock,
    private val deepLinks: DeepLinkRouter,
) : ViewModel() {

    /** A notification link waiting to be opened by the main screens. */
    val pendingLink: StateFlow<DeepLinkTarget?> = deepLinks.pending

    fun consumeLink() = deepLinks.consume()

    fun activateEmergency() = emergencyMode.activate()

    fun deactivateEmergency() = emergencyMode.deactivate()


    private val lastOnlineEpochSec = MutableStateFlow<Long?>(null)

    val state: StateFlow<MainUiState> = combine(
        connectivity.state,
        alerts.unreadCount,
        emergencyMode.isActive,
        packs.activePack,
        lastOnlineEpochSec,
    ) { net, unread, emergency, pack, lastOnline ->
        MainUiState(
            connectivity = net,
            unreadAlerts = unread,
            emergency = emergency,
            savedDataSinceEpochSec = lastOnline ?: pack?.downloadedAtEpochSec,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        // Zone monitoring feeds the Home status card; it lives as long as the main screens.
        riskMonitor.startMonitoring()
        viewModelScope.launch {
            connectivity.state.collect { if (it.internet) lastOnlineEpochSec.value = clock.millis() / 1000 }
        }
    }

    override fun onCleared() {
        riskMonitor.stopMonitoring()
    }
}
