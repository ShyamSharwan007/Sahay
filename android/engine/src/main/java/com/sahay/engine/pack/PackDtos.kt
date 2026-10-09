package com.sahay.engine.pack

import com.sahay.core.contracts.ForecastDay
import com.sahay.core.contracts.Incident
import com.sahay.core.contracts.LocalizedText
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.Precaution
import com.sahay.core.contracts.Region
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeParseException

// Wire shapes of docs/CONTRACTS.md §3 (GET /regions) and §5.1 (manifest). Unknown keys are ignored by the Json config.

@Serializable
internal data class RegionDto(val id: String, val name: String, val bbox: List<Double>) {
    /** Null when the server sent something we cannot use (bad id or bbox). */
    fun toRegionOrNull(): Region? =
        if (PackStorage.isSafeSegment(id) && isValidBbox(bbox)) Region(id, name, bbox) else null
}

@Serializable
internal data class ForecastDayDto(
    val date: String,
    val rainMm: Double = 0.0,
    val windKmh: Double = 0.0,
    val maxTempC: Double = 0.0,
    val riskLevel: String = "LOW",
)

@Serializable
internal data class HistoryDto(val summary: LocalizedText = emptyMap())

@Serializable
internal data class IncidentDto(
    val date: String,
    val type: String = "",
    val title: LocalizedText = emptyMap(),
    val summary: LocalizedText = emptyMap(),
    val sourceUrl: String? = null,
)

@Serializable
internal data class PrecautionDto(
    val id: String,
    val severity: Int = 1,
    val title: LocalizedText = emptyMap(),
    val body: LocalizedText = emptyMap(),
)

@Serializable
internal data class ManifestDto(
    val regionId: String,
    val regionName: String,
    val packVersion: String,
    val bbox: List<Double>,
    val sqliteUrl: String,
    val sqliteBytes: Long,
    val sqliteSha256: String,
    val publicKeyB64: String,
    val forecast: List<ForecastDayDto> = emptyList(),
    val history: HistoryDto? = null,
    val incidents: List<IncidentDto> = emptyList(),
    val precautions: List<PrecautionDto> = emptyList(),
) {
    /** Builds the public [PackInfo]; entries with unparsable dates are skipped instead of failing the pack. */
    fun toPackInfo(pointer: ActivePointer, sqliteSizeBytes: Long): PackInfo = PackInfo(
        regionId = regionId,
        regionName = regionName,
        packVersion = packVersion,
        bbox = bbox,
        tripStart = LocalDate.parse(pointer.tripStart),
        tripEnd = LocalDate.parse(pointer.tripEnd),
        downloadedAtEpochSec = pointer.downloadedAtEpochSec,
        forecast = forecast.mapNotNull { day ->
            parseDate(day.date)?.let { ForecastDay(it, day.rainMm, day.windKmh, day.maxTempC, day.riskLevel) }
        },
        historySummary = history?.summary.orEmpty(),
        incidents = incidents.mapNotNull { item ->
            parseDate(item.date)?.let { Incident(it, item.type, item.title, item.summary, item.sourceUrl) }
        },
        precautions = precautions.map { Precaution(it.id, it.severity, it.title, it.body) },
        publicKeyB64 = publicKeyB64,
        sizeBytes = sqliteSizeBytes,
    )

    /** Rejects manifests we must not trust with file paths or map downloads; returns the reason or null. */
    fun validationError(requestedRegionId: String): String? = when {
        regionId != requestedRegionId -> "manifest is for region '$regionId', not '$requestedRegionId'"
        !PackStorage.isSafeSegment(packVersion) -> "unsafe packVersion '$packVersion'"
        !isValidBbox(bbox) -> "invalid bbox $bbox"
        sqliteUrl.isBlank() -> "missing sqliteUrl"
        sqliteBytes !in 1..MAX_SQLITE_BYTES -> "invalid sqliteBytes $sqliteBytes"
        sqliteSha256.isBlank() -> "missing sqliteSha256"
        else -> null
    }

    private fun parseDate(text: String): LocalDate? =
        try { LocalDate.parse(text) } catch (_: DateTimeParseException) { null }
}

/** Sanity cap on a pack's size (2 GB); real packs are a few MB. Keeps the free-space maths from overflowing. */
private const val MAX_SQLITE_BYTES = 1L shl 31

/** `[minLon, minLat, maxLon, maxLat]` with real coordinates and a non-empty area. */
internal fun isValidBbox(bbox: List<Double>): Boolean =
    bbox.size == 4 &&
        bbox[0] in -180.0..180.0 && bbox[2] in -180.0..180.0 &&
        bbox[1] in -90.0..90.0 && bbox[3] in -90.0..90.0 &&
        bbox[0] < bbox[2] && bbox[1] < bbox[3]
