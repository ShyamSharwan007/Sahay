package com.sahay.app.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the sign-in screen should tell the user after a failed attempt. */
enum class SignInProblem { NO_INTERNET, NO_GOOGLE_ACCOUNT, FAILED }

data class SignInUiState(
    val loading: Boolean = false,
    val problem: SignInProblem? = null,
    /** True once a user is signed in (now or earlier); the screen moves on to the profile wizard. */
    val signedIn: Boolean = false,
)

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SignInUiState(signedIn = auth.currentUser != null))
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun signInWithGoogle(activityContext: Context) = startSignIn { auth.signInWithGoogle(activityContext) }

    fun continueAsGuest() = startSignIn { auth.signInAsGuest() }

    fun dismissProblem() = _state.update { it.copy(problem = null) }

    private fun startSignIn(attempt: suspend () -> SignInResult) {
        if (_state.value.loading) return // ignore double taps
        _state.update { it.copy(loading = true, problem = null) }
        viewModelScope.launch {
            val result = attempt()
            _state.update { it.after(result) }
        }
    }

    private fun SignInUiState.after(result: SignInResult) = when (result) {
        is SignInResult.Success -> copy(loading = false, signedIn = true)
        SignInResult.Cancelled -> copy(loading = false)
        SignInResult.NoGoogleAccount -> copy(loading = false, problem = SignInProblem.NO_GOOGLE_ACCOUNT)
        SignInResult.NoInternet -> copy(loading = false, problem = SignInProblem.NO_INTERNET)
        SignInResult.Failed -> copy(loading = false, problem = SignInProblem.FAILED)
    }
}
