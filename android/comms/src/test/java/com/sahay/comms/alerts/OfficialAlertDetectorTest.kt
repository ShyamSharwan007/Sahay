package com.sahay.comms.alerts

import com.sahay.core.contracts.AlertKeyword
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialAlertDetectorTest {

    /** Small keyword set in the style of the pack's `alert_keyword` table (lower case, en / hi / ta). */
    private val keywords = listOf(
        kw("CYC_WARN", "en", "cyclone"), kw("CYC_WARN", "en", "warning"), kw("CYC_WARN", "en", "stay indoors"),
        kw("CYC_WARN", "hi", "चक्रवात"),                   // chakravat
        kw("CYC_WARN", "hi", "चेतावनी"),                   // chetavani
        kw("CYC_WARN", "ta", "புயல்"),                               // puyal
        kw("CYC_WARN", "ta", "எச்சரிக்கை"), // echcharikkai
        kw("FLD_WARN", "en", "flood"), kw("FLD_WARN", "en", "warning"), kw("FLD_WARN", "en", "low-lying"),
        kw("FLD_WARN", "hi", "बाढ़"),            // baadh, written as U+0922 U+093C (decomposed)
        kw("FLD_WARN", "hi", "चेतावनी"),
        kw("RAIN_HVY", "en", "heavy rain"), kw("RAIN_HVY", "en", "rainfall"),
        kw("RAIN_HVY", "ta", "கனமழை"),                               // kanamazhai
    )

    private fun kw(code: String, lang: String, keyword: String) = AlertKeyword(code, lang, keyword)

    private class Sample(val expected: String, val sender: String, val body: String)

    private fun samples(): List<Sample> {
        val text = checkNotNull(javaClass.getResource("/sms_samples.tsv")).readText(Charsets.UTF_8)
        return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
            val (expected, sender, body) = it.split('\t', limit = 3)
            Sample(expected, sender, body)
        }
    }

    @Test fun `sample SMS in English, Hindi and Tamil get the expected verdict`() {
        val all = samples()
        assertTrue("sample file not loaded", all.size >= 10)
        all.forEach { s ->
            val match = OfficialAlertDetector.detect(s.body, s.sender, keywords)
            assertEquals("${s.sender}: ${s.body}", s.expected.takeUnless { it == "NONE" }, match?.code)
        }
    }

    @Test fun `samples cover all three keyword languages`() {
        val bodies = samples().map { it.body }
        assertTrue(bodies.any { b -> b.any { it in 'ऀ'..'ॿ' } })   // Devanagari
        assertTrue(bodies.any { b -> b.any { it in '஀'..'௿' } })   // Tamil
        assertTrue(bodies.any { b -> b.all { it.code < 128 } })              // English
    }

    @Test fun `two hits are enough from any sender, one hit needs a government sender`() {
        assertEquals(2, OfficialAlertDetector.detect("Cyclone warning tonight", "+911111111111", keywords)?.hits)
        assertNull(OfficialAlertDetector.detect("Cyclone expected", "+911111111111", keywords))
        assertNull(OfficialAlertDetector.detect("Cyclone expected", null, keywords))
        assertEquals("CYC_WARN", OfficialAlertDetector.detect("Cyclone expected", "XX-NDMAEW", keywords)?.code)
    }

    @Test fun `government sender shapes`() {
        listOf("XX-NDMAEW", "VM-NDMAEW", "AD-TNSDMA", "TN-ALERT", "VM-IMDALT-S", " vm-ndmaew ").forEach {
            assertTrue(it, OfficialAlertDetector.looksLikeGovernmentSender(it))
        }
        listOf("AX-HDFCBK", "+919876543210", "112", "NDMAEW", "", null, "VM-NDMAEWXXXXX").forEach {
            assertFalse(it.toString(), OfficialAlertDetector.looksLikeGovernmentSender(it))
        }
    }

    @Test fun `Latin keywords do not match inside other words but still match word endings`() {
        val rain = listOf(kw("RAIN_HVY", "en", "rain"), kw("RAIN_HVY", "en", "storm"))
        assertNull(OfficialAlertDetector.bestMatch("The train to the station is late", rain))
        assertEquals(1, OfficialAlertDetector.bestMatch("Rainy days ahead", rain)?.hits)     // "rainy" starts with "rain"
        assertEquals(2, OfficialAlertDetector.bestMatch("Rain and Storm!", rain)?.hits)
        assertEquals(1, OfficialAlertDetector.bestMatch("a train, then rain", rain)?.hits)  // second occurrence counts
    }

    @Test fun `each keyword counts once however often it appears or is listed`() {
        val dup = keywords + kw("CYC_WARN", "en", "cyclone")
        assertEquals(1, OfficialAlertDetector.bestMatch("cyclone cyclone cyclone", dup)?.hits)
    }

    @Test fun `the code with more hits wins, then the more specific keywords, then the code name`() {
        assertEquals("FLD_WARN", OfficialAlertDetector.bestMatch("Flood warning for low-lying areas", keywords)?.code)
        val tie = listOf(kw("B_CODE", "en", "storm"), kw("A_CODE", "en", "storm"))
        assertEquals("A_CODE", OfficialAlertDetector.bestMatch("storm", tie)?.code)
        val specific = listOf(kw("SHORT", "en", "sea"), kw("LONG", "en", "rough sea"))
        assertEquals("LONG", OfficialAlertDetector.bestMatch("rough sea today", specific)?.code)
    }

    @Test fun `empty input and empty keyword lists give no match`() {
        assertNull(OfficialAlertDetector.detect("", "XX-NDMAEW", keywords))
        assertNull(OfficialAlertDetector.detect("   ", "XX-NDMAEW", keywords))
        assertNull(OfficialAlertDetector.detect("Cyclone warning", "XX-NDMAEW", emptyList()))
        assertNull(OfficialAlertDetector.bestMatch("Cyclone warning", listOf(kw("CYC_WARN", "en", ""), kw("CYC_WARN", "en", "  "))))
    }

    @Test fun `precomposed and decomposed Hindi letters compare equal`() {
        val precomposed = "बाढ़"        // U+095D
        val decomposed = "बाढ़"    // U+0922 U+093C
        val k = listOf(kw("FLD_WARN", "hi", decomposed))
        assertNotNull(OfficialAlertDetector.bestMatch(precomposed, k))
        assertNotNull(OfficialAlertDetector.bestMatch(decomposed, listOf(kw("FLD_WARN", "hi", precomposed))))
    }
}
