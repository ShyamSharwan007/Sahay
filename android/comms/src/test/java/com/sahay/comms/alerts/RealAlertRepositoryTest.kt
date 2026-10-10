package com.sahay.comms.alerts

import org.robolectric.RuntimeEnvironment
import com.sahay.comms.net.TranslateDto
import com.sahay.comms.sms.SmsOutcome
import com.sahay.comms.sms.IncomingSms
import com.sahay.comms.wire.TestVectors
import com.sahay.core.contracts.AlertSource
import com.sahay.core.contracts.Verification
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealAlertRepositoryTest {

    private lateinit var harness: AlertHarness
    private val validAlert get() = TestVectors.firstValid("A")

    private fun newHarness(language: String = "en", online: Boolean = false, hasPack: Boolean = true) =
        AlertHarness(RuntimeEnvironment.getApplication(), language, online, hasPack).also {
            if (::harness.isInitialized) harness.close()
            harness = it
        }

    @Before fun setUp() { newHarness() }

    @After fun tearDown() = harness.close()

    // ------------------------------------------------------------------ refresh

    @Test fun `refresh stores a verified alert with wording in the user's language and English`() = runBlocking {
        newHarness(language = "de").api.alertWires = listOf(validAlert)

        assertTrue(harness.repository.refresh().isSuccess)

        val alert = harness.repository.alerts.await { it.size == 1 }.single()
        assertEquals("a1b2c3", alert.id)
        assertEquals("FLD_EVAC", alert.templateCode)
        assertEquals(3, alert.severity)
        assertEquals(12.6208, alert.area!!.lat, 1e-9)
        assertEquals(2000, alert.radiusM)
        assertTrue(alert.isSimulation)
        assertEquals(1_760_000_000L, alert.issuedAtEpochSec)
        assertEquals("Hochwasser: sofort in Sicherheit", alert.title)
        assertEquals("Flood: go to safety", alert.titleEn)
        assertEquals("Move to higher ground or a shelter now.", alert.bodyEn)
        assertEquals(AlertSource.INTERNET, alert.source)
        assertEquals(Verification.VERIFIED_OFFICIAL, alert.verification)
        assertFalse(alert.read)
        assertEquals(1, harness.repository.unreadCount.await { it == 1 })
        assertEquals(listOf(alert.id), harness.notifier.shown.map { it.id })
    }

    @Test fun `same alert from internet and SMS is stored and notified once`() = runBlocking {
        harness.api.alertWires = listOf(validAlert, validAlert)

        harness.repository.refresh()
        harness.repository.refresh()
        assertEquals(SmsOutcome.DUPLICATE, harness.processor.handle(IncomingSms("+911234567890", "  $validAlert\n")))

        assertEquals(1, harness.repository.alerts.await { it.isNotEmpty() }.size)
        assertEquals(1, harness.db.alertDao().observeUnreadCountOnce())
        assertEquals(1, harness.notifier.shown.size)
    }

    @Test fun `SMS first, then internet, is also stored once and keeps the first source`() = runBlocking {
        assertEquals(SmsOutcome.SERVER_ALERT, harness.processor.handle(IncomingSms("+911234567890", validAlert)))
        harness.api.alertWires = listOf(validAlert)

        harness.repository.refresh()

        val alerts = harness.repository.alerts.await { it.isNotEmpty() }
        assertEquals(1, alerts.size)
        assertEquals(AlertSource.SMS_SERVER, alerts.single().source)
        assertEquals(1, harness.notifier.shown.size)
    }

    @Test fun `refresh drops anything that is not a valid signed alert`() = runBlocking {
        val tampered = TestVectors.cases.first { it.note.contains("tampered") }.wire
        val badSignature = TestVectors.cases.first { it.note.contains("corrupted") }.wire
        val shelter = TestVectors.firstValid("S")
        harness.api.alertWires = listOf(tampered, badSignature, shelter, "garbage", "", null, validAlert)

        assertTrue(harness.repository.refresh().isSuccess)

        assertEquals(listOf("a1b2c3"), harness.repository.alerts.await { it.isNotEmpty() }.map { it.id })
    }

    @Test fun `refresh asks for the active region and only recent alerts`() = runBlocking {
        harness.repository.refresh()
        harness.repository.refresh()

        val now = TestVectors.NOW_SEC
        assertEquals("mahabalipuram", harness.api.alertRequests[0].first)
        assertEquals(now - 48 * 3600, harness.api.alertRequests[0].second)
        assertEquals(now - 5 * 60, harness.api.alertRequests[1].second)    // since last refresh, with 5 min overlap
    }

    @Test fun `refresh without a trip pack does nothing and does not fail`() = runBlocking {
        newHarness(hasPack = false)

        assertTrue(harness.repository.refresh().isSuccess)
        assertTrue(harness.api.alertRequests.isEmpty())
    }

    @Test fun `refresh returns a failure when the server fails and keeps stored alerts`() = runBlocking {
        harness.api.alertWires = listOf(validAlert)
        harness.repository.refresh()
        harness.api.failAlerts = true

        assertTrue(harness.repository.refresh().isFailure)
        assertEquals(1, harness.repository.alerts.await { it.isNotEmpty() }.size)
    }

    @Test fun `alerts are listed newest first`() = runBlocking {
        val dao = harness.db.alertDao()
        suspend fun add(key: String, issuedAt: Long) = dao.insertIfNew(entity(key, issuedAt))
        add("old", 100); add("new", 300); add("mid", 200)

        assertEquals(listOf("new", "mid", "old"), harness.repository.alerts.await { it.size == 3 }.map { it.id })
    }

    // ------------------------------------------------------------------ read state

    @Test fun `markRead clears the unread count and flags the alert`() = runBlocking {
        harness.api.alertWires = listOf(validAlert)
        harness.repository.refresh()
        harness.repository.unreadCount.await { it == 1 }

        harness.repository.markRead("a1b2c3")

        assertEquals(0, harness.repository.unreadCount.await { it == 0 })
        assertTrue(harness.repository.alerts.await { list -> list.all { it.read } }.single().read)
    }

    @Test fun `re-delivery of a read alert does not make it unread again`() = runBlocking {
        harness.api.alertWires = listOf(validAlert)
        harness.repository.refresh()
        harness.repository.alerts.await { it.isNotEmpty() }
        harness.repository.markRead("a1b2c3")
        harness.repository.unreadCount.await { it == 0 }

        harness.repository.refresh()

        assertEquals(0, harness.db.alertDao().observeUnreadCountOnce())
    }

    // ------------------------------------------------------------------ pasted text

    @Test fun `offline paste with no match explains itself and is not stored`() = runBlocking {
        val result = harness.repository.translatePasted("Hello, how are you?")

        assertEquals("Couldn't translate offline. Try again when connected.", result.body)
        assertEquals(AlertSource.PASTED, result.source)
        assertEquals(Verification.UNVERIFIED, result.verification)
        assertEquals("Hello, how are you?", result.originalText)
        assertTrue(harness.repository.alerts.value.isEmpty())
    }

    @Test fun `offline paste that matches keywords uses the pack wording and is stored once`() = runBlocking {
        val text = "Cyclone warning for the coast. Please be careful."

        val first = harness.repository.translatePasted(text)
        val second = harness.repository.translatePasted(text)

        assertEquals("CYC_WARN", first.templateCode)
        assertEquals("Cyclone warning", first.title)
        assertEquals("Stay indoors. Secure your belongings.", first.body)
        assertEquals(2, first.severity)
        assertEquals(Verification.UNVERIFIED, first.verification)
        assertEquals(text, first.originalText)
        assertEquals(first.id, second.id)
        val stored = harness.repository.alerts.await { it.isNotEmpty() }
        assertEquals(1, stored.size)
        assertTrue("pasted text counts as already seen", stored.single().read)
        assertEquals(0, harness.notifier.shown.size)
    }

    @Test fun `online paste uses the server translation and the matched template`() = runBlocking {
        newHarness(language = "de", online = true)
        harness.api.translateResult = TranslateDto("ta", "Flooding is likely.", "Hochwasser ist wahrscheinlich.", "FLD_EVAC")

        val result = harness.repository.translatePasted("  something in Tamil  ")

        assertEquals(listOf("something in Tamil" to "de"), harness.api.translateRequests)
        assertEquals("Hochwasser ist wahrscheinlich.", result.body)
        assertEquals("Flooding is likely.", result.bodyEn)
        assertEquals("Hochwasser: sofort in Sicherheit", result.title)
        assertEquals(3, result.severity)
        assertEquals(Verification.UNVERIFIED, result.verification)
    }

    @Test fun `online paste falls back to the offline match when the server fails`() = runBlocking {
        newHarness(online = true)
        harness.api.translateResult = null

        val result = harness.repository.translatePasted("Flood warning, stay safe")

        assertEquals("FLD_WARN", result.templateCode)
        assertEquals(1, harness.api.translateRequests.size)
    }

    @Test fun `paste never throws, even for empty or huge input`() = runBlocking {
        assertNotNull(harness.repository.translatePasted(""))
        assertNotNull(harness.repository.translatePasted("   "))
        val huge = harness.repository.translatePasted("flood warning ".repeat(10_000))
        assertTrue((huge.originalText ?: "").length <= 2_000)
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun AlertDao.observeUnreadCountOnce(): Int = observeUnreadCount().first()

    private fun entity(key: String, issuedAt: Long) = AlertEntity(
        dedupeKey = key, alertId = key, templateCode = null, severity = 1, lat = null, lon = null, radiusM = null,
        issuedAtEpochSec = issuedAt, isSimulation = false, title = key, body = key, titleEn = key, bodyEn = key,
        originalText = null, source = AlertSource.INTERNET.name, verification = Verification.UNVERIFIED.name,
        receivedAtEpochSec = issuedAt, read = false,
    )
}
