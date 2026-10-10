package com.sahay.engine.emergency

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.RiskMonitor
import com.sahay.core.contracts.RiskZone
import com.sahay.engine.state.EngineStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private class CountingRiskMonitor : RiskMonitor {
    val starts = AtomicInteger()
    val stops = AtomicInteger()
    override val currentZone: StateFlow<RiskZone?> = MutableStateFlow(null)
    override suspend fun zoneAt(point: GeoPoint): RiskZone? = null
    override fun startMonitoring() { starts.incrementAndGet() }
    override fun stopMonitoring() { stops.incrementAndGet() }
}

class RealEmergencyModeControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: EngineStateStore
    private val monitor = CountingRiskMonitor()
    private var inForeground = true

    @Before
    fun setUp() {
        file = File(tmp.root, "engine.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        store = EngineStateStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A controller as the app would create it; several can share one DataStore to simulate restarts. */
    private fun controller(withStore: EngineStateStore = store) = RealEmergencyModeController(
        store = withStore,
        riskMonitor = monitor,
        isAppInForeground = { inForeground },
        scope = scope,
    )

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }

    private fun persisted() = runBlocking { store.readEmergencyActive() }

    @Test
    fun startsInactiveWhenNothingWasSaved() {
        val controller = controller()
        Thread.sleep(100)                                   // give the restore a chance to (wrongly) switch it on
        assertFalse(controller.isActive.value)
        assertEquals(0, monitor.starts.get())
    }

    @Test
    fun activateTurnsOnStartsMonitoringAndIsSaved() {
        val controller = controller()
        controller.activate()

        assertTrue(controller.isActive.value)
        assertEquals(1, monitor.starts.get())
        awaitUntil { persisted() }
    }

    @Test
    fun stateSurvivesARestart() {
        controller().activate()
        awaitUntil { persisted() }
        val startsBefore = monitor.starts.get()

        val restarted = controller()                       // new process, same files
        awaitUntil { restarted.isActive.value }
        assertEquals(startsBefore + 1, monitor.starts.get())   // monitoring resumes with it
    }

    @Test
    fun deactivateIsAlsoSaved() {
        val first = controller()
        first.activate()
        awaitUntil { persisted() }
        first.deactivate()
        awaitUntil { !persisted() }

        val restarted = controller()
        Thread.sleep(100)
        assertFalse(restarted.isActive.value)
    }

    @Test
    fun deactivateInTheBackgroundStopsMonitoring() {
        val controller = controller()
        controller.activate()
        inForeground = false
        controller.deactivate()

        assertFalse(controller.isActive.value)
        assertEquals(1, monitor.stops.get())
    }

    @Test
    fun deactivateInTheForegroundLeavesMonitoringToTheScreens() {
        val controller = controller()
        controller.activate()
        inForeground = true
        controller.deactivate()

        assertFalse(controller.isActive.value)
        assertEquals(0, monitor.stops.get())
    }

    @Test
    fun aSlowRestoreDoesNotOverrideAnExplicitDeactivate() {
        runBlocking { store.writeEmergencyActive(true) }    // saved as "on" by the previous run

        val gate = CompletableDeferred<Unit>()
        val slowStore = EngineStateStore(object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { gate.await(); emitAll(dataStore.data) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = dataStore.updateData(transform)
        })
        val controller = controller(slowStore)
        controller.deactivate()                             // the user switches it off before the restore has read anything
        gate.complete(Unit)

        awaitUntil { !persisted() }                         // the "off" was saved ...
        Thread.sleep(100)
        assertFalse(controller.isActive.value)              // ... and the restore did not switch it back on
        assertEquals(0, monitor.starts.get())
    }

    @Test
    fun quickOnOffOnEndsWithTheLatestStateSaved() {
        val controller = controller()
        controller.activate()
        controller.deactivate()
        controller.activate()

        assertTrue(controller.isActive.value)
        awaitUntil { persisted() }
        Thread.sleep(100)
        assertTrue(persisted())
    }
}
