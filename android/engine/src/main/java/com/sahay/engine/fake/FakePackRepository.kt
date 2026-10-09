package com.sahay.engine.fake

import com.sahay.core.contracts.AlertKeyword
import com.sahay.core.contracts.AlertTemplate
import com.sahay.core.contracts.Embassy
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.PackDownloadState
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.Phrase
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.RadioStation
import com.sahay.core.contracts.Region
import com.sahay.core.contracts.RiskZone
import com.sahay.core.contracts.ShelterStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakePackRepository @Inject constructor() : PackRepository {

    private val pack = MutableStateFlow<PackInfo?>(null)
    override val activePack: StateFlow<PackInfo?> = pack.asStateFlow()

    private val shelterStatus = MutableStateFlow<Map<String, ShelterStatus>>(emptyMap())

    override suspend fun regions(): List<Region> = FakeEngineData.regions

    /** MANIFEST → DATA → MAP → ASSETS → VERIFY, two ticks each, about 4 s in total. */
    override fun download(regionId: String, start: LocalDate, end: LocalDate): Flow<PackDownloadState> = flow {
        for (step in DOWNLOAD_STEPS) {
            emit(PackDownloadState.Downloading(step, 0f))
            delay(STEP_DELAY_MS)
            emit(PackDownloadState.Downloading(step, 0.5f))
            delay(STEP_DELAY_MS)
        }
        val info = FakeEngineData.packInfo(regionId, start, end, System.currentTimeMillis() / 1000)
        pack.value = info
        emit(PackDownloadState.Done(info))
    }

    override suspend fun deletePack() {
        pack.value = null
    }

    override suspend fun pois(types: Set<PoiType>): List<Poi> =
        FakeEngineData.pois
            .filter { it.type in types }
            .map { it.copy(status = shelterStatus.value[it.id] ?: it.status) }

    override suspend fun nearestPois(from: GeoPoint, type: PoiType, limit: Int): List<Pair<Poi, Double>> =
        pois(setOf(type))
            .map { it to FakeEngineData.distanceM(from, it.point) }
            .sortedBy { it.second }
            .take(limit)

    override suspend fun riskZones(): List<RiskZone> = FakeEngineData.riskZones

    override suspend fun alertTemplate(code: String, lang: String): AlertTemplate? =
        FakeEngineData.alertTemplates.firstOrNull { it.code == code }?.copy(lang = "en")

    // Only English exists in the fake data, so every language falls back to it.
    override suspend fun alertTemplates(lang: String): List<AlertTemplate> = FakeEngineData.alertTemplates

    override suspend fun alertKeywords(): List<AlertKeyword> = FakeEngineData.alertKeywords

    override suspend fun phrases(lang: String): List<Phrase> {
        val wanted = if (lang == "ta") "ta" else "en"
        return FakeEngineData.phrases.filter { it.lang == wanted }
    }

    override suspend fun embassy(countryCode: String): Embassy? = FakeEngineData.embassies[countryCode.uppercase()]

    override suspend fun radios(): List<RadioStation> = FakeEngineData.radios

    override suspend fun updateShelterStatus(shelterId: String, status: ShelterStatus, updatedAtEpochSec: Long) {
        shelterStatus.value = shelterStatus.value + (shelterId to status)
    }

    private companion object {
        val DOWNLOAD_STEPS = listOf("MANIFEST", "DATA", "MAP", "ASSETS", "VERIFY")
        const val STEP_DELAY_MS = 400L
    }
}
