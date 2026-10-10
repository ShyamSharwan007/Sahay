package com.sahay.comms.alerts

import com.sahay.core.contracts.AlertKeyword
import java.text.Normalizer
import java.util.Locale

/** The alert template that best explains a text, and how many of its keywords were found. */
data class OfficialMatch(val code: String, val hits: Int)

/**
 * Decides whether a plain-text SMS (or pasted text) is an official hazard alert, by counting keyword hits per
 * template code. Keywords come from the trip pack (`alert_keyword`, languages en / hi / ta).
 */
object OfficialAlertDetector {

    /** Hits needed when we know nothing about the sender. */
    const val MIN_HITS = 2

    // Indian SMS sender ids look like "XX-NDMAEW": 2-letter operator/circle prefix, a name, optional "-S" suffix.
    private val SENDER_SHAPE = Regex("^[A-Z]{2}-([A-Z0-9]{3,8})(-[A-Z])?$")
    private val AUTHORITY_TOKENS = listOf("NDMA", "SDMA", "DMA", "IMD", "ALERT", "ALRT", "GOVT", "GOI", "DISASTER", "EMERG", "CYCLONE", "112")

    /**
     * Words match anywhere in the text, but a Latin keyword must not start in the middle of another word
     * ("rain" is not found in "train"). Text and keywords are normalised (NFC, lower case) so that Hindi and
     * Tamil letters written with combining marks compare equal.
     *
     * @return the best code with at least [MIN_HITS] hits, or with at least 1 hit when [sender] looks like a
     * government sender; null if neither holds.
     */
    fun detect(body: String, sender: String?, keywords: List<AlertKeyword>): OfficialMatch? {
        val needed = if (looksLikeGovernmentSender(sender)) 1 else MIN_HITS
        return bestMatch(body, keywords)?.takeIf { it.hits >= needed }
    }

    /** Best code with at least one hit, used for text the user pasted on purpose. */
    fun bestMatch(text: String, keywords: List<AlertKeyword>): OfficialMatch? {
        val haystack = normalize(text)
        if (haystack.isBlank()) return null
        val found = keywords.asSequence()
            .map { it.code to normalize(it.keyword) }
            .filter { (_, keyword) -> keyword.isNotEmpty() }
            .distinct()
            .filter { (_, keyword) -> containsKeyword(haystack, keyword) }
            .groupBy({ it.first }, { it.second })
        // More hits first; then longer (more specific) keywords; then code name, so the result is stable.
        return found.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<String>>> { it.value.size }
                    .thenByDescending { entry -> entry.value.sumOf { it.length } }
                    .thenBy { it.key },
            )
            .firstOrNull()
            ?.let { OfficialMatch(it.key, it.value.size) }
    }

    fun looksLikeGovernmentSender(sender: String?): Boolean {
        val match = SENDER_SHAPE.matchEntire(sender?.trim()?.uppercase(Locale.ROOT).orEmpty()) ?: return false
        val name = match.groupValues[1]
        return AUTHORITY_TOKENS.any { name.contains(it) }
    }

    private fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT).trim()

    private fun containsKeyword(haystack: String, keyword: String): Boolean {
        val needsWordStart = keyword.first().let { it in 'a'..'z' || it in '0'..'9' }
        var from = 0
        while (true) {
            val at = haystack.indexOf(keyword, from)
            if (at < 0) return false
            if (!needsWordStart || at == 0 || !haystack[at - 1].isLetterOrDigit()) return true
            from = at + 1
        }
    }
}
