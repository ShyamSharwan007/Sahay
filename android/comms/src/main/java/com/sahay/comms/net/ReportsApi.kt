package com.sahay.comms.net

import kotlinx.serialization.Serializable
import java.io.IOException

/** The hazard-report part of the REST API (docs/CONTRACTS.md §3). Every call may throw [IOException] ([ApiException] for HTTP errors). */
interface ReportsApi {
    /** `POST /reports`. Needs a Firebase ID token. Sent once, never retried here (the caller keeps it queued instead). */
    suspend fun postReport(request: ReportRequest, idToken: String?): ReportDto

    /** `GET /reports?regionId=&sinceMin=`. The token is optional; with it the server marks the caller's own reports. */
    suspend fun reports(regionId: String?, sinceMin: Int, idToken: String?): List<ReportDto>

    /** `POST /reports/{id}/photo` (multipart JPEG, at most 1 MB). Only the report's owner may upload. */
    suspend fun uploadPhoto(reportId: String, jpeg: ByteArray, idToken: String?): ReportDto
}

/** Body of `POST /reports`. `reporterLat/Lon` are 0 when the phone had no fix (the server then scores proximity as 0). */
@Serializable
data class ReportRequest(
    val type: String,
    val lat: Double,
    val lon: Double,
    val reporterLat: Double,
    val reporterLon: Double,
    val note: String?,
    val photoBase64: String?,
    val createdAt: Long,
    val channel: String,
)

/** The server's `Report` (docs/CONTRACTS.md §3.1). */
@Serializable
data class ReportDto(
    val id: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val note: String? = null,
    val photoUrl: String? = null,
    val createdAt: Long,
    val trustScore: Double = 0.0,
    val label: String? = null,
    val mine: Boolean = false,
    val channel: String? = null,
    val reviewStatus: String? = null,
)
