package com.sahay.app.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.core.contracts.AlertRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val MAX_PASTE_CHARS = 1000

data class PasteUiState(
    val text: String = "",
    val translating: Boolean = false,
    val showEmptyError: Boolean = false,
)

@HiltViewModel
class PasteAlertViewModel @Inject constructor(
    private val repository: AlertRepository,
    private val pasted: PastedAlertHolder,
) : ViewModel() {

    private val _state = MutableStateFlow(PasteUiState())
    val state: StateFlow<PasteUiState> = _state.asStateFlow()

    private val _opened = Channel<String>(Channel.BUFFERED)
    /** Emits the id of the alert to open once translation finishes. */
    val opened: Flow<String> = _opened.receiveAsFlow()

    fun onTextChange(text: String) = _state.update { it.copy(text = text.take(MAX_PASTE_CHARS), showEmptyError = false) }

    fun submit() {
        val current = _state.value
        if (current.translating) return
        if (current.text.isBlank()) {
            _state.update { it.copy(showEmptyError = true) }
            return
        }
        _state.update { it.copy(translating = true) }
        viewModelScope.launch {
            // translatePasted never throws: offline it falls back to keyword matching.
            val alert = repository.translatePasted(current.text.trim())
            pasted.remember(alert)
            _state.update { it.copy(translating = false) }
            _opened.send(alert.id)
        }
    }
}
