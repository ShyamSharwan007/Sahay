package com.sahay.app.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.core.contracts.PackDownloadState
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.Region
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

enum class TripStep { REGION, DATES, DOWNLOAD, READY }

sealed interface RegionsUi {
    data object Loading : RegionsUi
    data object Error : RegionsUi
    data class Loaded(val regions: List<Region>) : RegionsUi   // an empty list is the "empty" state
}

data class TripSetupState(
    val step: TripStep = TripStep.REGION,
    val regions: RegionsUi = RegionsUi.Loading,
    val region: Region? = null,
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val download: PackDownloadState = PackDownloadState.Idle,
    val pack: PackInfo? = null,
    val language: String = "en",
)

@HiltViewModel
class TripSetupViewModel @Inject constructor(
    private val packs: PackRepository,
    profiles: ProfileStore,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(TripSetupState(language = profiles.profile.value?.language ?: "en"))
    val state: StateFlow<TripSetupState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    init {
        loadRegions()
        viewModelScope.launch {
            profiles.profile.collect { p -> if (p != null) _state.update { it.copy(language = p.language) } }
        }
    }

    fun loadRegions() {
        _state.update { it.copy(regions = RegionsUi.Loading) }
        viewModelScope.launch {
            val result = try {
                RegionsUi.Loaded(packs.regions())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RegionsUi.Error
            }
            _state.update { it.copy(regions = result) }
        }
    }

    fun selectRegion(region: Region) {
        _state.update { it.copy(region = region, step = TripStep.DATES) }
    }

    /** Validates again (the UI already checked) and starts the download. */
    fun confirmDates(start: LocalDate?, end: LocalDate?) {
        if (validateTripDates(start, end, LocalDate.now(clock)) != null) return
        _state.update { it.copy(start = start, end = end) }
        startDownload()
    }

    /** Also the retry action after a failure. */
    fun startDownload() {
        val s = _state.value
        val region = s.region ?: return
        val start = s.start ?: return
        val end = s.end ?: return
        downloadJob?.cancel()
        _state.update { it.copy(step = TripStep.DOWNLOAD, download = PackDownloadState.Idle) }
        downloadJob = viewModelScope.launch {
            try {
                packs.download(region.id, start, end).collect { d ->
                    _state.update {
                        if (d is PackDownloadState.Done) it.copy(download = d, pack = d.pack, step = TripStep.READY)
                        else it.copy(download = d)
                    }
                }
                // A flow that ends without Done or Failed must not leave the user on a spinner.
                val s2 = _state.value
                if (s2.step == TripStep.DOWNLOAD && s2.download !is PackDownloadState.Failed) fail()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail()
            }
        }
    }

    private fun fail() {
        _state.update { it.copy(download = PackDownloadState.Failed("download", retryable = true)) }
    }

    /** Goes one step back. Returns false when there is nothing to go back to (caller leaves the screen). */
    fun back(): Boolean = when (_state.value.step) {
        TripStep.DATES -> {
            _state.update { it.copy(step = TripStep.REGION) }
            true
        }
        TripStep.DOWNLOAD -> {
            downloadJob?.cancel()
            _state.update { it.copy(step = TripStep.DATES, download = PackDownloadState.Idle) }
            true
        }
        TripStep.REGION, TripStep.READY -> false
    }

    /** From the non-retryable error: pick another place. */
    fun chooseAnotherRegion() {
        downloadJob?.cancel()
        _state.update { it.copy(step = TripStep.REGION, download = PackDownloadState.Idle) }
    }
}
