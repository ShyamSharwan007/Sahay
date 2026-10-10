package com.sahay.comms.sos

import androidx.core.text.BidiFormatter
import com.sahay.core.contracts.LocationFix
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Everything the SOS text needs. Blank optional fields are left out of the message. */
data class SosInput(
    val name: String?,
    val fix: LocationFix?,
    val bloodGroup: String?,
    val allergies: String?,
    val hotel: String?,
    val zone: ZoneId,
)

/** The wording of each sentence, in one language (see [ResourceSosStrings]). */
interface SosStrings {
    val locale: Locale
    fun unknownName(): String
    fun intro(name: String): String
    fun location(coordinates: String, accuracyM: Int, time: String): String
    fun locationWithoutAccuracy(coordinates: String, time: String): String
    fun locationUnavailable(): String
    fun bloodGroup(value: String): String
    fun allergies(value: String): String
    fun staying(value: String): String
}

/**
 * Builds the SOS SMS: "SOS from <name> via Sahay. I need help. Location: <maps link> (±<acc> m, <HH:mm>).
 * Blood group: <bg>. Allergies: <a>. Staying at: <hotel>." Empty fields are omitted.
 *
 * The result always fits in [MAX_PARTS] SMS parts. The intro and the location are mandatory; the other sentences
 * are added in order of importance and shortened (or dropped) when they would not fit. The "±" is outside the
 * GSM-7 alphabet, so in practice the message is UCS-2 and the budget is about 200 characters.
 */
object SosMessageBuilder {
    const val MAX_PARTS = 3
    private const val MAX_NAME_CHARS = 40
    private const val MAX_FIELD_CHARS = 60
    private const val MIN_SHORTENED_CHARS = 6
    private const val ELLIPSIS = "…"
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val MANY_SPACES = Regex(" {2,}")

    fun build(strings: SosStrings, input: SosInput): String {
        // Direction marks keep a right-to-left name (or a Latin one inside Arabic text) from scrambling its neighbours.
        val bidi = BidiFormatter.getInstance(strings.locale)
        val name = clean(input.name, MAX_NAME_CHARS)?.let(bidi::unicodeWrap) ?: strings.unknownName()
        var text = "${strings.intro(name)} ${locationSentence(strings, input)}"

        // Highest priority first. Blood group is short; the hotel is the first thing to lose.
        val optional: List<Pair<String?, (String) -> String>> = listOf(
            input.bloodGroup to strings::bloodGroup,
            input.allergies to strings::allergies,
            input.hotel to strings::staying,
        )
        for ((raw, sentence) in optional) {
            val value = clean(raw, MAX_FIELD_CHARS) ?: continue
            val fitted = fit(text, value) { sentence(bidi.unicodeWrap(it)) } ?: continue
            text = "$text $fitted"
        }
        return text
    }

    /** `Location: <link> (±<acc> m, <HH:mm>).` or the "unavailable" sentence when there is no usable fix. */
    private fun locationSentence(strings: SosStrings, input: SosInput): String {
        val fix = input.fix?.takeIf { it.point.lat in -90.0..90.0 && it.point.lon in -180.0..180.0 }
            ?: return strings.locationUnavailable()
        // Locale.ROOT: a German phone must not write "12,62080".
        val link = "https://maps.google.com/?q=" + String.format(Locale.ROOT, "%.5f,%.5f", fix.point.lat, fix.point.lon)
        val time = TIME_FORMAT.format(Instant.ofEpochMilli(fix.timeMs).atZone(input.zone))
        val accuracy = fix.accuracyM.takeIf { it.isFinite() && it > 0f }?.roundToInt()?.coerceAtLeast(1)
        return if (accuracy != null) strings.location(link, accuracy, time) else strings.locationWithoutAccuracy(link, time)
    }

    /** The sentence for [value] if [text] plus the sentence still fits; else a shortened one; else null. */
    private fun fit(text: String, value: String, sentence: (String) -> String): String? {
        fun fits(candidate: String) = SmsLength.parts("$text $candidate") <= MAX_PARTS
        sentence(value).takeIf(::fits)?.let { return it }
        for (length in value.length - 1 downTo MIN_SHORTENED_CHARS) {
            sentence(shorten(value, length)).takeIf(::fits)?.let { return it }
        }
        return null
    }

    /** Collapses whitespace and control characters, caps the length, and returns null for blank input. */
    private fun clean(raw: String?, maxChars: Int): String? {
        val collapsed = raw?.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }
            ?.joinToString("")?.trim()?.replace(MANY_SPACES, " ")
        if (collapsed.isNullOrEmpty()) return null
        return if (collapsed.length <= maxChars) collapsed else shorten(collapsed, maxChars)
    }

    /** First [length] characters plus an ellipsis, never splitting a surrogate pair. */
    private fun shorten(value: String, length: Int): String {
        var end = length.coerceAtMost(value.length)
        if (end > 0 && end < value.length && Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end).trimEnd() + ELLIPSIS
    }
}
