package com.sahay.app

import com.sahay.app.profile.countryList
import com.sahay.app.profile.flagEmoji
import com.sahay.app.profile.isValidE164
import com.sahay.app.profile.normalizePhone
import com.sahay.app.profile.search
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ProfileRulesTest {

    @Test fun `valid E164 numbers`() {
        listOf("+4915123456789", "+49 151 2345 6789", "+91 (44) 2345-6789", "+14155552671").forEach { assertTrue(it, isValidE164(it)) }
    }

    @Test fun `invalid E164 numbers`() {
        listOf("", "04915123456789", "+0123456789", "+12345", "+1234567890123456", "+49abc1234567", "49151234567").forEach {
            assertFalse(it, isValidE164(it))
        }
    }

    @Test fun `normalize strips separators`() {
        assertEquals("+4915123456789", normalizePhone(" +49 (151) 234-567.89 "))
    }

    @Test fun `flag emoji comes from the ISO code`() {
        assertEquals("🇩🇪", flagEmoji("DE"))
        assertEquals("", flagEmoji("de"))
        assertEquals("", flagEmoji("DEU"))
    }

    @Test fun `country search by name or code, ignoring case`() {
        val countries = countryList(Locale.ENGLISH)
        assertTrue(countries.size > 200)
        assertEquals("Germany", countries.search("germ").single().name)
        assertTrue(countries.search("de").any { it.code == "DE" })
        assertTrue(countries.search("zzzz").isEmpty())
        assertEquals(countries.size, countries.search("  ").size)
    }
}
