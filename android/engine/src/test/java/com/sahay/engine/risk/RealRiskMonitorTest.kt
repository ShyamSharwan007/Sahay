package com.sahay.engine.risk

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.LocationMode
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Scripted location source: tests push fixes into [fixes]; [emptyRuns] simulates "no permission" (flow ends at once). */
private class ScriptedLocation : LocationProvider {
    val fixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = 16)
    var emptyRuns = 0
    var updatesCalls = 0

    override val lastFix: StateFlow<LocationFix?> = MutableStateFlow(null)
    override fun hasPermission() = true
    override fun updates(mode: LocationMode): Flow<LocationFix> {
        updatesCalls++
        require(mode == LocationMode.BALANCED)
        return if (emptyRuns-- > 0) emptyFlow() else fixes
    }
    override suspend fun currentFix(timeoutMs: Long): LocationFix? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealRiskMonitorTest {

    private val inside = GeoPoint(0.5, 0.5)
    private val outside = GeoPoint(5.0, 5.0)
    private val highZone = RiskZone(
        "h", "Low coast", RiskLevel.HIGH,
        listOf(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0), GeoPoint(1.0, 1.0), GeoPoint(1.0, 0.0))),
    )

    private var notifications = 0
    private var now = 0L

    private fun TestScope.monitor(location: LocationProvider, zones: List<RiskZone> = listOf(highZone)) = RealRiskMonitor(
        location = location,
        zones = RiskZoneCache(packKey = { "pack" }, loadZones = { zones }),
        notifier = { notifications++ },
        scope = backgroundScope,
        clockMs = { now },
        retryDelayMs = 30_000L,
    )

    private fun fix(at: GeoPoint, accuracyM: Float = 10f) = LocationFix(at, accuracyM, timeMs = now)

    @Test
    fun enteringAHighZoneUpdatesTheZoneAndNotifiesOnce() = runTest {
        val location = ScriptedLocation()
        val monitor = monitor(location)
        monitor.startMonitoring()
        runCurrent()

        location.fixes.tryEmit(fix(outside)); runCurrent()
        assertNull(monitor.currentZone.value)
        assertEquals(0, notifications)

        now += 60_000
        location.fixes.tryEmit(fix(inside)); runCurrent()
        assertEquals("h", monitor.currentZone.value?.id)
        assertEquals(1, notifications)

        now += 60_000
        location.fixes.tryEmit(fix(inside)); runCurrent()          // still inside: no second notification
        assertEquals(1, notifications)
    }

    @Test
    fun startingInsideAHighZoneShowsTheZoneButDoesNotNotify() = runTest {
        val location = ScriptedLocation()
        val monitor = monitor(location)
        monitor.startMonitoring()
        runCurrent()

        location.fixes.tryEmit(fix(inside)); runCurrent()
        assertEquals("h", monitor.currentZone.value?.id)
        assertEquals(0, notifications)
    }

    @Test
    fun aRoughFixUpdatesTheZoneButNeverNotifies() = runTest {
        val location = ScriptedLocation()
        val monitor = monitor(location)
        monitor.startMonitoring()
        runCurrent()

        location.fixes.tryEmit(fix(outside)); runCurrent()
        location.fixes.tryEmit(fix(inside, accuracyM = 2_000f)); runCurrent()
        assertEquals("h", monitor.currentZone.value?.id)
        assertEquals(0, notifications)
    }

    @Test
    fun leavingTheZoneClearsCurrentZone() = runTest {
        val location = ScriptedLocation()
        val monitor = monitor(location)
        monitor.startMonitoring()
        runCurrent()

        location.fixes.tryEmit(fix(inside)); runCurrent()
        location.fixes.tryEmit(fix(outside)); runCurrent()
        assertNull(monitor.currentZone.value)
    }

    @Test
    fun stopMonitoringStopsListeningAndClearsTheZone() = runTest {
        val location = ScriptedLocation()
        val monitor = monitor(location)
        monitor.startMonitoring()
        runCurrent()
        location.fixes.tryEmit(fix(inside)); runCurrent()

        monitor.stopMonitoring()
        runCurrent()
        assertNull(monitor.currentZone.value)
        assertEquals(0, location.fixes.subscriptionCount.value)

        location.fixes.tryEmit(fix(inside)); runCurrent()
        assertNull(monitor.currentZone.value)
    }

    @Test
    fun startingTwiceKeepsASingleCollector() = runTest {
        val location = ScriptedLocation()
        val monitor = monitor(location)
        monitor.startMonitoring()
        monitor.startMonitoring()
        runCurrent()

        assertEquals(1, location.fixes.subscriptionCount.value)
        assertEquals(1, location.updatesCalls)
    }

    @Test
    fun canBeRestartedAfterStop() = runTest {
        val location = ScriptedLocation()
        val monitor = monitor(location)
        monitor.startMonitoring(); runCurrent()
        monitor.stopMonitoring(); runCurrent()
        monitor.startMonitoring(); runCurrent()

        location.fixes.tryEmit(fix(inside)); runCurrent()
        assertEquals("h", monitor.currentZone.value?.id)
    }

    @Test
    fun retriesWhenTheLocationFlowEndsAtOnce() = runTest {
        val location = ScriptedLocation().apply { emptyRuns = 1 }          // e.g. permission not granted yet
        val monitor = monitor(location)
        monitor.startMonitoring()
        runCurrent()
        assertEquals(1, location.updatesCalls)

        advanceTimeBy(30_001); runCurrent()                                // user granted it meanwhile
        assertEquals(2, location.updatesCalls)
        location.fixes.tryEmit(fix(inside)); runCurrent()
        assertEquals("h", monitor.currentZone.value?.id)
    }

    @Test
    fun zoneAtUsesTheCachedZonesAndIgnoresGarbagePoints() = runTest {
        val monitor = monitor(ScriptedLocation())
        assertEquals("h", monitor.zoneAt(inside)?.id)
        assertNull(monitor.zoneAt(outside))
        assertNull(monitor.zoneAt(GeoPoint(Double.NaN, Double.NaN)))
    }

    @Test
    fun withoutAnyZonesNothingHappens() = runTest {
        val location = ScriptedLocation()
        val monitor = monitor(location, zones = emptyList())
        monitor.startMonitoring()
        runCurrent()

        location.fixes.tryEmit(fix(inside)); runCurrent()
        assertNull(monitor.currentZone.value)
        assertEquals(0, notifications)
    }
}
