package com.sahay.engine.risk

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RiskZoneCacheTest {
    private fun zone(id: String, minLat: Double) = RiskZone(
        id, null, RiskLevel.HIGH,
        listOf(listOf(GeoPoint(minLat, 0.0), GeoPoint(minLat, 1.0), GeoPoint(minLat + 1, 1.0), GeoPoint(minLat + 1, 0.0))),
    )

    @Test
    fun zonesAreLoadedOncePerPack() = runTest {
        var loads = 0
        val cache = RiskZoneCache(packKey = { "pack-1" }, loadZones = { loads++; listOf(zone("a", 0.0)) })

        repeat(3) { assertEquals("a", cache.index().zoneAt(GeoPoint(0.5, 0.5))?.id) }
        assertEquals(1, loads)
    }

    @Test
    fun aNewPackReplacesTheCachedZones() = runTest {
        var key: String? = "pack-1"
        var zones = listOf(zone("old", 0.0))
        val cache = RiskZoneCache(packKey = { key }, loadZones = { zones })
        assertEquals("old", cache.index().zoneAt(GeoPoint(0.5, 0.5))?.id)

        key = "pack-2"
        zones = listOf(zone("new", 0.0))
        assertEquals("new", cache.index().zoneAt(GeoPoint(0.5, 0.5))?.id)
    }

    @Test
    fun noPackMeansNoZonesAndNothingIsLoaded() = runTest {
        var loads = 0
        var key: String? = null
        val cache = RiskZoneCache(packKey = { key }, loadZones = { loads++; listOf(zone("a", 0.0)) })

        assertNull(cache.index().zoneAt(GeoPoint(0.5, 0.5)))
        assertEquals(0, loads)

        key = "pack-1"                                       // the pack finished restoring
        assertEquals("a", cache.index().zoneAt(GeoPoint(0.5, 0.5))?.id)
    }

    @Test
    fun deletingThePackClearsTheCache() = runTest {
        var key: String? = "pack-1"
        val cache = RiskZoneCache(packKey = { key }, loadZones = { listOf(zone("a", 0.0)) })
        assertEquals("a", cache.index().zoneAt(GeoPoint(0.5, 0.5))?.id)

        key = null
        assertNull(cache.index().zoneAt(GeoPoint(0.5, 0.5)))
    }
}
