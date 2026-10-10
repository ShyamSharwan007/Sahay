package com.sahay.app.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.HazardType
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val MAX_NOTE_CHARS = 140

data class ReportUiState(
    val type: HazardType? = null,
    val note: String = "",
    val fix: LocationFix? = null,
    val locating: Boolean = true,
    val submitting: Boolean = false,
    val submitFailed: Boolean = false,
    /** Set once the report is saved or sent; the screen then shows the result. */
    val result: HazardReport? = null,
) {
    val canSubmit: Boolean get() = type != null && fix != null && !submitting
}

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val reports: ReportRepository,
    private val location: LocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportUiState(fix = location.lastFix.value, locating = location.lastFix.value == null))
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    init {
        if (_state.value.fix == null) locate()
    }

    fun locate() {
        _state.update { it.copy(locating = true) }
        viewModelScope.launch {
            val fix = location.lastFix.value ?: location.currentFix()
            _state.update { it.copy(fix = fix ?: it.fix, locating = false) }
        }
    }

    fun selectType(type: HazardType) = _state.update { it.copy(type = type, submitFailed = false) }

    fun onNoteChange(note: String) = _state.update { it.copy(note = note.take(MAX_NOTE_CHARS)) }

    fun submit() {
        val current = _state.value
        val type = current.type ?: return
        val fix = current.fix ?: return
        if (current.submitting) return
        _state.update { it.copy(submitting = true, submitFailed = false) }
        viewModelScope.launch {
            try {
                // No photo: the report always uses the current fix, never a picked point.
                val report = reports.submit(type, fix.point, current.note.trim().ifBlank { null }, photoJpeg = null)
                _state.update { it.copy(submitting = false, result = report) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(submitting = false, submitFailed = true) }
            }
        }
    }
}
