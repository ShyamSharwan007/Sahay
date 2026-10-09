package com.sahay.engine.fake

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.Route
import com.sahay.core.contracts.RoutingEngine
import javax.inject.Inject
import javax.inject.Singleton

/** Returns the same 5-point, 650 m walking route regardless of input. */
@Singleton
class FakeRoutingEngine @Inject constructor() : RoutingEngine {

    override suspend fun routeTo(from: GeoPoint, to: GeoPoint): Route? {
        // Use the POI at the requested point as destination when there is one.
        val destination = FakeEngineData.pois.firstOrNull { it.point == to }
        return FakeEngineData.fakeRoute(destination ?: FakeEngineData.pois.first())
    }

    override suspend fun routeToNearestSafe(from: GeoPoint): Route? = FakeEngineData.fakeRoute()

    override fun setBlockedPoints(points: List<GeoPoint>) {
        // Nothing to avoid in the fake graph.
    }
}
