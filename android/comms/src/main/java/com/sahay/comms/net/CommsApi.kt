package com.sahay.comms.net

import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.LocalDate

/** The part of the REST API (docs/CONTRACTS.md §3) that the alert code needs. Every call may throw [IOException]. */
interface CommsApi {
    /** `GET /alerts?region=&from=&to=&since=`: alerts for [regionId] that overlap the dates [from]..[to] (inclusive). */
    suspend fun alerts(regionId: String, sinceEpochSec: Long, from: LocalDate, to: LocalDate): List<AlertDto>

    /** `GET /alert-templates?lang=` */
    suspend fun alertTemplates(lang: String): List<TemplateDto>

    /** `POST /translate` */
    suspend fun translate(text: String, targetLang: String): TranslateDto

    /** `GET /shelters/status?regionId=` */
    suspend fun shelterStatuses(regionId: String): List<ShelterStatusDto>
}

/** Non-2xx answer, or a body we cannot read. */
class ApiException(message: String, val httpCode: Int? = null) : IOException(message)

/**
 * Only [wire] is trusted for the alert's content (docs/CONTRACTS.md §3.1). [regionId] and [expiresAt] (epoch seconds)
 * are not signed, so they are used only to decide whether to show the alert, never what it says.
 */
@Serializable
data class AlertDto(
    val id: String? = null,
    val wire: String? = null,
    val regionId: String? = null,
    val expiresAt: Long? = null,
)

/** Only [wire] is trusted, like [AlertDto]. */
@Serializable
data class ShelterStatusDto(val shelterId: String? = null, val wire: String? = null)

@Serializable
data class TemplateDto(val code: String, val severity: Int = 1, val title: String = "", val body: String = "")

@Serializable
data class TranslateDto(
    val detectedLang: String? = null,
    val simplifiedEn: String? = null,
    val translated: String? = null,
    val matchedTemplateCode: String? = null,
)

@Serializable
internal data class TranslateRequest(val text: String, val targetLang: String)
