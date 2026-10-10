package com.sahay.comms.net

import com.sahay.core.contracts.SahayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp client for [CommsApi], [ReportsApi] and [GroupsApi]. Deliberately not exposed to Hilt, so it cannot clash with a client another
 * module provides. Every call is capped at [SahayConfig.NETWORK_TIMEOUT_MS].
 */
@Singleton
class OkHttpCommsApi internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val retryBackoffMs: Long,
) : CommsApi, ReportsApi, GroupsApi {

    @Inject constructor() : this(defaultClient(), SahayConfig.BASE_URL.toHttpUrl(), RETRY_BACKOFF_MS)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override suspend fun alerts(regionId: String, sinceEpochSec: Long, from: LocalDate, to: LocalDate): List<AlertDto> {
        val url = baseUrl.newBuilder()
            .addPathSegment("alerts")
            .addQueryParameter("region", regionId)
            .addQueryParameter("regionId", regionId)    // older servers only know this spelling
            .addQueryParameter("from", from.toString())
            .addQueryParameter("to", to.toString())
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

    /** Not retried: if the answer is lost the report would be stored twice. The repository keeps it queued instead. */
    override suspend fun postReport(request: ReportRequest, idToken: String?): ReportDto {
        val body = json.encodeToString(ReportRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("reports").build())
            .authorized(idToken)
            .post(body)
            .build()
        return decode(ReportDto.serializer(), execute(httpRequest))
    }

    override suspend fun uploadPhoto(reportId: String, jpeg: ByteArray, idToken: String?): ReportDto {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("reports").addPathSegment(reportId).addPathSegment("photo").build())
            .authorized(idToken)
            .post(body)
            .build()
        return decode(ReportDto.serializer(), execute(request))
    }

    override suspend fun reports(regionId: String?, sinceMin: Int, idToken: String?): List<ReportDto> {
        val url = baseUrl.newBuilder().addPathSegment("reports").apply {
            if (regionId != null) addQueryParameter("regionId", regionId)
            addQueryParameter("sinceMin", sinceMin.toString())
        }.build()
        val array = try {
            json.parseToJsonElement(getWithRetry(url, idToken)).jsonArray
        } catch (e: SerializationException) {
            throw ApiException("Unreadable server answer")
        } catch (e: IllegalArgumentException) {
            throw ApiException("Unreadable server answer")
        }
        // One malformed report must not hide all the others.
        return array.mapNotNull { runCatching { json.decodeFromJsonElement(ReportDto.serializer(), it) }.getOrNull() }
    }

    override suspend fun shelterStatuses(regionId: String): List<ShelterStatusDto> {
        val url = baseUrl.newBuilder()
            .addPathSegment("shelters")
            .addPathSegment("status")
            .addQueryParameter("regionId", regionId)
            .build()
        return decode(ListSerializer(ShelterStatusDto.serializer()), getWithRetry(url))
    }

    override suspend fun postPresence(request: PresenceRequest, idToken: String) {
        val body = json.encodeToString(PresenceRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("presence").build())
            .authorized(idToken)
            .post(body)
            .build()
        execute(httpRequest)   // 204, empty body
    }

    override suspend fun groups(lat: Double, lon: Double, radiusM: Int, idToken: String?): GroupsDto {
        val url = baseUrl.newBuilder()
            .addPathSegment("groups")
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("lon", lon.toString())
            .addQueryParameter("radiusM", radiusM.toString())
            .build()
        val root = try {
            json.parseToJsonElement(getWithRetry(url, idToken)).jsonObject
        } catch (e: SerializationException) {
            throw ApiException("Unreadable server answer")
        } catch (e: IllegalArgumentException) {
            throw ApiException("Unreadable server answer")
        }
        // One malformed group must not hide all the others.
        val groups = (root["groups"] as? JsonArray).orEmpty()
            .mapNotNull { runCatching { json.decodeFromJsonElement(GroupDto.serializer(), it) }.getOrNull() }
        val minSize = (root["minSize"] as? JsonPrimitive)?.intOrNull
        return GroupsDto(groups, minSize)
    }

    /** One retry with backoff for network errors and 5xx (docs/CONTRACTS.md §9). 4xx is not retried. */
    private suspend fun getWithRetry(url: HttpUrl, idToken: String? = null): String {
        val request = Request.Builder().url(url).authorized(idToken).get().build()
        return try {
            execute(request)
        } catch (e: IOException) {
            if (e is ApiException && e.httpCode != null && e.httpCode < 500) throw e
            delay(retryBackoffMs)
            execute(request)
        }
    }

    private fun Request.Builder.authorized(idToken: String?): Request.Builder =
        if (idToken.isNullOrBlank()) this else header("Authorization", "Bearer $idToken")

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
