package com.sahay.engine.fake

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.RiskMonitor
import com.sahay.core.contracts.RiskZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeRiskMonitor @Inject constructor() : RiskMonitor {

    private val zone = MutableStateFlow<RiskZone?>(null)
    override val currentZone: StateFlow<RiskZone?> = zone.asStateFlow()

    override suspend fun zoneAt(point: GeoPoint): RiskZone? =
        FakeEngineData.riskZones.firstOrNull { z -> z.polygons.any { FakeEngineData.contains(it, point) } }

    // The fake user stands at the demo centre, which is outside the HIGH zone.
    override fun startMonitoring() {
        zone.value = FakeEngineData.riskZones.firstOrNull { z ->
            z.polygons.any { FakeEngineData.contains(it, FakeEngineData.center) }
        }
    }

    override fun stopMonitoring() {
        zone.value = null
    }
}
