package com.sahay.comms.net

import kotlinx.serialization.Serializable
import java.io.IOException

/** The part of the REST API (docs/CONTRACTS.md §3) that the alert code needs. Every call may throw [IOException]. */
interface CommsApi {
    /** `GET /alerts?regionId=&since=` */
    suspend fun alerts(regionId: String, sinceEpochSec: Long): List<AlertDto>

    /** `GET /alert-templates?lang=` */
    suspend fun alertTemplates(lang: String): List<TemplateDto>

    /** `POST /translate` */
    suspend fun translate(text: String, targetLang: String): TranslateDto
}

/** Non-2xx answer, or a body we cannot read. */
class ApiException(message: String, val httpCode: Int? = null) : IOException(message)

/** Only [wire] is trusted (docs/CONTRACTS.md §3.1); the other JSON fields are ignored. */
@Serializable
data class AlertDto(val id: String? = null, val wire: String? = null)

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
