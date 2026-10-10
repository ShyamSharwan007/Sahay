package com.sahay.app

import com.sahay.app.profile.ContactDraft
import com.sahay.app.profile.ProfileWizardViewModel
import com.sahay.app.profile.phoneFieldValue
import com.sahay.app.profile.rawPhone
import com.sahay.designsystem.components.matching
import com.sahay.designsystem.phone.PhoneFieldValue
import com.sahay.designsystem.phone.PhoneNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/** Runs under Robolectric because libphonenumber reads its country data from Android assets. */
@RunWith(RobolectricTestRunner::class)
class PhoneNumbersTest {

    @Before fun setUp() = PhoneNumbers.init(RuntimeEnvironment.getApplication())

    // ---- default country: nationality, SIM, device, India

    @Test fun `default country prefers nationality, then SIM, then device, then India`() {
        assertEquals("DE", PhoneNumbers.defaultRegion("DE", "FR", "US"))
        assertEquals("FR", PhoneNumbers.defaultRegion(null, "FR", "US"))
        assertEquals("FR", PhoneNumbers.defaultRegion("", "fr", "US"))
        assertEquals("US", PhoneNumbers.defaultRegion(null, null, "US"))
        assertEquals("IN", PhoneNumbers.defaultRegion(null, "", null))
    }

    @Test fun `default country skips codes that are not countries`() {
        assertEquals("FR", PhoneNumbers.defaultRegion("XX", "FR", null))
        assertEquals("IN", PhoneNumbers.defaultRegion("ZZ", "123", "A"))
    }

    // ---- detecting a full number

    @Test fun `plus number is split into country and digits`() {
        val found = PhoneNumbers.detect("+91 96260 61400")
        assertEquals("IN", found?.region)
        assertEquals("9626061400", found?.national)
    }

    @Test fun `double zero prefix works like plus`() {
        val found = PhoneNumbers.detect("0049 151 12345678")
        assertEquals("DE", found?.region)
        assertEquals("15112345678", found?.national)
    }

    @Test fun `separators and brackets are ignored when detecting`() {
        assertEquals("US", PhoneNumbers.detect("+1 (650) 253-0000")?.region)
    }

    @Test fun `plain national digits and unfinished prefixes are not detected`() {
        assertNull(PhoneNumbers.detect("9626061400"))
        assertNull(PhoneNumbers.detect("+"))
        assertNull(PhoneNumbers.detect("+91"))
        assertNull(PhoneNumbers.detect("00"))
    }

    @Test fun `looks international only for plus and double zero`() {
        assertTrue(PhoneNumbers.looksInternational("+49"))
        assertTrue(PhoneNumbers.looksInternational(" 0049"))
        assertFalse(PhoneNumbers.looksInternational("0151"))
        assertFalse(PhoneNumbers.looksInternational("9626"))
    }

    // ---- cleaning, formatting, validating

    @Test fun `sanitize keeps digits only`() {
        assertEquals("9626061400", PhoneNumbers.sanitize(" (96260)-614.00 "))
        assertEquals("123", PhoneNumbers.sanitize("١٢٣")) // Arabic-Indic digits
    }

    @Test fun `national digits are formatted as typed and map back to plain digits`() {
        val formatted = PhoneNumbers.formatNational("IN", "9626061400")
        assertEquals("9626061400", PhoneNumbers.sanitize(formatted))
        assertTrue(formatted.contains(" "))
        assertEquals("", PhoneNumbers.formatNational("IN", ""))
    }

    @Test fun `validation uses real number rules`() {
        assertTrue(PhoneNumbers.isValid("+919626061400"))
        assertTrue(PhoneNumbers.isValid("+91 96260 61400", "IN"))
        assertFalse(PhoneNumbers.isValid("+91123"))
        assertFalse(PhoneNumbers.isValid("9626061400")) // no +
        assertFalse(PhoneNumbers.isValid("+919626061400", "DE")) // other country's dial code
    }

    @Test fun `E164 output drops separators and the trunk zero`() {
        assertEquals("+919626061400", PhoneNumbers.toE164("+91 96260 61400"))
        assertEquals("+4915112345678", PhoneNumbers.toE164("+49 0151 12345678"))
    }

    // ---- region <-> stored number

    @Test fun `region comes from the dial code, preferring the default when it shares the code`() {
        assertEquals("DE", PhoneNumbers.regionForPhone("+4915112345678", "IN"))
        assertEquals("CA", PhoneNumbers.regionForPhone("+16045551234", "CA"))
        assertEquals("US", PhoneNumbers.regionForPhone("+16045551234", "IN"))
        assertEquals("IN", PhoneNumbers.regionForPhone("+91", "DE"))
    }

    @Test fun `stored number splits into the field value and back`() {
        val value = phoneFieldValue("+919626061400", manualRegion = null, autoRegion = "DE")
        assertEquals(PhoneFieldValue("IN", "9626061400", manual = false), value)
        assertEquals("+919626061400", value.rawPhone())
    }

    @Test fun `blank number follows the automatic country, a chosen country stays`() {
        assertEquals("DE", phoneFieldValue("", null, "DE").region)
        val chosen = phoneFieldValue("", "JP", "DE")
        assertEquals("JP", chosen.region)
        assertTrue(chosen.manual)
    }

    @Test fun `typed trunk zero is kept while editing`() {
        val value = PhoneFieldValue("DE", "015112345678")
        assertEquals("+49015112345678", value.rawPhone())
        assertEquals("015112345678", phoneFieldValue(value.rawPhone(), null, "DE").national)
    }

    // ---- wizard state

    private fun wizard() = ProfileWizardViewModel(FakeAuth(currentUser = googleUser), FakeProfileStore(), FakeLocales("de"))

    @Test fun `typing digits in the phone field stores the plain number`() {
        val vm = wizard()
        vm.setPhoneValue(PhoneFieldValue("IN", "9626061400"))
        assertEquals("+919626061400", vm.state.value.phone)
        assertNull(vm.state.value.phoneRegion)
    }

    @Test fun `contact country starts as the own phone country but can differ`() {
        val vm = wizard()
        vm.setNationality("DE")
        assertEquals("DE", vm.state.value.contactPhoneValue(ContactDraft()).region)
        vm.setPhoneValue(PhoneFieldValue("JP", "9012345678", manual = true))
        assertEquals("JP", vm.state.value.contactPhoneValue(ContactDraft()).region)
        assertEquals("DE", vm.state.value.contactPhoneValue(ContactDraft(phone = "+4915112345678")).region)
    }

    // ---- country list

    @Test fun `popular countries come first in the given order`() {
        val countries = PhoneNumbers.countries(Locale.ENGLISH)
        assertEquals(PhoneNumbers.POPULAR_REGIONS, countries.take(PhoneNumbers.POPULAR_REGIONS.size).map { it.region })
        assertEquals(91, countries.first().dialCode)
        assertTrue(countries.size > 150)
    }

    @Test fun `search matches name, ISO code and dial code`() {
        val countries = PhoneNumbers.countries(Locale.ENGLISH)
        assertNotNull(countries.matching("germ").firstOrNull { it.region == "DE" })
        assertNotNull(countries.matching("de").firstOrNull { it.region == "DE" })
        assertNotNull(countries.matching("+91").firstOrNull { it.region == "IN" })
        assertNotNull(countries.matching("49").firstOrNull { it.region == "DE" })
        assertTrue(countries.matching("zzzzzz").isEmpty())
        assertEquals(countries.size, countries.matching("  ").size)
    }
}
