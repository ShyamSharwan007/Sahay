package com.sahay.app.data

import com.sahay.core.contracts.EmergencyContact
import com.sahay.core.contracts.UserProfile
import kotlinx.serialization.Serializable

/**
 * On-disk shape of [UserProfile]. Kept separate from the contract class so the stored JSON can evolve
 * (every field has a default, unknown keys are ignored) without touching the frozen contract.
 */
@Serializable
data class ProfileDto(
    val uid: String,
    val isGuest: Boolean = false,
    val displayName: String = "",
    val email: String? = null,
    val photoUrl: String? = null,
    val language: String = "en",
    val nationality: String? = null,
    val phone: String? = null,
    val bloodGroup: String? = null,
    val allergies: String? = null,
    val medications: String? = null,
    val conditions: String? = null,
    val hotelName: String? = null,
    val hotelAddress: String? = null,
    val contacts: List<ContactDto> = emptyList(),
    val smsAlertsOptIn: Boolean = false,
    val groupFinderOptIn: Boolean = false,
    val onboardingComplete: Boolean = false,
)

@Serializable
data class ContactDto(val name: String, val phone: String, val relation: String)

fun UserProfile.toDto() = ProfileDto(
    uid = uid,
    isGuest = isGuest,
    displayName = displayName,
    email = email,
    photoUrl = photoUrl,
    language = language,
    nationality = nationality,
    phone = phone,
    bloodGroup = bloodGroup,
    allergies = allergies,
    medications = medications,
    conditions = conditions,
    hotelName = hotelName,
    hotelAddress = hotelAddress,
    contacts = contacts.map { ContactDto(it.name, it.phone, it.relation) },
    smsAlertsOptIn = smsAlertsOptIn,
    groupFinderOptIn = groupFinderOptIn,
    onboardingComplete = onboardingComplete,
)

fun ProfileDto.toProfile() = UserProfile(
    uid = uid,
    isGuest = isGuest,
    displayName = displayName,
    email = email,
    photoUrl = photoUrl,
    language = language,
    nationality = nationality,
    phone = phone,
    bloodGroup = bloodGroup,
    allergies = allergies,
    medications = medications,
    conditions = conditions,
    hotelName = hotelName,
    hotelAddress = hotelAddress,
    contacts = contacts.map { EmergencyContact(it.name, it.phone, it.relation) },
    smsAlertsOptIn = smsAlertsOptIn,
    groupFinderOptIn = groupFinderOptIn,
    onboardingComplete = onboardingComplete,
)
