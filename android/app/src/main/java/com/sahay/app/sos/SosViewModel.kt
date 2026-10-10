package com.sahay.app.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.app.data.LoadableProfileStore
import com.sahay.app.profile.normalizePhone
import com.sahay.core.contracts.EmergencyContact
import com.sahay.core.contracts.SosResult
import com.sahay.core.contracts.SosService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

const val SOS_COUNTDOWN_SECONDS = 5
private const val TICK_MS = 1_000L

sealed interface SosUiState {
    data object Loading : SosUiState
    /** There is nobody to text; the screen offers to add a contact. */
    data object NoContacts : SosUiState
    data class Countdown(val secondsLeft: Int) : SosUiState
    /** Countdown is over but Android has not yet allowed us to send texts. */
    data object NeedsSmsPermission : SosUiState
    data object Sending : SosUiState
    data class Done(val result: SosResult) : SosUiState
    /** Permission refused: the user sends the same text from their messages app. */
    data class SmsApp(val message: String, val phones: List<String>, val autoOpen: Boolean) : SosUiState
}

enum class ContactSendStatus { SENT, FAILED }

/** A contact counts as sent when its number (or, failing that, its name) is in [SosResult.sentTo]. */
fun contactStatus(contact: EmergencyContact, result: SosResult): ContactSendStatus {
    val phone = normalizePhone(contact.phone)
    val sent = result.sentTo.any { normalizePhone(it) == phone || it == contact.name }
    return if (sent) ContactSendStatus.SENT else ContactSendStatus.FAILED
}

/** After a retry, contacts that already got the text stay "sent". */
fun mergeResults(previous: SosResult?, latest: SosResult): SosResult {
    if (previous == null) return latest
    val sent = (previous.sentTo + latest.sentTo).distinct()
    return latest.copy(sentTo = sent, failed = latest.failed.filterNot { it in sent })
}

@HiltViewModel
class SosViewModel @Inject constructor(
    private val sos: SosService,
    private val profiles: LoadableProfileStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<SosUiState>(SosUiState.Loading)
    val state: StateFlow<SosUiState> = _state.asStateFlow()

    private val _contacts = MutableStateFlow<List<EmergencyContact>>(emptyList())
    val contacts: StateFlow<List<EmergencyContact>> = _contacts.asStateFlow()

    /** The exact text that will be sent, once known. */
    private val _preview = MutableStateFlow<String?>(null)
    val preview: StateFlow<String?> = _preview.asStateFlow()

    private var countdownJob: Job? = null

    init {
        viewModelScope.launch { _preview.value = safePreview() }
        viewModelScope.launch {
            // The disk read gives the first answer; later edits (for example new contacts) follow the flow.
            onContacts(profiles.awaitLoaded()?.contacts.orEmpty())
            profiles.profile.map { it?.contacts }.filterNotNull().distinctUntilChanged().collect(::onContacts)
        }
    }

    /** Starts the countdown once there is someone to text; goes back to "add a contact" when the list is empty. */
    private fun onContacts(list: List<EmergencyContact>) {
        _contacts.value = list
        when (_state.value) {
            SosUiState.Loading, SosUiState.NoContacts ->
                if (list.isEmpty()) _state.value = SosUiState.NoContacts else startCountdown()
            else -> Unit
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (second in SOS_COUNTDOWN_SECONDS downTo 1) {
                _state.value = SosUiState.Countdown(second)
                delay(TICK_MS)
            }
            if (hasSmsPermission()) send() else _state.value = SosUiState.NeedsSmsPermission
        }
    }

    fun cancelCountdown() {
        countdownJob?.cancel()
    }

    /** Sends the SOS to every contact. Safe to call again as a retry: texts that already went out stay "sent". */
    fun send() {
        if (_state.value == SosUiState.Sending) return
        val previous = (_state.value as? SosUiState.Done)?.result
        _state.value = SosUiState.Sending
        viewModelScope.launch {
            val result = try {
                // An SOS must finish even if the user leaves the screen meanwhile.
                withContext(NonCancellable) { sos.sendSos() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SosResult(emptyList(), _contacts.value.map { it.phone }, null, _preview.value.orEmpty())
            }
            _state.value = SosUiState.Done(mergeResults(previous, result))
        }
    }

    /** The user said no to the SMS permission: fall back to their messages app with the text prefilled. */
    fun smsPermissionDenied() {
        viewModelScope.launch {
            _state.value = SosUiState.SmsApp(
                message = _preview.value ?: safePreview(),
                phones = _contacts.value.map { normalizePhone(it.phone) },
                autoOpen = true,
            )
        }
    }

    fun smsAppOpened() {
        (_state.value as? SosUiState.SmsApp)?.let { _state.value = it.copy(autoOpen = false) }
    }

    private suspend fun safePreview(): String = try {
        sos.previewMessage()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ""
    }

    private fun hasSmsPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
}
