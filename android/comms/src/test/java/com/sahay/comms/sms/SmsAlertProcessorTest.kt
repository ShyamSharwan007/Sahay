package com.sahay.comms.sms

import org.robolectric.RuntimeEnvironment
import com.sahay.comms.alerts.AlertHarness
import com.sahay.comms.alerts.await
import com.sahay.comms.net.TranslateDto
import com.sahay.comms.wire.TestVectors
import com.sahay.core.contracts.AlertSource
import com.sahay.core.contracts.Channel
import com.sahay.core.contracts.GroupStatus
import com.sahay.core.contracts.ShelterStatus
import com.sahay.core.contracts.Verification
import io.mockk.coVerify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsAlertProcessorTest {

    private lateinit var harness: AlertHarness
    private val official = "Cyclone warning for Chengalpattu coast. Stay indoors."

    @Before fun setUp() {
        harness = AlertHarness(RuntimeEnvironment.getApplication())
    }

    @After fun tearDown() = harness.close()

    private suspend fun receive(body: String, sender: String? = "+911234567890") =
        harness.processor.handle(IncomingSms(sender, body))

    // ------------------------------------------------------------------ signed wires

    @Test fun `signed alert wire becomes a verified SMS_SERVER alert`() = runBlocking {
        assertEquals(SmsOutcome.SERVER_ALERT, receive(TestVectors.firstValid("A")))

        val alert = harness.repository.alerts.await { it.isNotEmpty() }.single()
        assertEquals(AlertSource.SMS_SERVER, alert.source)
        assertEquals(Verification.VERIFIED_OFFICIAL, alert.verification)
        assertEquals("Flood: go to safety", alert.title)
        assertEquals(1, harness.notifier.shown.size)
        assertEquals(SmsOutcome.DUPLICATE, receive(TestVectors.firstValid("A")))
        assertEquals(1, harness.notifier.shown.size)
    }

    @Test fun `signed shelter status is handed to the pack`() = runBlocking {
        assertEquals(SmsOutcome.SHELTER_STATUS, receive(TestVectors.firstValid("S")))

        coVerify { harness.pack.updateShelterStatus("poi_123", ShelterStatus.FULL, 1_760_000_000L) }
        assertTrue(harness.repository.alerts.value.isEmpty())
    }

    @Test fun `signed group goes to the in-memory group cache`() = runBlocking {
        assertEquals(SmsOutcome.GROUP, receive(TestVectors.firstValid("G")))

        val group = harness.groupCache.groups.value.single()
        assertEquals(7, group.size)
        assertEquals(GroupStatus.AT_SHELTER, group.status)
        assertEquals(Channel.SMS, group.channel)
        assertEquals(12.621, group.point.lat, 1e-9)
    }

    @Test fun `a newer group message replaces an older one for the same place`() {
        val old = com.sahay.comms.wire.WireMessage.Group(12.621, 80.193, 7, GroupStatus.AT_SHELTER, 100, "s")
        harness.groupCache.put(old)
        harness.groupCache.put(old.copy(size = 9, timestamp = 200))
        harness.groupCache.put(old.copy(size = 3, timestamp = 150))     // older than what we have: ignored

        assertEquals(listOf(9), harness.groupCache.groups.value.map { it.size })
    }

    @Test fun `invalid or unsupported SH1 messages are dropped`() = runBlocking {
        val tampered = TestVectors.cases.first { it.note.contains("tampered") }.wire
        val unsigned = "SH1*P*12.621,80.194*${TestVectors.NOW_SEC}*anon0000"

        assertEquals(SmsOutcome.DROPPED, receive(tampered))
        assertEquals(SmsOutcome.DROPPED, receive(unsigned))               // users' messages are not accepted by SMS
        assertEquals(SmsOutcome.DROPPED, receive("SH1*A*broken"))
        assertEquals(SmsOutcome.DROPPED, receive("SH1*"))
        assertTrue(harness.repository.alerts.value.isEmpty())
        assertTrue(harness.notifier.shown.isEmpty())
    }

    // ------------------------------------------------------------------ plain-text SMS

    @Test fun `official-looking SMS becomes an alert labelled as matched, not verified`() = runBlocking {
        assertEquals(SmsOutcome.OFFICIAL_ALERT, receive(official, sender = "VM-NDMAEW"))

        val alert = harness.repository.alerts.await { it.isNotEmpty() }.single()
        assertEquals(AlertSource.SMS_OFFICIAL, alert.source)
        assertEquals(Verification.MATCHED_OFFICIAL_SMS, alert.verification)
        assertEquals("CYC_WARN", alert.templateCode)
        assertEquals(official, alert.originalText)
        assertEquals("Cyclone warning", alert.title)
        assertEquals("Cyclone warning", alert.titleEn)
        assertEquals(2, alert.severity)
        assertEquals(1, harness.notifier.shown.size)
    }

    @Test fun `the same SMS twice gives one alert`() = runBlocking {
        assertEquals(SmsOutcome.OFFICIAL_ALERT, receive(official))
        assertEquals(SmsOutcome.DUPLICATE, receive("  $official \n"))

        assertEquals(1, harness.repository.alerts.await { it.isNotEmpty() }.size)
        assertEquals(1, harness.notifier.shown.size)
    }

    @Test fun `ordinary SMS is ignored`() = runBlocking {
        assertEquals(SmsOutcome.IGNORED, receive("Your OTP is 482913", sender = "AX-HDFCBK"))
        assertEquals(SmsOutcome.IGNORED, receive("Cyclone expected", sender = "+911234567890"))   // one hit, unknown sender
        assertEquals(SmsOutcome.IGNORED, receive(""))
        assertEquals(SmsOutcome.IGNORED, receive("   \n"))
        assertTrue(harness.repository.alerts.value.isEmpty())
    }

    @Test fun `without a trip pack plain SMS cannot be matched and signed alerts are dropped, nothing crashes`() = runBlocking {
        harness.close()
        harness = AlertHarness(RuntimeEnvironment.getApplication(), hasPack = false)
        io.mockk.coEvery { harness.pack.alertKeywords() } returns emptyList()

        assertEquals(SmsOutcome.IGNORED, receive(official, sender = "VM-NDMAEW"))
        assertEquals(SmsOutcome.DROPPED, receive(TestVectors.firstValid("A")))          // regional alerts need a trip pack
        assertTrue(harness.repository.alerts.value.isEmpty())
    }

    @Test fun `when online the server improves the wording in the background`() = runBlocking {
        harness.close()
        harness = AlertHarness(RuntimeEnvironment.getApplication(), online = true)
        harness.api.translateResult = TranslateDto("en", "A cyclone is coming. Stay inside.", "A cyclone is coming. Stay inside.", "CYC_WARN")

        assertEquals(SmsOutcome.OFFICIAL_ALERT, receive(official, sender = "VM-NDMAEW"))

        val improved = harness.repository.alerts.await { list -> list.any { it.body == "A cyclone is coming. Stay inside." } }
        assertEquals("A cyclone is coming. Stay inside.", improved.single().bodyEn)
        assertEquals("Cyclone warning", improved.single().title)        // title stays the template's
        assertEquals(listOf(official to "en"), harness.api.translateRequests)
    }

    @Test fun `if the server cannot improve the wording the template text stays`() = runBlocking {
        harness.close()
        harness = AlertHarness(RuntimeEnvironment.getApplication(), online = true)
        harness.api.translateResult = null

        assertEquals(SmsOutcome.OFFICIAL_ALERT, receive(official, sender = "VM-NDMAEW"))

        val alert = harness.repository.alerts.await { it.isNotEmpty() }.single()
        assertEquals("Stay indoors. Secure your belongings.", alert.body)
    }
}
