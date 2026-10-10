package com.sahay.app

import com.sahay.app.profile.BLOOD_GROUP_UNKNOWN
import com.sahay.app.profile.ContactDraft
import com.sahay.app.profile.ProfileWizardViewModel
import com.sahay.app.profile.Relation
import com.sahay.app.profile.WizardStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileWizardViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val validContact = ContactDraft("Mum", "+49 151 1234 5678", Relation.PARENT)

    private fun vm(
        auth: FakeAuth = FakeAuth(currentUser = googleUser),
        store: FakeProfileStore = FakeProfileStore(),
        locales: FakeLocales = FakeLocales("de"),
    ) = ProfileWizardViewModel(auth, store, locales)

    private fun ProfileWizardViewModel.step() = state.value.step

    private fun ProfileWizardViewModel.goToContacts() {
        next() // essentials -> medical
        next() // medical -> contacts
    }

    private fun ProfileWizardViewModel.fillAndReachLastStep() {
        setName("  Anna Schmidt ")
        setNationality("DE")
        setPhone("+49 151 2345 6789")
        next()
        setBloodGroup("O-")
        setAllergies(" Nuts ")
        next()
        updateContact(0, validContact)
        next()
        setHotelName("Sea View")
        setHotelAddress("")
        next()
        setGroupFinder(true)
        next()
        assertEquals(WizardStep.PERMISSIONS, step())
    }

    @Test fun `name is prefilled from Google, empty for guests`() {
        assertEquals("Anna Schmidt", vm().state.value.name)
        assertEquals("", vm(auth = FakeAuth(currentUser = guestUser)).state.value.name)
    }

    @Test fun `essentials needs a name`() {
        val vm = vm(auth = FakeAuth(currentUser = guestUser))
        vm.next()
        assertEquals(WizardStep.ESSENTIALS, vm.step())
        assertTrue(vm.state.value.showErrors)
        vm.setName("Ben")
        vm.next()
        assertEquals(WizardStep.MEDICAL, vm.step())
        assertFalse(vm.state.value.showErrors)
    }

    @Test fun `phone is optional but must be valid when given`() {
        val vm = vm()
        vm.setPhone("0151 123")
        vm.next()
        assertEquals(WizardStep.ESSENTIALS, vm.step())
        vm.setPhone("")
        vm.next()
        assertEquals(WizardStep.MEDICAL, vm.step())
    }

    @Test fun `quick add appends without duplicates`() {
        val vm = vm()
        vm.addAllergy("Penicillin")
        vm.addAllergy("nuts")
        vm.addAllergy("PENICILLIN")
        vm.addCondition("Asthma")
        assertEquals("Penicillin, nuts", vm.state.value.allergies)
        assertEquals("Asthma", vm.state.value.conditions)
    }

    @Test fun `skip on medical clears the fields and moves on`() {
        val vm = vm()
        vm.next()
        vm.setBloodGroup("A+")
        vm.setAllergies("Nuts")
        vm.skipMedical()
        assertEquals(WizardStep.CONTACTS, vm.step())
        assertNull(vm.state.value.bloodGroup)
        assertEquals("", vm.state.value.allergies)
    }

    @Test fun `contacts need at least one complete valid contact`() {
        val vm = vm()
        vm.goToContacts()
        vm.next()
        assertEquals(WizardStep.CONTACTS, vm.step())
        assertTrue(vm.state.value.showErrors)

        vm.updateContact(0, validContact.copy(phone = "12345"))
        vm.next()
        assertEquals(WizardStep.CONTACTS, vm.step())

        vm.updateContact(0, validContact.copy(relation = null))
        vm.next()
        assertEquals(WizardStep.CONTACTS, vm.step())

        vm.updateContact(0, validContact)
        vm.next()
        assertEquals(WizardStep.STAY, vm.step())
    }

    @Test fun `at most three contacts, first one cannot be removed`() {
        val vm = vm()
        repeat(5) { vm.addContact() }
        assertEquals(3, vm.state.value.contacts.size)
        assertFalse(vm.state.value.canAddContact)
        vm.removeContact(0)
        assertEquals(3, vm.state.value.contacts.size)
        vm.removeContact(2)
        assertEquals(2, vm.state.value.contacts.size)
        vm.removeContact(9) // out of range: ignored
        assertEquals(2, vm.state.value.contacts.size)
    }

    @Test fun `every added contact must be valid`() {
        val vm = vm()
        vm.goToContacts()
        vm.updateContact(0, validContact)
        vm.addContact()
        vm.next()
        assertEquals(WizardStep.CONTACTS, vm.step())
    }

    @Test fun `back walks to the previous step and reports the first step`() {
        val vm = vm()
        assertFalse(vm.back())
        vm.next()
        assertTrue(vm.back())
        assertEquals(WizardStep.ESSENTIALS, vm.step())
    }

    @Test fun `answers survive going back and forth`() {
        val vm = vm()
        vm.setHotelName("Sea View")
        vm.next()
        vm.next()
        vm.back()
        vm.back()
        assertEquals("Sea View", vm.state.value.hotelName)
        assertEquals("Anna Schmidt", vm.state.value.name)
    }

    @Test fun `finish saves a complete profile`() {
        val store = FakeProfileStore()
        val vm = vm(store = store)
        vm.fillAndReachLastStep()
        vm.next()

        assertTrue(vm.state.value.finished)
        val saved = store.saved!!
        assertEquals("uid-google", saved.uid)
        assertFalse(saved.isGuest)
        assertEquals("anna@example.com", saved.email)
        assertEquals("Anna Schmidt", saved.displayName)
        assertEquals("de", saved.language)
        assertEquals("DE", saved.nationality)
        assertEquals("+4915123456789", saved.phone)
        assertEquals("O-", saved.bloodGroup)
        assertEquals("Nuts", saved.allergies)
        assertNull(saved.medications)
        assertEquals("Sea View", saved.hotelName)
        assertNull(saved.hotelAddress)
        assertEquals(listOf("Mum"), saved.contacts.map { it.name })
        assertEquals("+4915112345678", saved.contacts.single().phone)
        assertEquals("Parent", saved.contacts.single().relation)
        assertTrue(saved.groupFinderOptIn)
        assertFalse(saved.smsAlertsOptIn)
        assertTrue(saved.onboardingComplete)
    }

    @Test fun `guest profile is marked as guest and unknown blood group is null`() {
        val store = FakeProfileStore()
        val vm = vm(auth = FakeAuth(currentUser = guestUser), store = store)
        vm.setName("Ben")
        vm.next()
        vm.setBloodGroup(BLOOD_GROUP_UNKNOWN)
        vm.next()
        vm.updateContact(0, validContact)
        repeat(4) { vm.next() }
        val saved = store.saved!!
        assertTrue(saved.isGuest)
        assertNull(saved.bloodGroup)
        assertNull(saved.email)
    }

    @Test fun `save failure shows retry and a retry can succeed`() {
        val store = FakeProfileStore(failSave = true)
        val vm = vm(store = store)
        vm.fillAndReachLastStep()
        vm.next()
        assertTrue(vm.state.value.saveFailed)
        assertFalse(vm.state.value.saving)
        assertFalse(vm.state.value.finished)

        store.failSave = false
        vm.retrySave()
        assertTrue(vm.state.value.finished)
        assertFalse(vm.state.value.saveFailed)
    }

    @Test fun `signed out during the wizard fails the save instead of crashing`() {
        val auth = FakeAuth(currentUser = googleUser)
        val store = FakeProfileStore()
        val vm = vm(auth = auth, store = store)
        vm.fillAndReachLastStep()
        auth.currentUser = null
        vm.next()
        assertTrue(vm.state.value.saveFailed)
        assertNull(store.saved)
    }
}
