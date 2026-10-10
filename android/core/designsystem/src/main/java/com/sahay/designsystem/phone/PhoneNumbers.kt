package com.sahay.designsystem.phone

import android.content.Context
import io.michaelrocks.libphonenumber.android.NumberParseException
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import java.util.Locale

/** A country in the picker. [region] is the ISO 3166 alpha-2 code. */
data class PhoneCountry(val region: String, val name: String, val dialCode: Int) {
    val flag: String get() = PhoneNumbers.flagEmoji(region)
    val dialText: String get() = "+$dialCode"
}

/** What the field shows: the chosen country, the digits typed after the country code, and whether the user picked the country. */
data class PhoneFieldValue(val region: String, val national: String = "", val manual: Boolean = false)

/** A full international number split into its country and national digits. */
data class DetectedNumber(val region: String, val national: String)

/**
 * Phone-number rules on top of libphonenumber. Call [init] once (the Application does).
 * Until then every function degrades to a simple E.164 shape check, so plain unit tests still work.
 */
object PhoneNumbers {
    /** Shown first in the country picker, in this order. */
    val POPULAR_REGIONS = listOf("IN", "US", "GB", "DE", "FR", "JP", "KR", "AE")
    const val FALLBACK_REGION = "IN"
    const val MAX_NATIONAL_DIGITS = 15

    private val E164_SHAPE = Regex("^\\+[1-9]\\d{6,14}$")

    @Volatile private var util: PhoneNumberUtil? = null

    @Synchronized
    fun init(context: Context) {
        if (util == null) util = PhoneNumberUtil.createInstance(context.applicationContext)
    }

    // ---------------------------------------------------------------- text helpers

    /** Keeps only the digits (also converts Arabic-Indic and other digit scripts to 0-9). */
    fun sanitize(input: String): String = buildString {
        input.forEach { c -> Character.digit(c, 10).takeIf { it >= 0 }?.let { append(('0' + it)) } }
    }

    /** Removes the spaces, dashes, dots and brackets people type, and converts digits to 0-9. */
    fun normalize(input: String): String = buildString {
        input.trim().forEachIndexed { i, c ->
            when {
                c == '+' && i == 0 -> append(c)
                Character.digit(c, 10) >= 0 -> append('0' + Character.digit(c, 10))
            }
        }
    }

    /** True when the text starts like a full international number: "+..." or "00...". */
    fun looksInternational(input: String): Boolean {
        val t = input.trimStart()
        return t.startsWith("+") || t.startsWith("00")
    }

    fun flagEmoji(region: String): String {
        if (region.length != 2 || !region.all { it in 'A'..'Z' }) return ""
        return region.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    // ---------------------------------------------------------------- countries

    fun dialCode(region: String): Int? = util?.getCountryCodeForRegion(region)?.takeIf { it > 0 }

    fun regionName(region: String, locale: Locale): String =
        Locale("", region).getDisplayCountry(locale).ifBlank { region }

    /** All countries with a dial code: the popular ones first (in [POPULAR_REGIONS] order), then the rest by name. */
    fun countries(locale: Locale): List<PhoneCountry> {
        val u = util ?: return emptyList()
        val all = u.supportedRegions.mapNotNull { region ->
            val code = u.getCountryCodeForRegion(region)
            if (code > 0) PhoneCountry(region, regionName(region, locale), code) else null
        }
        val (popular, rest) = all.partition { it.region in POPULAR_REGIONS }
        return popular.sortedBy { POPULAR_REGIONS.indexOf(it.region) } + rest.sortedBy { it.name.lowercase(locale) }
    }

    /** Picks the default country: nationality, then SIM country, then device country, then India. */
    fun defaultRegion(nationality: String?, simCountry: String?, deviceCountry: String?): String =
        listOf(nationality, simCountry, deviceCountry)
            .mapNotNull { it?.trim()?.uppercase(Locale.ROOT)?.takeIf(::isSupportedRegion) }
            .firstOrNull() ?: FALLBACK_REGION

    private fun isSupportedRegion(region: String): Boolean =
        region.length == 2 && (util?.let { region in it.supportedRegions } ?: region.all { it in 'A'..'Z' })

    // ---------------------------------------------------------------- detecting and splitting

    /**
     * Reads a full number such as "+91 96260 61400" or "0049 151 2345 6789" and finds its country.
     * Null when the text isn't (yet) a complete international number.
     */
    fun detect(input: String): DetectedNumber? {
        val u = util ?: return null
        val digits = normalize(input).removePrefix("+").let { if (input.trimStart().startsWith("00")) it.removePrefix("00") else it }
        if (!looksInternational(input) || digits.isEmpty()) return null
        val number = try {
            u.parse("+$digits", null)
        } catch (_: NumberParseException) {
            return null
        }
        val region = u.getRegionCodeForNumber(number)?.takeIf { it != "ZZ" }
            ?: u.getRegionCodeForCountryCode(number.countryCode).takeIf { it != "ZZ" }
            ?: return null
        return DetectedNumber(region, u.getNationalSignificantNumber(number))
    }

    /** The country of a stored number like "+4915112345678"; [preferred] wins when it shares the dial code (US/CA, GB/GG...). */
    fun regionForPhone(phone: String, preferred: String?): String? {
        val u = util ?: return null
        val digits = normalize(phone).removePrefix("+")
        for (length in 1..3) {
            val code = digits.take(length).toIntOrNull() ?: return null
            if (digits.length < length) return null
            val region = u.getRegionCodeForCountryCode(code)
            if (region == "ZZ") continue
            return if (preferred != null && u.getCountryCodeForRegion(preferred) == code) preferred else region
        }
        return null
    }

    /** The digits after the dial code of [region] in a stored number; empty for a blank number. */
    fun nationalPart(phone: String, region: String): String {
        val digits = sanitize(phone)
        val dial = dialCode(region)?.toString() ?: return digits
        return if (digits.startsWith(dial)) digits.removePrefix(dial) else digits
    }

    /** "+<dial code><national digits>", or blank when no digits were typed. This is the text kept while editing. */
    fun toRawPhone(region: String, national: String): String {
        if (national.isEmpty()) return ""
        return "+${dialCode(region) ?: ""}$national"
    }

    // ---------------------------------------------------------------- formatting and validation

    /** Formats digits as the user types, in the style of [region] ("96260 61400"). Falls back to the plain digits. */
    fun formatNational(region: String, digits: String): String {
        val u = util ?: return digits
        if (digits.isEmpty()) return ""
        val formatter = u.getAsYouTypeFormatter(region)
        var formatted = digits
        digits.forEach { formatted = formatter.inputDigit(it) }
        return if (sanitize(formatted) == digits) formatted else digits
    }

    /**
     * True when [phone] (with its "+") is a real number. With [region] the number must also belong to that
     * country's dial code. Without libphonenumber loaded it only checks the E.164 shape.
     */
    fun isValid(phone: String, region: String? = null): Boolean {
        val normalized = normalize(phone)
        if (!normalized.startsWith("+")) return false
        val u = util ?: return E164_SHAPE.matches(normalized)
        return try {
            val number = u.parse(normalized, null)
            u.isValidNumber(number) && (region == null || dialCode(region)?.let { it == number.countryCode } != false)
        } catch (_: NumberParseException) {
            false
        }
    }

    /** The number in E.164 ("+919626061400"), with any trunk "0" removed. Plain cleaned text if it can't be parsed. */
    fun toE164(phone: String): String {
        val normalized = normalize(phone)
        val u = util ?: return normalized
        return try {
            u.format(u.parse(normalized, null), PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (_: NumberParseException) {
            normalized
        }
    }
}
