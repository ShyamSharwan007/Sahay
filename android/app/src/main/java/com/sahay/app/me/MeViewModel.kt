package com.sahay.app.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.app.auth.AuthRepository
import com.sahay.app.onboarding.AppLocaleController
import com.sahay.core.contracts.EmergencyModeController
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.ThemeMode
import com.sahay.core.contracts.UiPreferences
import com.sahay.core.contracts.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MeUiState(
    /** Null while the profile is still loading. */
    val profile: UserProfile? = null,
    val pack: PackInfo? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** True after a save or delete that didn't work, so the screen can say so. */
    val actionFailed: Boolean = false,
    val signedOut: Boolean = false,
)

/** Shared by the Me tab and the screens opened from it (medical card, trip pack, privacy). */
@HiltViewModel
class MeViewModel @Inject constructor(
    private val profiles: ProfileStore,
    private val packs: PackRepository,
    private val preferences: UiPreferences,
    private val locales: AppLocaleController,
    private val auth: AuthRepository,
    private val emergencyMode: EmergencyModeController,
) : ViewModel() {

    private val failed = MutableStateFlow(false)
    private val signedOut = MutableStateFlow(false)

    val state: StateFlow<MeUiState> = combine(
        profiles.profile, packs.activePack, preferences.themeMode, failed, signedOut,
    ) { profile, pack, theme, actionFailed, done ->
        MeUiState(profile, pack, theme, actionFailed, done)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MeUiState())

    fun setTheme(mode: ThemeMode) = attempt { preferences.setThemeMode(mode) }

    fun setGroupFinder(enabled: Boolean) = attempt {
        profiles.profile.value?.let { profiles.save(it.copy(groupFinderOptIn = enabled)) }
    }

    /** Saves the language with the profile (alerts follow it), then switches the app. The switch can recreate the activity. */
    fun setLanguage(code: String) = attempt {
        profiles.profile.value?.let { profiles.save(it.copy(language = code)) }
        locales.apply(code)
    }

    fun deletePack() = attempt { packs.deletePack() }

    /** Ends Emergency Mode, forgets the profile on this phone and signs out. The screen then restarts the app. */
    fun signOut() = attempt {
        if (emergencyMode.isActive.value) emergencyMode.deactivate()
        profiles.clear()
        auth.signOut()
        signedOut.value = true
    }

    fun dismissFailure() = failed.update { false }

    private fun attempt(block: suspend () -> Unit) {
        failed.value = false
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed.value = true
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
