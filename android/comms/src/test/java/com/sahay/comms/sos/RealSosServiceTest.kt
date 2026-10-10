package com.sahay.comms.sos

import com.sahay.core.contracts.EmergencyContact
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.UserProfile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)      // currentTime
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealSosServiceTest {

    /** Records every send; [behaviour] decides what the "network" answers per phone number. */
    private class FakeDispatcher(
        var unavailable: SmsFailure? = null,
        var behaviour: suspend (String) -> SmsFailure? = { null },
    ) : SmsDispatcher {
        val sent = mutableListOf<Pair<String, String>>()
        override fun unavailableReason() = unavailable
        override suspend fun send(phone: String, text: String): SmsFailure? {
            sent += phone to text
            return behaviour(phone)
        }
    }

    private val fix = LocationFix(GeoPoint(12.6208, 80.1945), 12f, 1_760_000_000_000L)

    private fun profile(contacts: List<String>, language: String = "en") = UserProfile(
        uid = "u1", isGuest = false, displayName = "Anna Schmidt", email = null, photoUrl = null, language = language,
        nationality = "DE", phone = null, bloodGroup = "O+", allergies = "penicillin", medications = null, conditions = null,
        hotelName = "Hotel Sea View", hotelAddress = null,
        contacts = contacts.map { EmergencyContact("Contact", it, "friend") },
        smsAlertsOptIn = false, groupFinderOptIn = false, onboardingComplete = true,
    )

    private fun service(
        user: UserProfile?,
        dispatcher: SmsDispatcher,
        lastKnown: LocationFix? = fix,
        freshFix: suspend () -> LocationFix? = { fix },
        permission: Boolean = true,
    ): RealSosService {
        val store = mockk<ProfileStore> { every { profile } returns MutableStateFlow(user) }
        val location = mockk<LocationProvider> {
            every { lastFix } returns MutableStateFlow(lastKnown)
            every { hasPermission() } returns permission
            coEvery { currentFix(any()) } coAnswers { freshFix() }
        }
        return RealSosService(
            RuntimeEnvironment.getApplication(), store, location, dispatcher, { ZoneId.of("Asia/Kolkata") },
        )
    }

    @Test fun `sends the previewed text to every contact`() = runTest {
        val dispatcher = FakeDispatcher()
        val sos = service(profile(listOf("+4915100000001", "+91 98765 43210")), dispatcher)

        val preview = sos.previewMessage()
        val result = sos.sendSos()

        assertEquals(preview, result.message)
        assertEquals(listOf("+4915100000001", "+919876543210"), result.sentTo)
        assertTrue(result.failed.isEmpty())
        assertEquals(GeoPoint(12.6208, 80.1945), result.includedLocation)
        assertEquals(listOf("+4915100000001", "+919876543210"), dispatcher.sent.map { it.first })
        assertTrue(dispatcher.sent.all { it.second == result.message })
        assertTrue(result.message, result.message.contains("https://maps.google.com/?q=12.62080,80.19450"))
    }

    @Test fun `duplicate numbers are texted once`() = runTest {
        val dispatcher = FakeDispatcher()
        val result = service(profile(listOf("+4915100000001", "+49 151 0000 0001", " ")), dispatcher).sendSos()

        assertEquals(listOf("+4915100000001"), result.sentTo)
        assertEquals(1, dispatcher.sent.size)
    }

    @Test fun `one failing contact does not stop the others and says why`() = runTest {
        val dispatcher = FakeDispatcher(behaviour = { if (it == "+4915100000002") SmsFailure.NO_SERVICE else null })
        val result = service(profile(listOf("+4915100000001", "+4915100000002")), dispatcher).sendSos()

        assertEquals(listOf("+4915100000001"), result.sentTo)
        assertEquals(listOf("+4915100000002: No mobile signal."), result.failed)
    }

    @Test fun `failure reasons are written in the user's language`() = runTest {
        val dispatcher = FakeDispatcher(unavailable = SmsFailure.AIRPLANE_MODE)
        val result = service(profile(listOf("+4915100000001"), language = "de"), dispatcher).sendSos()

        assertEquals(listOf("+4915100000001: Flugmodus ist an."), result.failed)
    }

    @Test fun `no SIM, no permission or airplane mode fail every contact without trying to send`() = runTest {
        for (reason in listOf(SmsFailure.NO_SIM, SmsFailure.NO_PERMISSION, SmsFailure.AIRPLANE_MODE)) {
            val dispatcher = FakeDispatcher(unavailable = reason)
            val result = service(profile(listOf("+4915100000001", "+4915100000002")), dispatcher).sendSos()

            assertTrue(result.sentTo.isEmpty())
            assertEquals(2, result.failed.size)
            assertTrue(dispatcher.sent.isEmpty())
            assertTrue(result.message.isNotBlank())          // the user can still see what would have been sent
        }
    }

    @Test fun `a contact that never confirms is reported as failed after 20 seconds`() = runTest {
        val dispatcher = FakeDispatcher(behaviour = { if (it == "+4915100000002") awaitCancellation() else null })
        val result = service(profile(listOf("+4915100000001", "+4915100000002")), dispatcher).sendSos()

        assertEquals(listOf("+4915100000001"), result.sentTo)
        assertEquals(listOf("+4915100000002: No confirmation after 20 seconds."), result.failed)
        assertEquals(RealSosService.SEND_TIMEOUT_MS, currentTime)
    }

    @Test fun `contacts are texted at the same time`() = runTest {
        val dispatcher = FakeDispatcher(behaviour = { delay(10_000); null })
        service(profile(listOf("+4915100000001", "+4915100000002", "+4915100000003")), dispatcher).sendSos()

        assertEquals(10_000L, currentTime)
    }

    @Test fun `a number that is not a phone number fails as invalid and is never sent`() = runTest {
        val dispatcher = FakeDispatcher()
        val result = service(profile(listOf("abc", "+4915100000001")), dispatcher).sendSos()

        assertEquals(listOf("+4915100000001"), result.sentTo)
        assertEquals(listOf("abc: Invalid phone number."), result.failed)
        assertEquals(1, dispatcher.sent.size)
    }

    @Test fun `no contacts gives a clear failure instead of silence`() = runTest {
        val result = service(profile(emptyList()), FakeDispatcher()).sendSos()

        assertTrue(result.sentTo.isEmpty())
        assertEquals(listOf("No emergency contacts saved."), result.failed)
    }

    @Test fun `without a profile the SOS still builds a message`() = runTest {
        val sos = service(null, FakeDispatcher())

        assertTrue(sos.previewMessage().startsWith("SOS from a tourist via Sahay."))
        assertEquals(listOf("No emergency contacts saved."), sos.sendSos().failed)
    }

    // ------------------------------------------------------------------ location

    @Test fun `uses the last known location when no fresh fix arrives`() = runTest {
        val old = fix.copy(point = GeoPoint(12.5, 80.1))
        val sos = service(profile(listOf("+4915100000001")), FakeDispatcher(), lastKnown = old, freshFix = { null })

        val result = sos.sendSos()

        assertEquals(old.point, result.includedLocation)
        assertTrue(result.message, result.message.contains("?q=12.50000,80.10000"))
    }

    @Test fun `waits at most five seconds for a fresh fix`() = runTest {
        val sos = service(profile(listOf("+4915100000001")), FakeDispatcher(), freshFix = { awaitCancellation() })

        sos.sendSos()

        assertTrue("took ${currentTime} ms", currentTime <= 6_000L)
    }

    @Test fun `a location provider that throws does not break the SOS`() = runTest {
        val sos = service(profile(listOf("+4915100000001")), FakeDispatcher(), freshFix = { throw SecurityException("no permission") })

        val result = sos.sendSos()

        assertEquals(listOf("+4915100000001"), result.sentTo)
        assertNotNull(result.includedLocation)           // fell back to the last known fix
    }

    @Test fun `without location permission or any fix the message says the location is unavailable`() = runTest {
        val sos = service(profile(listOf("+4915100000001")), FakeDispatcher(), lastKnown = null, permission = false)

        val result = sos.sendSos()

        assertNull(result.includedLocation)
        assertTrue(result.message, result.message.contains("Location unavailable."))
        assertEquals(listOf("+4915100000001"), result.sentTo)
    }

    @Test fun `phone numbers are normalised`() {
        assertEquals("+919876543210", RealSosService.normalizePhone(" +91 (98765) 43-210 "))
        assertEquals("09876543210", RealSosService.normalizePhone("098-7654 3210"))
        assertNull(RealSosService.normalizePhone("112"))
        assertNull(RealSosService.normalizePhone("+1234567890123456"))
        assertNull(RealSosService.normalizePhone("call me"))
    }
}
