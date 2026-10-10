package com.sahay.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A [ProfileStore] that can also tell the caller when the first disk read has finished. */
interface LoadableProfileStore : ProfileStore {
    /** Reads the stored profile from disk. Use at start-up, because [profile] is null until the first read ends. */
    suspend fun awaitLoaded(): UserProfile?
}

/**
 * Stores the profile as one JSON string in DataStore. Medical details never leave the phone
 * (CONTRACTS §2), so this class has no network code.
 */
@Singleton
class DataStoreProfileStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : LoadableProfileStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val profile: StateFlow<UserProfile?> = dataStore.data
        .map { decode(it[KEY]) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun awaitLoaded(): UserProfile? = decode(dataStore.data.first()[KEY])

    override suspend fun save(profile: UserProfile) {
        val json = JSON.encodeToString(ProfileDto.serializer(), profile.toDto())
        dataStore.edit { it[KEY] = json }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    /** A damaged or unreadable file is treated as "no profile" so the user can set up again instead of crashing. */
    private fun decode(raw: String?): UserProfile? {
        if (raw.isNullOrBlank()) return null
        return try {
            JSON.decodeFromString(ProfileDto.serializer(), raw).toProfile()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("profile_json")
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
