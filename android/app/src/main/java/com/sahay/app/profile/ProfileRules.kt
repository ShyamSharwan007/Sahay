package com.sahay.app.profile

import java.util.Locale

/** E.164: "+", a non-zero first digit, 7 to 15 digits in total. */
private val E164 = Regex("^\\+[1-9]\\d{6,14}$")

/** Removes the spaces, dashes, dots and brackets people type in phone numbers. */
fun normalizePhone(input: String): String = input.filterNot { it.isWhitespace() || it in "-.()" }

fun isValidE164(input: String): Boolean = E164.matches(normalizePhone(input))

/** A country in the nationality picker. [flag] is an emoji built from the ISO alpha-2 [code]. */
data class Country(val code: String, val name: String, val flag: String)

/** Regional-indicator letters make a flag emoji: "DE" becomes U+1F1E9 U+1F1EA. */
fun flagEmoji(isoCode: String): String {
    if (isoCode.length != 2 || !isoCode.all { it in 'A'..'Z' }) return ""
    return isoCode.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

/** All ISO countries, names in [displayLocale], sorted by name. */
fun countryList(displayLocale: Locale): List<Country> =
    Locale.getISOCountries()
        .map { code -> Country(code, Locale("", code).getDisplayCountry(displayLocale), flagEmoji(code)) }
        .filter { it.name.isNotBlank() && it.name != it.code }
        .sortedBy { it.name.lowercase(displayLocale) }

/** Case-insensitive match on the country name or its ISO code. A blank query returns everything. */
fun List<Country>.search(query: String): List<Country> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.name.contains(q, ignoreCase = true) || it.code.equals(q, ignoreCase = true) }
}

val BloodGroups = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")

/** Stored value for "Don't know" while the wizard is running. Saved to the profile as null. */
const val BLOOD_GROUP_UNKNOWN = "UNKNOWN"

/** Relation keys are stored in the profile (and in SOS texts) in English; the UI shows a translated label. */
enum class Relation(val key: String) { PARENT("Parent"), PARTNER("Partner"), SIBLING("Sibling"), FRIEND("Friend"), OTHER("Other") }
