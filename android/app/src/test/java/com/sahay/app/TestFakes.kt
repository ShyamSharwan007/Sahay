package com.sahay.app

import android.content.Context
import com.sahay.app.auth.AuthRepository
import com.sahay.app.auth.AuthUser
import com.sahay.app.auth.SignInResult
import com.sahay.app.data.LoadableProfileStore
import com.sahay.app.onboarding.AppLocaleController
import com.sahay.core.contracts.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

val googleUser = AuthUser("uid-google", "Anna Schmidt", "anna@example.com", "https://photo", isAnonymous = false)
val guestUser = AuthUser("uid-guest", null, null, null, isAnonymous = true)

class FakeAuth(
    override var currentUser: AuthUser? = null,
    var googleResult: SignInResult = SignInResult.Cancelled,
    var guestResult: SignInResult = SignInResult.Success(guestUser),
) : AuthRepository {
    override suspend fun signInWithGoogle(activityContext: Context): SignInResult = googleResult
    override suspend fun signInAsGuest(): SignInResult = guestResult
    override fun signOut() { currentUser = null }
}

class FakeProfileStore(initial: UserProfile? = null, var failSave: Boolean = false) : LoadableProfileStore {
    private val state = MutableStateFlow(initial)
    override val profile: StateFlow<UserProfile?> = state
    var saved: UserProfile? = null

    override suspend fun awaitLoaded(): UserProfile? = state.value

    override suspend fun save(profile: UserProfile) {
        if (failSave) throw java.io.IOException("disk full")
        saved = profile
        state.value = profile
    }

    override suspend fun clear() {
        state.value = null
    }
}

class FakeLocales(private var language: String = "en") : AppLocaleController {
    var applied: String? = null
    override fun currentLanguage() = language
    override fun apply(code: String) {
        applied = code
        language = code
    }
}
