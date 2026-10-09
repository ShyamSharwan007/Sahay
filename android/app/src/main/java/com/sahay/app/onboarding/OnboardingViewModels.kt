package com.sahay.app.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.app.data.LoadableProfileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the app opens, decided once from the saved profile. */
enum class StartDestination { LOADING, LANGUAGE, MAIN }

@HiltViewModel
class StartViewModel @Inject constructor(
    private val profiles: LoadableProfileStore,
) : ViewModel() {

    private val _destination = MutableStateFlow(StartDestination.LOADING)
    val destination: StateFlow<StartDestination> = _destination.asStateFlow()

    init {
        viewModelScope.launch {
            _destination.value =
                if (profiles.awaitLoaded()?.onboardingComplete == true) StartDestination.MAIN else StartDestination.LANGUAGE
        }
    }
}

@HiltViewModel
class LanguageViewModel @Inject constructor(
    private val locales: AppLocaleController,
) : ViewModel() {

    // Preselects the device language on first launch (or the app language when coming back).
    private val _selected = MutableStateFlow(locales.currentLanguage())
    val selected: StateFlow<String> = _selected.asStateFlow()

    fun select(code: String) {
        _selected.value = supportedLanguageOrEnglish(code)
    }

    /** Applies the chosen language. Call after navigating on, because this can recreate the activity. */
    fun confirm() = locales.apply(_selected.value)
}
