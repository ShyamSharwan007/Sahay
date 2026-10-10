package com.sahay.app.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.core.contracts.AlertRepository
import com.sahay.core.contracts.SahayAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlertsUiState(
    val alerts: List<SahayAlert> = emptyList(),
    val refreshing: Boolean = false,
    /** The last refresh failed (offline, server down). Saved alerts are still shown. */
    val refreshFailed: Boolean = false,
) {
    /** First load with nothing to show yet. */
    val loading: Boolean get() = refreshing && alerts.isEmpty()
}

private data class RefreshState(val refreshing: Boolean = false, val failed: Boolean = false)

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val repository: AlertRepository,
) : ViewModel() {

    private val refreshState = MutableStateFlow(RefreshState())

    val state: StateFlow<AlertsUiState> = combine(repository.alerts, refreshState) { alerts, refresh ->
        AlertsUiState(alerts, refresh.refreshing, refresh.failed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AlertsUiState())

    init {
        refresh()
    }

    fun refresh() {
        if (refreshState.value.refreshing) return
        refreshState.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val ok = try {
                repository.refresh().isSuccess
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            refreshState.value = RefreshState(refreshing = false, failed = !ok)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
