package com.sahay.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sahay.core.contracts.ThemeMode
import com.sahay.core.contracts.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataStoreUiPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UiPreferences {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Starts as SYSTEM and switches as soon as the stored value is read (a few ms).
    override val themeMode: StateFlow<ThemeMode> = dataStore.data
        .map { prefs -> ThemeMode.entries.firstOrNull { it.name == prefs[KEY] } ?: ThemeMode.SYSTEM }
        .stateIn(scope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY] = mode.name }
    }

    private companion object {
        val KEY = stringPreferencesKey("theme_mode")
    }
}
