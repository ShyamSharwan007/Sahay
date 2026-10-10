package com.sahay.engine.state

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.LocationFix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException

/** The engine's own DataStore file. A separate file from :app's, so the two modules can never clash on keys. */
private val Context.engineDataStore: DataStore<Preferences> by preferencesDataStore(name = "sahay_engine")

/**
 * Small values the engine must remember across app restarts: the last location fix (so SOS has a position
 * right after a restart) and whether Emergency Mode is on.
 *
 * Reads never throw: an unreadable or corrupt file means "nothing stored". Writes report failure as `false`.
 */
internal class EngineStateStore(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.engineDataStore)

    suspend fun readEmergencyActive(): Boolean =
        read { it[EMERGENCY_ACTIVE] } ?: false

    suspend fun writeEmergencyActive(active: Boolean): Boolean =
        write { it[EMERGENCY_ACTIVE] = active }

    suspend fun readLastFix(): LocationFix? = read { prefs ->
        val lat = prefs[FIX_LAT]
        val lon = prefs[FIX_LON]
        val accuracy = prefs[FIX_ACCURACY]
        val timeMs = prefs[FIX_TIME_MS]
        // A partially written or damaged record is treated as "no fix" rather than a bogus position.
        if (lat == null || lon == null || accuracy == null || timeMs == null) return@read null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@read null
        LocationFix(GeoPoint(lat, lon), accuracy, timeMs)
    }

    suspend fun writeLastFix(fix: LocationFix): Boolean = write { prefs ->
        prefs[FIX_LAT] = fix.point.lat
        prefs[FIX_LON] = fix.point.lon
        prefs[FIX_ACCURACY] = fix.accuracyM
        prefs[FIX_TIME_MS] = fix.timeMs
    }

    private suspend fun <T> read(extract: (Preferences) -> T?): T? = try {
        extract(dataStore.data.first())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {            // IOException or CorruptionException from DataStore
        Log.w(TAG, "Could not read saved engine state: ${e.message}")
        null
    }

    private suspend fun write(change: (MutablePreferences) -> Unit): Boolean = try {
        dataStore.edit(change)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "Could not save engine state: ${e.message}")
        false
    }

    private companion object {
        const val TAG = "EngineStateStore"
        val EMERGENCY_ACTIVE = booleanPreferencesKey("emergency_active")
        val FIX_LAT = doublePreferencesKey("last_fix_lat")
        val FIX_LON = doublePreferencesKey("last_fix_lon")
        val FIX_ACCURACY = floatPreferencesKey("last_fix_accuracy_m")
        val FIX_TIME_MS = longPreferencesKey("last_fix_time_ms")
    }
}
