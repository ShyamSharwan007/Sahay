package com.sahay.comms.net

import com.sahay.core.contracts.SahayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp client for [CommsApi]. Deliberately not exposed to Hilt, so it cannot clash with a client another
 * module provides. Every call is capped at [SahayConfig.NETWORK_TIMEOUT_MS].
 */
@Singleton
class OkHttpCommsApi internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val retryBackoffMs: Long,
) : CommsApi {

    @Inject constructor() : this(defaultClient(), SahayConfig.BASE_URL.toHttpUrl(), RETRY_BACKOFF_MS)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override suspend fun alerts(regionId: String, sinceEpochSec: Long): List<AlertDto> {
        val url = baseUrl.newBuilder()
            .addPathSegment("alerts")
            .addQueryParameter("regionId", regionId)
            .addQueryParameter("since", sinceEpochSec.toString())
            .build()
        return decode(ListSerializer(AlertDto.serializer()), getWithRetry(url))
    }

    override suspend fun alertTemplates(lang: String): List<TemplateDto> {
        val url = baseUrl.newBuilder().addPathSegment("alert-templates").addQueryParameter("lang", lang).build()
        return decode(ListSerializer(TemplateDto.serializer()), getWithRetry(url))
    }

    /** No retry: a person is waiting, and the caller has an offline fallback. */
    override suspend fun translate(text: String, targetLang: String): TranslateDto {
        val body = json.encodeToString(TranslateRequest.serializer(), TranslateRequest(text, targetLang))
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("translate").build())
            .post(body)
            .build()
        return decode(TranslateDto.serializer(), execute(request))
    }

    /** One retry with backoff for network errors and 5xx (docs/CONTRACTS.md §9). 4xx is not retried. */
    private suspend fun getWithRetry(url: HttpUrl): String {
        val request = Request.Builder().url(url).get().build()
        return try {
            execute(request)
        } catch (e: IOException) {
            if (e is ApiException && e.httpCode != null && e.httpCode < 500) throw e
            delay(retryBackoffMs)
            execute(request)
        }
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw ApiException("HTTP ${response.code}", response.code)
            text
        }
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, text: String): T =
        try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw ApiException("Unreadable server answer")
        }

    private companion object {
        const val RETRY_BACKOFF_MS = 500L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(SahayConfig.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SahayConfig.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(SahayConfig.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(SahayConfig.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
