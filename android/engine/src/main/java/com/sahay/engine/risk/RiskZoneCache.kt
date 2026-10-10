package com.sahay.engine.risk

import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.RiskZone
import com.sahay.engine.geo.RiskZoneIndex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the point-in-polygon index of the active pack in memory, so a location fix costs a few
 * comparisons instead of a database read. It is rebuilt when [packKey] changes (a new or deleted pack).
 * Without a pack ([packKey] null) nothing is cached and the index is empty.
 */
internal class RiskZoneCache(
    private val packKey: () -> String?,
    private val loadZones: suspend () -> List<RiskZone>,
) {
    constructor(packs: PackRepository) : this(
        packKey = { packs.activePack.value?.let { "${it.regionId}@${it.packVersion}" } },
        loadZones = packs::riskZones,
    )

    private class Cached(val key: String, val index: RiskZoneIndex)

    private val lock = Mutex()
    private var cached: Cached? = null

    suspend fun index(): RiskZoneIndex = lock.withLock {
        val key = packKey() ?: return@withLock EMPTY.also { cached = null }
        cached?.takeIf { it.key == key }?.index
            ?: RiskZoneIndex(loadZones()).also { cached = Cached(key, it) }
    }

    private companion object {
        val EMPTY = RiskZoneIndex(emptyList())
    }
}
