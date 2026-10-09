package com.sahay.app

import com.sahay.app.auth.SignInProblem
import com.sahay.app.auth.SignInResult
import com.sahay.app.auth.SignInViewModel
import com.sahay.app.data.toDto
import com.sahay.app.data.toProfile
import com.sahay.app.onboarding.LanguageViewModel
import com.sahay.app.onboarding.StartDestination
import com.sahay.app.onboarding.StartViewModel
import com.sahay.app.onboarding.supportedLanguageOrEnglish
import com.sahay.core.contracts.EmergencyContact
import com.sahay.core.contracts.UserProfile
import io.mockk.mockk
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
class OnboardingViewModelsTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun profile(complete: Boolean) = UserProfile(
        uid = "u", isGuest = false, displayName = "A", email = null, photoUrl = null, language = "en", nationality = "DE",
        phone = null, bloodGroup = "O+", allergies = null, medications = null, conditions = null, hotelName = null,
        hotelAddress = null, contacts = listOf(EmergencyContact("Mum", "+4915112345678", "Parent")),
        smsAlertsOptIn = false, groupFinderOptIn = true, onboardingComplete = complete,
    )

    // ---- Start

    @Test fun `start goes to language when no profile`() {
        assertEquals(StartDestination.LANGUAGE, StartViewModel(FakeProfileStore(null)).destination.value)
    }

    @Test fun `start goes to language when profile is incomplete`() {
        assertEquals(StartDestination.LANGUAGE, StartViewModel(FakeProfileStore(profile(false))).destination.value)
    }

    @Test fun `start goes to main when profile is complete`() {
        assertEquals(StartDestination.MAIN, StartViewModel(FakeProfileStore(profile(true))).destination.value)
    }

    // ---- Language

    @Test fun `language preselects the device language`() {
        assertEquals("ja", LanguageViewModel(FakeLocales("ja")).selected.value)
    }

    @Test fun `unsupported language falls back to English`() {
        assertEquals("en", supportedLanguageOrEnglish("pt"))
        assertEquals("en", supportedLanguageOrEnglish(null))
        assertEquals("ar", supportedLanguageOrEnglish("AR"))
    }

    @Test fun `confirm applies the selected language only when asked`() {
        val locales = FakeLocales("en")
        val vm = LanguageViewModel(locales)
        vm.select("de")
        assertNull(locales.applied)
        vm.confirm()
        assertEquals("de", locales.applied)
    }

    // ---- Sign in

    @Test fun `already signed in skips ahead`() {
        assertTrue(SignInViewModel(FakeAuth(currentUser = googleUser)).state.value.signedIn)
    }

    @Test fun `google success signs in`() {
        val vm = SignInViewModel(FakeAuth(googleResult = SignInResult.Success(googleUser)))
        vm.signInWithGoogle(mockk(relaxed = true))
        assertTrue(vm.state.value.signedIn)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `cancelling google shows no error`() {
        val vm = SignInViewModel(FakeAuth(googleResult = SignInResult.Cancelled))
        vm.signInWithGoogle(mockk(relaxed = true))
        assertNull(vm.state.value.problem)
        assertFalse(vm.state.value.signedIn)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `problems map to messages`() {
        val cases = mapOf(
            SignInResult.NoGoogleAccount to SignInProblem.NO_GOOGLE_ACCOUNT,
            SignInResult.NoInternet to SignInProblem.NO_INTERNET,
            SignInResult.Failed to SignInProblem.FAILED,
        )
        cases.forEach { (result, problem) ->
            val vm = SignInViewModel(FakeAuth(googleResult = result))
            vm.signInWithGoogle(mockk(relaxed = true))
            assertEquals(problem, vm.state.value.problem)
            assertFalse(vm.state.value.signedIn)
        }
    }

    @Test fun `guest offline shows the internet message, then clears it`() {
        val vm = SignInViewModel(FakeAuth(guestResult = SignInResult.NoInternet))
        vm.continueAsGuest()
        assertEquals(SignInProblem.NO_INTERNET, vm.state.value.problem)
        vm.dismissProblem()
        assertNull(vm.state.value.problem)
    }

    @Test fun `guest success signs in`() {
        val vm = SignInViewModel(FakeAuth())
        vm.continueAsGuest()
        assertTrue(vm.state.value.signedIn)
    }

    // ---- Stored profile shape

    @Test fun `profile survives the stored json shape`() {
        val original = profile(true)
        assertEquals(original, original.toDto().toProfile())
    }
}
