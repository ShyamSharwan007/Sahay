package com.sahay.app.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahay.app.auth.AuthRepository
import com.sahay.app.onboarding.AppLocaleController
import com.sahay.core.contracts.EmergencyContact
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.UserProfile
import com.sahay.designsystem.phone.PhoneFieldValue
import com.sahay.designsystem.phone.PhoneNumbers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class WizardStep { ESSENTIALS, MEDICAL, CONTACTS, STAY, PRIVACY, PERMISSIONS }

const val MAX_CONTACTS = 3

/** [phone] is "+<dial code><digits>"; [phoneRegion] is set only when the user (or a pasted number) chose the country. */
data class ContactDraft(
    val name: String = "",
    val phone: String = "",
    val relation: Relation? = null,
    val phoneRegion: String? = null,
) {
    val isValid get() = name.isNotBlank() && PhoneNumbers.isValid(phone) && relation != null
}

data class WizardState(
    val step: WizardStep = WizardStep.ESSENTIALS,
    val name: String = "",
    val nationality: String? = null,
    val phone: String = "",
    val phoneRegion: String? = null,
    /** Countries of the SIM and the device, for the default country code. */
    val deviceSim: String? = null,
    val deviceLocale: String? = null,
    val bloodGroup: String? = null,
    val allergies: String = "",
    val medications: String = "",
    val conditions: String = "",
    val contacts: List<ContactDraft> = listOf(ContactDraft()),
    val hotelName: String = "",
    val hotelAddress: String = "",
    val groupFinderOptIn: Boolean = false,
    /** Set once the user tries to go on with a problem, so fields only turn red after a first attempt. */
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val finished: Boolean = false,
) {
    val stepIndex get() = step.ordinal
    val stepCount get() = WizardStep.entries.size
    val nameError get() = name.isBlank()
    /** Country code order: nationality, SIM, device region, India. */
    val autoPhoneRegion get() = PhoneNumbers.defaultRegion(nationality, deviceSim, deviceLocale)
    val phoneValue get() = phoneFieldValue(phone, phoneRegion, autoPhoneRegion)
    /** The phone number is required and must be valid for its country (E.164). */
    val phoneError get() = phone.isBlank() || !PhoneNumbers.isValid(phone, phoneValue.region)
    /** A contact's number starts in the same country as the user's own. */
    fun contactPhoneValue(contact: ContactDraft) = phoneFieldValue(contact.phone, contact.phoneRegion, phoneValue.region)
    /** False while the current step has something invalid; Next stays disabled. */
    val canProceed get() = when (step) {
        WizardStep.ESSENTIALS -> !nameError && !phoneError
        WizardStep.CONTACTS -> contacts.all { it.isValid }
        else -> true
    }
    val canAddContact get() = contacts.size < MAX_CONTACTS
}

@HiltViewModel
class ProfileWizardViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: ProfileStore,
    private val locales: AppLocaleController,
    savedState: SavedStateHandle = SavedStateHandle(),
    deviceCountry: DeviceCountryProvider = NoDeviceCountry,
) : ViewModel() {

    // First run: prefill the name from the Google account (guests have none).
    // Editing: start from the saved profile, on the step named by the route's "step" argument.
    private val _state = MutableStateFlow(
        (profiles.profile.value?.takeIf { it.onboardingComplete }
            ?.let { it.toWizardState(startStep = WizardStep.entries.getOrElse(savedState.get<Int>("step") ?: 0) { WizardStep.ESSENTIALS }) }
            ?: WizardState(name = auth.currentUser?.displayName.orEmpty()))
            .copy(deviceSim = deviceCountry.simCountry(), deviceLocale = deviceCountry.localeCountry()),
    )
    val state: StateFlow<WizardState> = _state.asStateFlow()

    /** True when changing a finished profile (from Me); false during first-run setup. */
    private val editing = profiles.profile.value?.onboardingComplete == true

    // ---- field editing

    fun setName(value: String) = edit { copy(name = value) }
    fun setNationality(code: String?) = edit { copy(nationality = code) }
    /** Sets the whole number as text (must start with "+"); the country is then read from its dial code. */
    fun setPhone(value: String) = edit { copy(phone = value, phoneRegion = null) }

    fun setPhoneValue(value: PhoneFieldValue) = edit { copy(phone = value.rawPhone(), phoneRegion = value.manualRegion()) }
    fun setBloodGroup(value: String?) = edit { copy(bloodGroup = value) }
    fun setAllergies(value: String) = edit { copy(allergies = value) }
    fun setMedications(value: String) = edit { copy(medications = value) }
    fun setConditions(value: String) = edit { copy(conditions = value) }
    fun setHotelName(value: String) = edit { copy(hotelName = value) }
    fun setHotelAddress(value: String) = edit { copy(hotelAddress = value) }
    fun setGroupFinder(value: Boolean) = edit { copy(groupFinderOptIn = value) }

    fun addAllergy(item: String) = edit { copy(allergies = allergies.withItem(item)) }
    fun addCondition(item: String) = edit { copy(conditions = conditions.withItem(item)) }

    fun updateContact(index: Int, contact: ContactDraft) = edit {
        if (index !in contacts.indices) this
        else copy(contacts = contacts.toMutableList().also { it[index] = contact })
    }

    fun addContact() = edit { if (canAddContact) copy(contacts = contacts + ContactDraft()) else this }

    /** The first contact is required, so it cannot be removed. */
    fun removeContact(index: Int) = edit {
        if (index == 0 || index !in contacts.indices) this
        else copy(contacts = contacts.filterIndexed { i, _ -> i != index })
    }

    // ---- navigation

    /** Validates the current step and moves on; on the last step saves the profile. */
    fun next() {
        val s = _state.value
        if (s.saving) return
        if (!s.canProceed) {
            edit { copy(showErrors = true) }
            return
        }
        if (s.step == WizardStep.PERMISSIONS) save() else goTo(WizardStep.entries[s.stepIndex + 1])
    }

    /** "Skip" on the Medical step: forget anything typed there and move on. */
    fun skipMedical() {
        edit { copy(bloodGroup = null, allergies = "", medications = "", conditions = "") }
        goTo(WizardStep.CONTACTS)
    }

    /** Returns false on the first step, where the caller should leave the wizard. */
    fun back(): Boolean {
        val s = _state.value
        if (s.stepIndex == 0) return false
        if (!s.saving) goTo(WizardStep.entries[s.stepIndex - 1])
        return true
    }

    fun retrySave() = save()

    /**
     * Edit screens: writes only the section being edited into the saved profile, so nothing else is touched.
     * [WizardState.finished] tells the screen to go back.
     */
    fun saveSection() {
        val s = _state.value
        if (s.saving) return
        if (!s.canProceed) {
            edit { copy(showErrors = true) }
            return
        }
        val base = profiles.profile.value
        if (base == null) {
            edit { copy(saveFailed = true) }
            return
        }
        val edited = s.toProfile(base.uid, base.isGuest, base.email, base.photoUrl, base.language)
        val merged = when (s.step) {
            WizardStep.ESSENTIALS -> base.copy(displayName = edited.displayName, nationality = edited.nationality, phone = edited.phone)
            WizardStep.MEDICAL -> base.copy(
                bloodGroup = edited.bloodGroup, allergies = edited.allergies,
                medications = edited.medications, conditions = edited.conditions,
            )
            WizardStep.CONTACTS -> base.copy(contacts = edited.contacts)
            WizardStep.STAY -> base.copy(hotelName = edited.hotelName, hotelAddress = edited.hotelAddress)
            WizardStep.PRIVACY -> base.copy(groupFinderOptIn = edited.groupFinderOptIn)
            WizardStep.PERMISSIONS -> base
        }
        edit { copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                profiles.save(merged)
                edit { copy(saving = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                edit { copy(saving = false, saveFailed = true) }
            }
        }
    }

    /** Leaving the first step. During first-run setup this also drops the half-created session, so Sign in starts clean. */
    fun leave() {
        if (!editing) auth.signOut()
    }

    // ---- internals

    private fun goTo(step: WizardStep) = edit { copy(step = step, showErrors = false, saveFailed = false) }

    private fun save() {
        val user = auth.currentUser
        if (user == null) { // signed out underneath us; the screen offers a retry
            edit { copy(saveFailed = true) }
            return
        }
        val s = _state.value
        edit { copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                profiles.save(s.toProfile(user.uid, user.isAnonymous, user.email, user.photoUrl, locales.currentLanguage()))
                edit { copy(saving = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                edit { copy(saving = false, saveFailed = true) }
            }
        }
    }

    private fun edit(change: WizardState.() -> WizardState) = _state.update { it.change() }
}

/** Appends [item] to a comma-separated list unless it is already there (case-insensitive). */
internal fun String.withItem(item: String): String {
    val items = split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return if (items.any { it.equals(item, ignoreCase = true) }) this else (items + item).joinToString(", ")
}

private fun String.orNullIfBlank(): String? = trim().ifEmpty { null }

/** Inverse of [toProfile]: fills the wizard from a saved profile so it can be edited. */
internal fun UserProfile.toWizardState(startStep: WizardStep = WizardStep.ESSENTIALS) = WizardState(
    step = startStep,
    name = displayName,
    nationality = nationality,
    phone = phone.orEmpty(),
    bloodGroup = bloodGroup,
    allergies = allergies.orEmpty(),
    medications = medications.orEmpty(),
    conditions = conditions.orEmpty(),
    contacts = contacts.map { c -> ContactDraft(c.name, c.phone, Relation.entries.firstOrNull { it.key == c.relation }) }
        .ifEmpty { listOf(ContactDraft()) },
    hotelName = hotelName.orEmpty(),
    hotelAddress = hotelAddress.orEmpty(),
    groupFinderOptIn = groupFinderOptIn,
)

internal fun WizardState.toProfile(
    uid: String,
    isGuest: Boolean,
    email: String?,
    photoUrl: String?,
    language: String,
) = UserProfile(
    uid = uid,
    isGuest = isGuest,
    displayName = name.trim(),
    email = email,
    photoUrl = photoUrl,
    language = language,
    nationality = nationality,
    phone = phone.orNullIfBlank()?.let(PhoneNumbers::toE164),
    bloodGroup = bloodGroup?.takeIf { it != BLOOD_GROUP_UNKNOWN },
    allergies = allergies.orNullIfBlank(),
    medications = medications.orNullIfBlank(),
    conditions = conditions.orNullIfBlank(),
    hotelName = hotelName.orNullIfBlank(),
    hotelAddress = hotelAddress.orNullIfBlank(),
    contacts = contacts.map { EmergencyContact(it.name.trim(), PhoneNumbers.toE164(it.phone), it.relation?.key.orEmpty()) },
    smsAlertsOptIn = false, // always false in this build
    groupFinderOptIn = groupFinderOptIn,
    onboardingComplete = true,
)
