package com.sahay.engine.state

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.LocationFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineStateStoreTest {

    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "engine.preferences_pb") } }
    private val store by lazy { EngineStateStore(dataStore) }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun emptyStoreHasNoFixAndEmergencyOff() = runBlocking {
        assertNull(store.readLastFix())
        assertFalse(store.readEmergencyActive())
    }

    @Test
    fun lastFixRoundTrips() = runBlocking {
        val fix = LocationFix(GeoPoint(12.6208, 80.1945), accuracyM = 8.5f, timeMs = 1_760_000_000_000L)
        assertTrue(store.writeLastFix(fix))
        assertEquals(fix, store.readLastFix())
    }

    @Test
    fun emergencyFlagRoundTrips() = runBlocking {
        assertTrue(store.writeEmergencyActive(true))
        assertTrue(store.readEmergencyActive())
        assertTrue(store.writeEmergencyActive(false))
        assertFalse(store.readEmergencyActive())
    }

    @Test
    fun aDamagedFixIsIgnored() = runBlocking {
        // Latitude without the other fields, as a half-written record would look.
        dataStore.edit { it[doublePreferencesKey("last_fix_lat")] = 12.0 }
        assertNull(store.readLastFix())
    }

    @Test
    fun anOutOfRangeFixIsIgnored() = runBlocking {
        store.writeLastFix(LocationFix(GeoPoint(123.0, 80.0), 5f, 1L))
        assertNull(store.readLastFix())
    }
}
