package com.sahay.app.local

import com.sahay.core.contracts.SahayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TIMEOUT_MS = 10_000
const val MAX_TRANSLATE_CHARS = 500 // backend limit on `text`

/** Outcome of asking the server for a Tamil translation. */
sealed interface TranslationResult {
    data class Success(val tamil: String) : TranslationResult
    /** No internet, timeout, server error or an answer we can't use. */
    data object Unavailable : TranslationResult
}

/**
 * POST {BASE_URL}translate `{"text", "targetLang":"ta"}` -> `{"translated", ...}` (backend `TranslateRequest`).
 * Never throws; every failure maps to [TranslationResult.Unavailable].
 */
suspend fun translateToTamil(text: String): TranslationResult = withContext(Dispatchers.IO) {
    val input = text.trim().take(MAX_TRANSLATE_CHARS)
    if (input.isEmpty()) return@withContext TranslationResult.Unavailable
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(SahayConfig.BASE_URL + "translate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        val body = JSONObject().put("text", input).put("targetLang", SahayConfig.LOCAL_LANGUAGE).toString()
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode !in 200..299) return@withContext TranslationResult.Unavailable
        val reply = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        parseTranslation(reply, input)
    } catch (_: Exception) {
        TranslationResult.Unavailable
    } finally {
        connection?.disconnect()
    }
}

/**
 * Reads `translated` from the reply. The backend's keyword fallback echoes the input back untranslated,
 * so an unchanged answer counts as "no translation".
 */
internal fun parseTranslation(json: String, input: String): TranslationResult {
    val translated = try {
        JSONObject(json).optString("translated").trim()
    } catch (_: Exception) {
        return TranslationResult.Unavailable
    }
    return if (translated.isEmpty() || translated.equals(input, ignoreCase = true)) {
        TranslationResult.Unavailable
    } else {
        TranslationResult.Success(translated)
    }
}
