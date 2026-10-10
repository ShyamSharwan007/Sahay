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
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.RiskMonitor
import com.sahay.core.contracts.SahayAlert
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
    private val alerts: AlertRepository,
    private val emergencyMode: EmergencyModeController,
    packs: PackRepository,
    private val riskMonitor: RiskMonitor,
    private val clock: Clock,
    private val deepLinks: DeepLinkRouter,
    private val profiles: ProfileStore,
) : ViewModel() {

    /** A notification link waiting to be opened by the main screens. */
    val pendingLink: StateFlow<DeepLinkTarget?> = deepLinks.pending

    fun consumeLink() = deepLinks.consume()

    /**
     * Keeps the saved language equal to the app language, also when it was changed in Android's own
     * per-app language settings. Alerts, pack text and phrases all follow the saved language.
     */
    fun syncLanguage(appLanguage: String) {
        viewModelScope.launch {
            val profile = profiles.profile.value ?: return@launch
            if (profile.language != appLanguage) profiles.save(profile.copy(language = appLanguage))
        }
    }

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

    /** Alerts that already switched Emergency Mode on, so exiting it is not undone by the same alert. */
    private val autoHandled = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            alerts.alerts.collect { list ->
                val critical = criticalAlertToAutoActivate(list, autoHandled, clock.millis() / 1000) ?: return@collect
                autoHandled += critical.id
                if (!emergencyMode.isActive.value) emergencyMode.activate()
            }
        }
    }

    override fun onCleared() {
        riskMonitor.stopMonitoring()
    }
}

/** Newer than this, an unread emergency alert still counts as current. */
private const val CRITICAL_ALERT_MAX_AGE_SEC = 6 * 3600L

/**
 * The alert that should switch Emergency Mode on by itself: an unread, recent, not yet handled
 * emergency (severity 3 or FLD_EVAC). Null when there is none.
 */
fun criticalAlertToAutoActivate(alerts: List<SahayAlert>, handled: Set<String>, nowSec: Long): SahayAlert? =
    alerts.firstOrNull {
        !it.read && it.id !in handled &&
            (it.severity >= 3 || it.templateCode == "FLD_EVAC") &&
            nowSec - it.issuedAtEpochSec <= CRITICAL_ALERT_MAX_AGE_SEC
    }
