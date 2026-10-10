package com.sahay.engine.routing

import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.RiskZone
import com.sahay.engine.pack.RealPackRepository

/** What routing needs from the active pack. A seam so tests can supply a synthetic network. */
internal interface RoutingDataSource {
    /** The active pack once startup restore has finished; null if there is none. */
    suspend fun activePack(): PackInfo?

    /** The road network of the active pack, or null if there is none or it cannot be read. */
    suspend fun roadGraph(): RoadGraph?

    /** POIs with the locally known shelter status merged in. */
    suspend fun pois(types: Set<PoiType>): List<Poi>

    suspend fun riskZones(): List<RiskZone>
}

internal class RepositoryRoutingData(private val repository: RealPackRepository) : RoutingDataSource {
    override suspend fun activePack(): PackInfo? = repository.awaitActivePack()

    // The SQLite read hops to IO inside the repository; the array build then runs on the caller's dispatcher.
    override suspend fun roadGraph(): RoadGraph? = repository.readRoadGraph()?.build()

    override suspend fun pois(types: Set<PoiType>): List<Poi> = repository.pois(types)

    override suspend fun riskZones(): List<RiskZone> = repository.riskZones()
}
