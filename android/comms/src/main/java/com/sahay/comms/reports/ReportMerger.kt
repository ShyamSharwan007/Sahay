package com.sahay.comms.reports

import com.sahay.comms.net.ReportDto
import com.sahay.core.contracts.Channel
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.HazardType
import com.sahay.core.contracts.SahayAlert
import com.sahay.core.contracts.TrustLabel

/** Combines the reports from the server with the ones made on this phone into the one list the app shows. */
internal object ReportMerger {
    /** Reports older than this are not shown (the server query uses the same window). */
    const val WINDOW_MIN = 180
    private const val WINDOW_SEC = WINDOW_MIN * 60L

    /**
     * - A report of mine that the server already lists is shown once, with the server's trust, under my local id
     *   (so its id does not change when it moves from "saved" to "sent").
     * - Reports still on the phone (PENDING, FAILED, or SENT but missing from the server list) get the server's
     *   trust if it told us, otherwise a provisional one from [ProvisionalTrust].
     * - Trust is recomputed on every call, so it fades as the report ages.
     */
    fun merge(local: List<ReportEntity>, server: List<HazardReport>, alerts: List<SahayAlert>, nowSec: Long): List<HazardReport> {
        val localByServerId = local.mapNotNull { e -> e.serverId?.let { it to e } }.toMap()
        val serverIds = server.mapTo(HashSet()) { it.id }
        val others = server.filter { !it.mine && it.id !in localByServerId }

        val fromServer = server.map { report ->
            localByServerId[report.id]?.let {
                // The server's review state is the fresher one; the photo file only exists on this phone.
                report.copy(
                    id = it.localId, mine = true, pendingSync = false,
                    photoPath = it.photoPath, reviewStatus = report.reviewStatus ?: localReviewStatus(it),
                )
            } ?: report
        }
        val fromPhone = local
            .filter { it.serverId == null || it.serverId !in serverIds }
            .map { toHazardReport(it, others, alerts, nowSec) }

        val oldest = nowSec - WINDOW_SEC
        return (fromServer + fromPhone)
            .filter { it.createdAtEpochSec >= oldest }
            .sortedByDescending { it.createdAtEpochSec }
    }

    /** A report made on this phone, as the app shows it. */
    fun toHazardReport(entity: ReportEntity, others: List<HazardReport>, alerts: List<SahayAlert>, nowSec: Long): HazardReport {
        val type = HazardType.fromCode(entity.type) ?: HazardType.OTHER
        val point = GeoPoint(entity.lat, entity.lon)
        val status = ReportStatus.entries.firstOrNull { it.name == entity.status } ?: ReportStatus.PENDING
        val trust = entity.serverTrust ?: ProvisionalTrust.score(
            TrustInput(type, point, reporterPoint(entity), entity.createdAtEpochSec), others, alerts, nowSec,
        )
        return HazardReport(
            id = entity.localId,
            type = type,
            point = point,
            note = entity.note,
            photoUrl = entity.serverPhotoUrl,
            createdAtEpochSec = entity.createdAtEpochSec,
            trustScore = trust,
            label = ProvisionalTrust.label(trust),
            mine = true,
            channel = Channel.entries.firstOrNull { it.name == entity.channel } ?: Channel.INTERNET,
            pendingSync = status == ReportStatus.PENDING,
            photoPath = entity.photoPath,
            reviewStatus = localReviewStatus(entity),
        )
    }

    /** A queued photo counts as "pending" until the server says otherwise. */
    private fun localReviewStatus(entity: ReportEntity): String? =
        entity.reviewStatus ?: if (entity.photoPath != null) "pending" else null

    /** A report from `GET /reports`, or null if its type is unknown to this app version. */
    fun fromDto(dto: ReportDto): HazardReport? {
        val type = HazardType.fromCode(dto.type) ?: return null
        val trust = dto.trustScore.coerceIn(0.0, 1.0)
        return HazardReport(
            id = dto.id,
            type = type,
            point = GeoPoint(dto.lat, dto.lon),
            note = dto.note,
            photoUrl = dto.photoUrl,
            createdAtEpochSec = dto.createdAt,
            trustScore = trust,
            label = TrustLabel.entries.firstOrNull { it.name == dto.label } ?: ProvisionalTrust.label(trust),
            mine = dto.mine,
            channel = Channel.entries.firstOrNull { it.name == dto.channel } ?: Channel.INTERNET,
            pendingSync = false,
            reviewStatus = dto.reviewStatus,
        )
    }

    /** Points the router should keep away from: flooding and blocked roads that are probably real. */
    fun blockedPoints(reports: List<HazardReport>): List<GeoPoint> = reports
        .filter {
            (it.type == HazardType.FLOOD || it.type == HazardType.ROAD_BLOCKED) &&
                (it.label == TrustLabel.VERIFIED || it.label == TrustLabel.LIKELY)
        }
        .map { it.point }
        .distinct()

    private fun reporterPoint(entity: ReportEntity): GeoPoint? {
        val lat = entity.reporterLat ?: return null
        val lon = entity.reporterLon ?: return null
        return GeoPoint(lat, lon)
    }
}
