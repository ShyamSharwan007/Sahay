package com.sahay.app.report

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.HazardType
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** The compressed photo (JPEG) that goes with the report, if any. */
    val photo: ByteArray? = null,
    val photoBusy: Boolean = false,
    val photoFailed: Boolean = false,
    /** Set once the report is saved or sent; the screen then shows the result. */
    val result: HazardReport? = null,
) {
    val canSubmit: Boolean get() = type != null && fix != null && !submitting && !photoBusy
}

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val reports: ReportRepository,
    private val location: LocationProvider,
    @ApplicationContext private val context: Context,
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

    /** A photo was picked or taken: shrink it off the main thread, then keep it for the report. */
    fun onPhoto(uri: Uri) {
        _state.update { it.copy(photoBusy = true, photoFailed = false) }
        viewModelScope.launch {
            val jpeg = withContext(Dispatchers.Default) { PhotoCompressor.compress(context.contentResolver, uri) }
            _state.update { it.copy(photoBusy = false, photo = jpeg ?: it.photo, photoFailed = jpeg == null) }
        }
    }

    /** The picker or camera failed or was not available. */
    fun onPhotoProblem() = _state.update { it.copy(photoFailed = true) }

    fun removePhoto() = _state.update { it.copy(photo = null, photoFailed = false) }

    fun onNoteChange(note: String) = _state.update { it.copy(note = note.take(MAX_NOTE_CHARS)) }

    fun submit() {
        val current = _state.value
        val type = current.type ?: return
        val fix = current.fix ?: return
        if (current.submitting) return
        _state.update { it.copy(submitting = true, submitFailed = false) }
        viewModelScope.launch {
            try {
                // The report always uses the current fix, never a picked point.
                val report = reports.submit(type, fix.point, current.note.trim().ifBlank { null }, current.photo)
                _state.update { it.copy(submitting = false, result = report) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(submitting = false, submitFailed = true) }
            }
        }
    }
}
