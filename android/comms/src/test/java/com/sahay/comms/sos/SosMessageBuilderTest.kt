package com.sahay.comms.sos

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SosMessageBuilderTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** 2025-10-09 08:53:20 UTC = 14:23 in India. */
    private val fix = LocationFix(GeoPoint(12.6208, 80.1945), accuracyM = 12.4f, timeMs = 1_760_000_000_000L)
    private val link = "https://maps.google.com/?q=12.62080,80.19450"

    private fun input(
        name: String? = "Anna Schmidt",
        fix: LocationFix? = this.fix,
        blood: String? = "O+",
        allergies: String? = "penicillin",
        hotel: String? = "Hotel Sea View",
    ) = SosInput(name, fix, blood, allergies, hotel, ZoneId.of("Asia/Kolkata"))

    private fun build(language: String?, input: SosInput) =
        SosMessageBuilder.build(ResourceSosStrings(context, language), input)

    private fun parts(text: String) = SmsLength.parts(text)

    // ------------------------------------------------------------------ wording

    @Test fun `full English message follows the agreed format`() {
        assertEquals(
            "SOS from Anna Schmidt via Sahay. I need help. Location: $link (±12 m, 14:23). " +
                "Blood group: O+. Allergies: penicillin. Staying at: Hotel Sea View.",
            build("en", input()),
        )
    }

    @Test fun `German and French use their own wording`() {
        val de = build("de", input())
        assertTrue(de, de.startsWith("SOS von Anna Schmidt über Sahay. Ich brauche Hilfe. Standort: $link (±12 m, 14:23)."))
        assertTrue(de, de.contains("Blutgruppe: O+.") && de.contains("Allergien: penicillin.") && de.contains("Unterkunft: Hotel Sea View."))

        val fr = build("fr", input())
        assertTrue(fr, fr.startsWith("SOS de Anna Schmidt via Sahay. J'ai besoin d'aide. Position : $link"))
        assertTrue(fr, fr.contains("Groupe sanguin : O+.") && fr.contains("Hébergement : Hotel Sea View."))
    }

    @Test fun `Arabic uses Arabic wording and keeps the link and digits readable`() {
        val ar = build("ar", input(name = "محمد علي"))
        assertTrue(ar, ar.startsWith("نداء استغاثة من محمد علي عبر Sahay."))
        assertTrue(ar, ar.contains(link) && ar.contains("14:23"))
        // Latin values inside Arabic text are wrapped in direction marks, so only check the label and the value.
        assertTrue(ar, ar.contains("فصيلة الدم: ") && ar.contains("O+"))
        assertTrue(ar, SmsLength.parts(ar) <= SosMessageBuilder.MAX_PARTS)
    }

    @Test fun `languages without a translation fall back to English`() {
        for (language in listOf("ja", "ko", "zh", "es", "ru", "xx", null)) {
            assertTrue(language.toString(), build(language, input()).startsWith("SOS from Anna Schmidt via Sahay."))
        }
    }

    @Test fun `numbers stay in ASCII with a dot, whatever the phone language`() {
        val text = build("de", input(fix = LocationFix(GeoPoint(-12.5, 80.25), 7.6f, 1_760_000_000_000L)))
        assertTrue(text, text.contains("https://maps.google.com/?q=-12.50000,80.25000"))
        assertTrue(text, text.contains("±8 m"))
    }

    // ------------------------------------------------------------------ missing data

    @Test fun `empty and blank fields are left out`() {
        val text = build("en", input(blood = null, allergies = "  \n ", hotel = ""))
        assertEquals("SOS from Anna Schmidt via Sahay. I need help. Location: $link (±12 m, 14:23).", text)
    }

    @Test fun `no location says so instead of inventing one`() {
        val text = build("en", input(fix = null))
        assertTrue(text, text.startsWith("SOS from Anna Schmidt via Sahay. I need help. Location unavailable. Blood group: O+."))
        assertFalse(text, text.contains("maps.google.com"))
    }

    @Test fun `an impossible coordinate counts as no location`() {
        val text = build("en", input(fix = LocationFix(GeoPoint(Double.NaN, 80.0), 5f, 0)))
        assertTrue(text, text.contains("Location unavailable."))
    }

    @Test fun `unknown accuracy is omitted`() {
        val text = build("en", input(fix = fix.copy(accuracyM = 0f)))
        assertTrue(text, text.contains("Location: $link (14:23)."))
    }

    @Test fun `no name falls back to a neutral word`() {
        assertTrue(build("en", input(name = null)).startsWith("SOS from a tourist via Sahay."))
        assertTrue(build("en", input(name = "   ")).startsWith("SOS from a tourist via Sahay."))
    }

    @Test fun `line breaks and tabs in profile fields become single spaces`() {
        val text = build("en", input(allergies = "peanuts,\n\tshellfish", hotel = "Hotel\r\nSea   View"))
        assertTrue(text, text.contains("Allergies: peanuts, shellfish."))
        assertTrue(text, text.contains("Staying at: Hotel Sea View."))
    }

    // ------------------------------------------------------------------ size limit

    @Test fun `a normal message fits in three parts`() {
        for (language in listOf("en", "de", "fr", "ar")) {
            val text = build(language, input())
            assertTrue("$language: ${parts(text)} parts", parts(text) <= SosMessageBuilder.MAX_PARTS)
        }
    }

    @Test fun `very long fields are shortened to stay within three parts`() {
        val long = "x".repeat(300)
        for (language in listOf("en", "de", "fr", "ar")) {
            val text = build(language, input(name = long, allergies = long, hotel = long))
            assertTrue("$language: ${parts(text)} parts", parts(text) <= SosMessageBuilder.MAX_PARTS)
            assertTrue(text, text.contains(link))
        }
    }

    @Test fun `the hotel is dropped first, then allergies are shortened, blood group stays`() {
        val text = build("en", input(name = "N".repeat(40), allergies = "a".repeat(55), hotel = "h".repeat(60)))
        assertTrue(text, text.contains("Blood group: O+."))
        assertTrue(text, text.contains("Allergies: " + "a".repeat(10)))
        assertFalse(text, text.contains("a".repeat(55)))
        assertFalse(text, text.contains("Staying at"))
        assertTrue(parts(text) <= SosMessageBuilder.MAX_PARTS)
    }

    @Test fun `long names are cut with an ellipsis, never in the middle of an emoji`() {
        val text = build("en", input(name = "a" + "😀".repeat(60)))       // the cut at 40 units would fall inside an emoji
        assertTrue(text, text.contains("…"))
        text.forEachIndexed { i, c ->
            if (c.isHighSurrogate()) assertTrue("lone high surrogate at $i", text.getOrNull(i + 1)?.isLowSurrogate() == true)
            if (c.isLowSurrogate()) assertTrue("lone low surrogate at $i", text.getOrNull(i - 1)?.isHighSurrogate() == true)
        }
        assertTrue(parts(text) <= SosMessageBuilder.MAX_PARTS)
    }

    // ------------------------------------------------------------------ right-to-left names

    @Test fun `an Arabic name is kept whole inside an English message`() {
        val name = "محمد عبد الله"
        val text = build("en", input(name = name))
        assertTrue(text, text.contains(name))
        assertTrue(text, text.contains("I need help. Location: $link (±12 m, 14:23)."))
        assertTrue(parts(text) <= SosMessageBuilder.MAX_PARTS)
    }

    @Test fun `RTL allergies and hotel are isolated from the surrounding English`() {
        val text = build("en", input(allergies = "البنسلين", hotel = "فندق الشاطئ"))
        assertTrue(text, text.contains("البنسلين") && text.contains("فندق الشاطئ"))
        // A direction mark follows each RTL value so the full stop and the next sentence do not jump around.
        assertTrue(text, text.contains("‏") || text.contains("‫") || text.contains("‎"))
    }

    @Test fun `a long RTL name is shortened and the message still fits`() {
        val name = "عبد".repeat(80)
        for (language in listOf("en", "ar")) {
            val text = build(language, input(name = name))
            assertTrue("$language: ${parts(text)} parts", parts(text) <= SosMessageBuilder.MAX_PARTS)
            assertTrue(text, text.contains("…") && text.contains(link))
        }
    }
}
