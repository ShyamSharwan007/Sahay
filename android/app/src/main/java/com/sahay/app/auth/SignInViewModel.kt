package com.sahay.app.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.app.data.LoadableProfileStore
import com.sahay.app.data.profileKey
import com.sahay.core.contracts.PackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the sign-in screen should tell the user after a failed attempt. */
enum class SignInProblem { NO_INTERNET, NO_GOOGLE_ACCOUNT, FAILED }

/** Where to go once someone is signed in. */
enum class SignInNext {
    /** No saved profile for this user: run the profile wizard. */
    PROFILE_SETUP,

    /** Saved profile restored and a trip pack exists. */
    HOME,

    /** Saved profile restored, but there is no trip pack yet. */
    TRIP_SETUP,
}

data class SignInUiState(
    val loading: Boolean = false,
    val problem: SignInProblem? = null,
    /** Set once a user is signed in (now or earlier); the screen moves on, then calls [SignInViewModel.consumeNext]. */
    val next: SignInNext? = null,
)

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: LoadableProfileStore,
    private val packs: PackRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    init {
        // A session left over from an earlier run (killed mid-setup) goes on without asking again.
        auth.currentUser?.let { user -> viewModelScope.launch { resolve(user) } }
    }

    /** The screen has moved on; going back to it later must not move on again by itself. */
    fun consumeNext() = _state.update { it.copy(next = null) }

    fun signInWithGoogle(activityContext: Context) = startSignIn { auth.signInWithGoogle(activityContext) }

    fun continueAsGuest() = startSignIn { auth.signInAsGuest() }

    fun dismissProblem() = _state.update { it.copy(problem = null) }

    private fun startSignIn(attempt: suspend () -> SignInResult) {
        if (_state.value.loading) return // ignore double taps
        _state.update { it.copy(loading = true, problem = null) }
        viewModelScope.launch {
            val result = attempt()
            if (result is SignInResult.Success) resolve(result.user)
            else _state.update { it.after(result) }
        }
    }

    /** A user with a saved profile goes straight in; a new one sets up a profile. Nothing is fetched from the network. */
    private suspend fun resolve(user: AuthUser) {
        val saved = try {
            profiles.savedFor(profileKey(user.uid, user.isAnonymous))?.takeIf { it.onboardingComplete }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null // unreadable storage: treat as a new user rather than blocking sign-in
        }
        val next = if (saved == null) {
            SignInNext.PROFILE_SETUP
        } else {
            // Guests get a new uid every time, so the restored profile takes the current one.
            val restored = saved.copy(uid = user.uid, isGuest = user.isAnonymous, email = user.email, photoUrl = user.photoUrl)
            try {
                profiles.save(restored)
                if (packs.activePack.value != null) SignInNext.HOME else SignInNext.TRIP_SETUP
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                SignInNext.PROFILE_SETUP
            }
        }
        _state.update { it.copy(loading = false, next = next) }
    }

    private fun SignInUiState.after(result: SignInResult) = when (result) {
        is SignInResult.Success -> copy(loading = false)
        SignInResult.Cancelled -> copy(loading = false)
        SignInResult.NoGoogleAccount -> copy(loading = false, problem = SignInProblem.NO_GOOGLE_ACCOUNT)
        SignInResult.NoInternet -> copy(loading = false, problem = SignInProblem.NO_INTERNET)
        SignInResult.Failed -> copy(loading = false, problem = SignInProblem.FAILED)
    }
}
