package com.sahay.comms.fake

import com.sahay.core.contracts.Channel
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.HazardType
import com.sahay.core.contracts.ReportRepository
import com.sahay.core.contracts.TrustLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeReportRepository @Inject constructor() : ReportRepository {

    private val list = MutableStateFlow(initialReports())
    private val pending = MutableStateFlow(list.value.count { it.pendingSync })

    override val reports: StateFlow<List<HazardReport>> = list.asStateFlow()
    override val pendingCount: StateFlow<Int> = pending.asStateFlow()

    override suspend fun submit(type: HazardType, point: GeoPoint, note: String?, photoJpeg: ByteArray?): HazardReport {
        val report = HazardReport(
            id = "r_local_${System.nanoTime()}", type = type, point = point, note = note,
            photoUrl = null, createdAtEpochSec = nowSec(), trustScore = 0.2,
            label = TrustLabel.UNCONFIRMED, mine = true, channel = Channel.INTERNET, pendingSync = true,
        )
        publish(listOf(report) + list.value)
        return report
    }

    override suspend fun refresh(): Result<Unit> = Result.success(Unit)

    private fun publish(reports: List<HazardReport>) {
        list.value = reports
        pending.value = reports.count { it.pendingSync }
    }

    private fun nowSec() = System.currentTimeMillis() / 1000

    private fun initialReports(): List<HazardReport> {
        val now = nowSec()
        fun report(id: String, type: HazardType, lat: Double, label: TrustLabel, score: Double, pending: Boolean = false) =
            HazardReport(
                id = id, type = type, point = GeoPoint(lat, 80.1940), note = null, photoUrl = null,
                createdAtEpochSec = now - 900, trustScore = score, label = label, mine = pending,
                channel = Channel.INTERNET, pendingSync = pending,
            )
        return listOf(
            report("r_verified", HazardType.FLOOD, 12.6215, TrustLabel.VERIFIED, 0.82),
            report("r_likely", HazardType.ROAD_BLOCKED, 12.6225, TrustLabel.LIKELY, 0.55),
            report("r_unconfirmed", HazardType.POWER_LINE, 12.6195, TrustLabel.UNCONFIRMED, 0.21),
            report("r_pending", HazardType.LANDSLIDE, 12.6240, TrustLabel.UNCONFIRMED, 0.2, pending = true),
        )
    }
}
