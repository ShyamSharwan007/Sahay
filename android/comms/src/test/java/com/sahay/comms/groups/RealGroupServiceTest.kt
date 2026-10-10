package com.sahay.comms.groups

import com.sahay.comms.net.ApiException
import com.sahay.comms.net.GroupDto
import com.sahay.comms.net.GroupsApi
import com.sahay.comms.net.GroupsDto
import com.sahay.comms.net.PresenceRequest
import com.sahay.comms.sms.GroupWireCache
import com.sahay.comms.wire.WireMessage
import com.sahay.core.contracts.AuthTokenProvider
import com.sahay.core.contracts.Channel
import com.sahay.core.contracts.EmergencyModeController
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.GroupStatus
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.UserProfile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Time is virtual: the clock follows the test scheduler, so `advanceTimeBy` moves both delays and "now". */
@OptIn(ExperimentalCoroutinesApi::class)
class RealGroupServiceTest {

    private class VirtualClock(private val scheduler: TestCoroutineScheduler) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(START_MS + scheduler.currentTime)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private class FakeGroupsApi : GroupsApi {
        class Presence(val request: PresenceRequest, val token: String)

        val presences = mutableListOf<Presence>()
        val groupCalls = mutableListOf<GroupCall>()
        var presenceFailures = 0                              // the next n heartbeats fail
        var rejectFirstToken = false                          // first heartbeat answers 401
        var answer = GroupsDto(emptyList(), 5)
        var groupsFail: Exception? = null

        class GroupCall(val lat: Double, val lon: Double, val radiusM: Int, val token: String?)

        override suspend fun postPresence(request: PresenceRequest, idToken: String) {
            if (rejectFirstToken && presences.none { it.token != "tok-1" } && idToken == "tok-1") {
                presences += Presence(request, idToken)
                throw ApiException("HTTP 401", 401)
            }
            if (presenceFailures > 0) {
                presenceFailures--
                throw ApiException("HTTP 503", 503)
            }
            presences += Presence(request, idToken)
        }

        override suspend fun groups(lat: Double, lon: Double, radiusM: Int, idToken: String?): GroupsDto {
            groupCalls += GroupCall(lat, lon, radiusM, idToken)
            groupsFail?.let { throw it }
            return answer
        }
    }

    private class FakeAuth : AuthTokenProvider {
        var token: String? = "tok-1"
        override val uid: String? = "u1"
        override suspend fun idToken(forceRefresh: Boolean): String? = if (forceRefresh) "tok-2" else token
    }

    private class Rig(scope: TestScope, optedIn: Boolean, active: Boolean) {
        val clock = VirtualClock(scope.testScheduler)
        val api = FakeGroupsApi()
        val auth = FakeAuth()
        val cache = GroupWireCache()
        val online = booleanArrayOf(true)
        val emergencyActive = MutableStateFlow(active)
        val profile = MutableStateFlow<UserProfile?>(profile(optedIn))
        val lastFix = MutableStateFlow<LocationFix?>(fix(12.62084, 80.19456))

        val location = mockk<LocationProvider> {
            every { this@mockk.lastFix } returns this@Rig.lastFix
            coEvery { currentFix(any()) } returns null
        }
        val service = RealGroupService(
            api = api,
            auth = auth,
            profileStore = mockk<ProfileStore> { every { this@mockk.profile } returns this@Rig.profile },
            emergency = mockk<EmergencyModeController> { every { isActive } returns emergencyActive },
            location = location,
            isOnline = { online[0] },
            groupCache = cache,
            clock = clock,
            scope = scope.backgroundScope,
            presenceIntervalMs = RealGroupService.PRESENCE_INTERVAL_MS,
            presenceRetryMs = RealGroupService.PRESENCE_RETRY_MS,
            tickMs = RealGroupService.TICK_MS,
        )

        fun fix(lat: Double, lon: Double, atMs: Long = START_MS) = LocationFix(GeoPoint(lat, lon), 8f, atMs)
        val nowSec get() = clock.instant().epochSecond
    }

    // ------------------------------------------------------------------ presence cadence

    @Test fun `no heartbeat while Emergency Mode is off`() = runTest {
        val rig = Rig(this, optedIn = true, active = false)
        advanceTimeBy(20 * MINUTE); runCurrent()
        assertTrue(rig.api.presences.isEmpty())
    }

    @Test fun `no heartbeat without the group finder opt-in`() = runTest {
        val rig = Rig(this, optedIn = false, active = true)
        advanceTimeBy(20 * MINUTE); runCurrent()
        assertTrue(rig.api.presences.isEmpty())
    }

    @Test fun `first heartbeat at once, then every 5 minutes`() = runTest {
        val rig = Rig(this, optedIn = true, active = true)
        runCurrent()
        assertEquals(1, rig.api.presences.size)

        advanceTimeBy(5 * MINUTE - 1); runCurrent()
        assertEquals(1, rig.api.presences.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, rig.api.presences.size)

        advanceTimeBy(5 * MINUTE); runCurrent()
        assertEquals(3, rig.api.presences.size)
    }

    @Test fun `heartbeat carries the position rounded to 3 decimals and the login token`() = runTest {
        val rig = Rig(this, optedIn = true, active = true)
        runCurrent()
        val sent = rig.api.presences.single()
        assertEquals(12.621, sent.request.lat, 0.0)
        assertEquals(80.195, sent.request.lon, 0.0)
        assertEquals("tok-1", sent.token)
    }

    @Test fun `heartbeats stop when Emergency Mode ends and resume when it starts again`() = runTest {
        val rig = Rig(this, optedIn = true, active = true)
        runCurrent()
        rig.emergencyActive.value = false
        advanceTimeBy(20 * MINUTE); runCurrent()
        assertEquals(1, rig.api.presences.size)

        rig.lastFix.value = rig.fix(12.62084, 80.19456, atMs = START_MS + 20 * MINUTE)   // the phone kept tracking meanwhile
        rig.emergencyActive.value = true
        runCurrent()
        assertEquals(2, rig.api.presences.size)
    }

    @Test fun `heartbeats stop when the user withdraws the opt-in`() = runTest {
        val rig = Rig(this, optedIn = true, active = true)
        runCurrent()
        rig.profile.value = profile(optedIn = false)
        advanceTimeBy(20 * MINUTE); runCurrent()
        assertEquals(1, rig.api.presences.size)
    }

    @Test fun `offline heartbeat is skipped and retried after a minute, then the normal cadence resumes`() = runTest {
        val rig = Rig(this, optedIn = true, active = true)
        rig.online[0] = false
        runCurrent()
        assertTrue(rig.api.presences.isEmpty())

        rig.online[0] = true
        advanceTimeBy(MINUTE - 1); runCurrent()
        assertTrue(rig.api.presences.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(1, rig.api.presences.size)

        advanceTimeBy(5 * MINUTE); runCurrent()
        assertEquals(2, rig.api.presences.size)
    }

    @Test fun `failed heartbeat is retried after a minute, not five`() = runTest {
        val rig = Rig(this, optedIn = true, active = true)
        rig.api.presenceFailures = 1
        runCurrent()
        assertTrue(rig.api.presences.isEmpty())

        advanceTimeBy(MINUTE); runCurrent()
        assertEquals(1, rig.api.presences.size)
    }

    @Test fun `expired token is refreshed once`() = runTest {
        val rig = Rig(this, optedIn = true, active = true)
        rig.api.rejectFirstToken = true
        runCurrent()
        assertEquals(listOf("tok-1", "tok-2"), rig.api.presences.map { it.token })
    }

    @Test fun `signed out means no heartbeat`() = runTest {
        val rig = Rig(this, optedIn = true, active = true)
        rig.auth.token = null
        runCurrent()
        assertTrue(rig.api.presences.isEmpty())
    }

    @Test fun `an old position is not sent, a fresh quick fix is`() = runTest {
        val rig = Rig(this, optedIn = true, active = true)
        rig.lastFix.value = rig.fix(12.0, 80.0, atMs = START_MS - 11 * MINUTE)
        runCurrent()
        assertTrue(rig.api.presences.isEmpty())             // no fix at all: skipped, nothing wrong is sent

        coEvery { rig.location.currentFix(any()) } returns rig.fix(12.5, 80.5)
        advanceTimeBy(MINUTE); runCurrent()
        assertEquals(12.5, rig.api.presences.single().request.lat, 0.0)
    }

    // ------------------------------------------------------------------ refresh and snapshot

    @Test fun `refresh asks for 3 km around the rounded position and takes minSize from the server`() = runTest {
        val rig = Rig(this, optedIn = false, active = false)
        rig.api.answer = GroupsDto(listOf(dto("g_1", rig.nowSec - 60)), minSize = 3)
        runCurrent()

        assertTrue(rig.service.refresh(GeoPoint(12.62084, 80.19456)).isSuccess)

        val call = rig.api.groupCalls.single()
        assertEquals(12.621, call.lat, 0.0)
        assertEquals(80.195, call.lon, 0.0)
        assertEquals(3000, call.radiusM)
        assertEquals("tok-1", call.token)
        runCurrent()
        val snapshot = rig.service.snapshot.value
        assertEquals(3, snapshot.minGroupSize)
        assertEquals(listOf("g_1"), snapshot.groups.map { it.id })
        assertEquals(Channel.INTERNET, snapshot.groups.single().channel)
        assertEquals(0, snapshot.nearbyAppUsers)
        assertTrue(snapshot.beacons.isEmpty())
    }

    @Test fun `snapshot merges server groups with SMS groups`() = runTest {
        val rig = Rig(this, optedIn = false, active = false)
        rig.api.answer = GroupsDto(listOf(dto("g_1", rig.nowSec - 60, lat = 12.621)), 5)
        rig.cache.put(smsGroup(lat = 12.700, lon = 80.100, ts = rig.nowSec - 120))
        runCurrent()
        rig.service.refresh(GeoPoint(12.62, 80.19))
        runCurrent()

        val groups = rig.service.snapshot.value.groups
        assertEquals(2, groups.size)
        assertEquals(setOf(Channel.INTERNET, Channel.SMS), groups.map { it.channel }.toSet())
    }

    @Test fun `offline refresh fails but the snapshot still shows SMS groups`() = runTest {
        val rig = Rig(this, optedIn = false, active = false)
        rig.online[0] = false
        rig.cache.put(smsGroup(ts = rig.nowSec - 30))
        runCurrent()

        assertTrue(rig.service.refresh(GeoPoint(12.62, 80.19)).isFailure)
        assertTrue(rig.api.groupCalls.isEmpty())
        assertEquals(1, rig.service.snapshot.value.groups.size)
    }

    @Test fun `server failure keeps the previous snapshot`() = runTest {
        val rig = Rig(this, optedIn = false, active = false)
        rig.api.answer = GroupsDto(listOf(dto("g_1", rig.nowSec - 10)), 5)
        rig.service.refresh(GeoPoint(12.62, 80.19))
        runCurrent()

        rig.api.groupsFail = IOException("timeout")
        assertTrue(rig.service.refresh(GeoPoint(12.62, 80.19)).isFailure)
        runCurrent()
        assertEquals(listOf("g_1"), rig.service.snapshot.value.groups.map { it.id })
    }

    @Test fun `bad group rows from the server are dropped, good ones kept`() = runTest {
        val rig = Rig(this, optedIn = false, active = false)
        rig.api.answer = GroupsDto(
            listOf(
                dto("ok", rig.nowSec - 10, lat = 12.621),
                dto("weird_status", rig.nowSec - 10, lat = 12.630, status = "PARTY"),
                dto("empty", rig.nowSec - 10, lat = 12.640, size = 0),
                dto("bad_lat", rig.nowSec - 10, lat = 912.0),
            ),
            minSize = 0,
        )
        rig.service.refresh(GeoPoint(12.62, 80.19))
        runCurrent()

        val snapshot = rig.service.snapshot.value
        assertEquals(listOf("ok"), snapshot.groups.map { it.id })
        assertEquals(1, snapshot.minGroupSize)            // never below 1
    }

    @Test fun `snapshot goes stale after 15 minutes and loses the group after 30, on its own`() = runTest {
        val rig = Rig(this, optedIn = false, active = false)
        rig.api.answer = GroupsDto(listOf(dto("g_1", rig.nowSec - 30)), 5)
        rig.service.refresh(GeoPoint(12.62, 80.19))
        runCurrent()
        assertFalse(rig.service.snapshot.value.isStale)

        advanceTimeBy(16 * MINUTE); runCurrent()
        assertTrue(rig.service.snapshot.value.isStale)
        assertEquals(1, rig.service.snapshot.value.groups.size)

        advanceTimeBy(15 * MINUTE); runCurrent()
        assertTrue(rig.service.snapshot.value.groups.isEmpty())
    }

    @Test fun `beacons are off`() = runTest {
        val rig = Rig(this, optedIn = false, active = false)
        rig.service.setBeacon(true, GeoPoint(12.62, 80.19))
        runCurrent()
        assertFalse(rig.service.myBeaconActive.value)
        assertTrue(rig.service.snapshot.value.beacons.isEmpty())
        assertNull(rig.service.snapshot.value.updatedAtEpochSec)
    }

    // ------------------------------------------------------------------ helpers

    private companion object {
        const val START_MS = 1_760_000_000_000L
        const val MINUTE = 60_000L

        fun dto(id: String, lastSeen: Long, lat: Double = 12.621, lon: Double = 80.193, size: Int = 7, status: String = "AT_SHELTER") =
            GroupDto(id, lat, lon, size, status, lastSeen)

        fun smsGroup(lat: Double = 12.621, lon: Double = 80.193, ts: Long) =
            WireMessage.Group(lat, lon, 7, GroupStatus.AT_SHELTER, ts, "sig")

        fun profile(optedIn: Boolean) = UserProfile(
            uid = "u1", isGuest = true, displayName = "Test", email = null, photoUrl = null, language = "en",
            nationality = null, phone = null, bloodGroup = null, allergies = null, medications = null, conditions = null,
            hotelName = null, hotelAddress = null, contacts = emptyList(), smsAlertsOptIn = false,
            groupFinderOptIn = optedIn, onboardingComplete = true,
        )
    }
}
