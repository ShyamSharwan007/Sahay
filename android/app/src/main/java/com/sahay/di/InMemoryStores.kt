package com.sahay.di

import com.sahay.core.contracts.AuthTokenProvider
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.ThemeMode
import com.sahay.core.contracts.UiPreferences
import com.sahay.core.contracts.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// TEMPORARY in-memory implementations so other modules can run before real storage/auth exist.

@Singleton
class InMemoryProfileStore @Inject constructor() : ProfileStore {
    private val state = MutableStateFlow<UserProfile?>(null)
    override val profile: StateFlow<UserProfile?> = state.asStateFlow()

    override suspend fun save(profile: UserProfile) {
        state.value = profile
    }

    override suspend fun clear() {
        state.value = null
    }
}

@Singleton
class InMemoryAuthTokenProvider @Inject constructor() : AuthTokenProvider {
    override val uid: String? = null
    override suspend fun idToken(forceRefresh: Boolean): String? = null
}

@Singleton
class InMemoryUiPreferences @Inject constructor() : UiPreferences {
    private val state = MutableStateFlow(ThemeMode.SYSTEM)
    override val themeMode: StateFlow<ThemeMode> = state.asStateFlow()

    override suspend fun setThemeMode(mode: ThemeMode) {
        state.value = mode
    }
}
