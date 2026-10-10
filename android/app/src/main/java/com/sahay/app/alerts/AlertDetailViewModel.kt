package com.sahay.app.alerts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.core.contracts.AlertRepository
import com.sahay.core.contracts.SahayAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlertDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: AlertRepository,
    private val pasted: PastedAlertHolder,
) : ViewModel() {

    // Type-safe routes store their arguments under the property name.
    private val alertId: String = savedState.get<String>("id").orEmpty()

    /** Null when the alert is not (or no longer) known. */
    val alert: StateFlow<SahayAlert?> = repository.alerts
        .map(::lookup)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), lookup(repository.alerts.value))

    private fun lookup(list: List<SahayAlert>): SahayAlert? = list.firstOrNull { it.id == alertId } ?: pasted.find(alertId)

    init {
        // Opening the alert counts as reading it.
        if (alertId.isNotEmpty()) viewModelScope.launch { repository.markRead(alertId) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
