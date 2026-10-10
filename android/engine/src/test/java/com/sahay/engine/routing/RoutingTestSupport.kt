package com.sahay.engine.routing

import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone
import com.sahay.core.contracts.ShelterStatus
import com.sahay.engine.pack.GeoMath
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos

/**
 * A rectangular street grid. Node `(row, col)` has id `row * cols + col + 1`; rows run north, columns east.
 * [risk] gives the risk_cost of the edge between two grid cells (the same value in both directions).
 */
internal class TestGrid(
    val rows: Int,
    val cols: Int,
    val spacingM: Double = 100.0,
    val originLat: Double = 12.60,
    val originLon: Double = 80.17,
    risk: (r1: Int, c1: Int, r2: Int, c2: Int) -> Double = { _, _, _, _ -> 0.0 },
) {
    private val dLat = spacingM / METRES_PER_DEGREE
    private val dLon = spacingM / (METRES_PER_DEGREE * cos(Math.toRadians(originLat)))

    fun point(row: Int, col: Int) = GeoPoint(originLat + row * dLat, originLon + col * dLon)
    fun id(row: Int, col: Int) = (row * cols + col + 1).toLong()

    val graph: RoadGraph = RoadGraphBuilder(rows * cols, rows * cols * 4).also { builder ->
        for (r in 0 until rows) for (c in 0 until cols) {
            val p = point(r, c)
            builder.addNode(id(r, c), p.lat, p.lon)
        }
        fun connect(r1: Int, c1: Int, r2: Int, c2: Int) {
            val length = GeoMath.haversineM(point(r1, c1), point(r2, c2))
            val cost = risk(r1, c1, r2, c2)
            builder.addEdge(id(r1, c1), id(r2, c2), length, cost)
            builder.addEdge(id(r2, c2), id(r1, c1), length, cost)
        }
        for (r in 0 until rows) for (c in 0 until cols) {
            if (c + 1 < cols) connect(r, c, r, c + 1)
            if (r + 1 < rows) connect(r, c, r + 1, c)
        }
    }.build()

    private companion object {
        const val METRES_PER_DEGREE = 111_195.0
    }
}

internal fun testPack(version: String = "1", downloadedAt: Long = 1L) = PackInfo(
    regionId = "test", regionName = "Test", packVersion = version, bbox = listOf(80.0, 12.0, 81.0, 13.0),
    tripStart = LocalDate.of(2026, 10, 10), tripEnd = LocalDate.of(2026, 10, 12), downloadedAtEpochSec = downloadedAt,
    forecast = emptyList(), historySummary = emptyMap(), incidents = emptyList(), precautions = emptyList(),
    publicKeyB64 = "", sizeBytes = 0,
)

internal fun testPoi(id: String, type: PoiType, point: GeoPoint, status: ShelterStatus = ShelterStatus.OPEN) = Poi(
    id = id, type = type, name = id, nameTa = null, point = point, phone = null,
    isOfficial = true, elevationM = null, capacity = null, status = status,
)

/** A square zone of about ±20 m around [center]. */
internal fun testZone(id: String, level: RiskLevel, center: GeoPoint, halfSideDeg: Double = 0.0002) = RiskZone(
    id = id, name = null, level = level,
    polygons = listOf(
        listOf(
            GeoPoint(center.lat - halfSideDeg, center.lon - halfSideDeg), GeoPoint(center.lat - halfSideDeg, center.lon + halfSideDeg),
            GeoPoint(center.lat + halfSideDeg, center.lon + halfSideDeg), GeoPoint(center.lat + halfSideDeg, center.lon - halfSideDeg),
            GeoPoint(center.lat - halfSideDeg, center.lon - halfSideDeg),
        ),
    ),
)

/** In-memory pack contents; counts how often the road graph is requested. */
internal class FakeRoutingData(
    var pack: PackInfo? = testPack(),
    var graph: RoadGraph? = null,
    var pois: List<Poi> = emptyList(),
    var zones: List<RiskZone> = emptyList(),
) : RoutingDataSource {
    val graphLoads = AtomicInteger()

    override suspend fun activePack(): PackInfo? = pack
    override suspend fun roadGraph(): RoadGraph? = graph.also { graphLoads.incrementAndGet() }
    override suspend fun pois(types: Set<PoiType>): List<Poi> = pois.filter { it.type in types }
    override suspend fun riskZones(): List<RiskZone> = zones
}
