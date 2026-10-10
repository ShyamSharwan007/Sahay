package com.sahay.engine.pack

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.database.getDoubleOrNull
import androidx.core.database.getIntOrNull
import androidx.core.database.getStringOrNull
import com.sahay.core.contracts.AlertKeyword
import com.sahay.core.contracts.AlertTemplate
import com.sahay.core.contracts.Embassy
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.Phrase
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.RadioStation
import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone
import com.sahay.engine.routing.RoadGraphBuilder
import java.io.File

/** One routing-graph node (`node` table). */
internal data class GraphNode(val id: Long, val point: GeoPoint, val elevationM: Double?)

/** One directed routing-graph edge (`edge` table). Edges are stored in both directions unless one-way. */
internal data class GraphEdge(
    val fromId: Long,
    val toId: Long,
    val lengthM: Double,
    val riskCost: Double,          // 0..5
    val roadClass: String?,
)

/**
 * Blocking, read-only access to one pack file (schema: docs/CONTRACTS.md §5.2). Callers run it on
 * Dispatchers.IO. POIs and risk zones are parsed once and cached for the life of this object, which is
 * one pack version, so the cache never goes stale.
 *
 * Every read holds a reference on the database, so [close] never pulls the file away from a running query.
 */
internal class PackQueries private constructor(private val db: SQLiteDatabase) : AutoCloseable {

    private val poiCache: List<Poi> by lazy { readAllPois() }
    private val riskZoneCache: List<RiskZone> by lazy { readRiskZones() }

    override fun close() = db.close()

    // ------------------------------------------------------------------ meta / verification

    fun meta(key: String): String? = read {
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    /** Row count of one of the tables a valid pack must contain. */
    fun rowCount(table: Table): Long = read {
        db.rawQuery("SELECT COUNT(*) FROM ${table.sqlName}", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }

    enum class Table(val sqlName: String) { POI("poi"), NODE("node"), EDGE("edge") }

    // ------------------------------------------------------------------ places

    /** All POIs of the pack (status is UNKNOWN here; the repository merges live shelter status). */
    fun pois(): List<Poi> = poiCache

    /**
     * The [limit] POIs of [type] closest to [from], nearest first, with distance in metres.
     * Searches growing boxes (SQL pre-filter) so only nearby rows are read; a hit inside radius R is
     * guaranteed to be complete because the box contains the whole circle of radius R.
     */
    fun nearestPois(from: GeoPoint, type: PoiType, limit: Int): List<Pair<Poi, Double>> = read {
        if (limit <= 0) return@read emptyList()
        for (radiusM in SEARCH_RADII_M) {
            val within = queryPois(type, GeoMath.boxAround(from, radiusM))
                .map { it to GeoMath.haversineM(from, it.point) }
                .filter { it.second <= radiusM }
            if (within.size >= limit) return@read within.sortedBy { it.second }.take(limit)
        }
        queryPois(type, box = null)                                   // fewer than `limit` nearby: scan all of this type
            .map { it to GeoMath.haversineM(from, it.point) }
            .sortedBy { it.second }
            .take(limit)
    }

    private fun readAllPois(): List<Poi> = read {
        db.rawQuery("SELECT $POI_COLUMNS FROM poi ORDER BY id", null).use { it.mapRows(Cursor::toPoi) }
    }

    private fun queryPois(type: PoiType, box: LatLonBox?): List<Poi> {
        val where = StringBuilder("type = ?")
        val args = mutableListOf(type.name)
        if (box != null) {
            where.append(" AND lat BETWEEN CAST(? AS REAL) AND CAST(? AS REAL)")
            args += listOf(box.minLat.toString(), box.maxLat.toString())
            if (box.minLon != null && box.maxLon != null) {
                where.append(" AND lon BETWEEN CAST(? AS REAL) AND CAST(? AS REAL)")
                args += listOf(box.minLon.toString(), box.maxLon.toString())
            }
        }
        return db.rawQuery("SELECT $POI_COLUMNS FROM poi WHERE $where", args.toTypedArray()).use { it.mapRows(Cursor::toPoi) }
    }

    /** Zones whose GeoJSON cannot be parsed are skipped (and logged) so one bad row cannot hide the others. */
    fun riskZones(): List<RiskZone> = riskZoneCache

    private fun readRiskZones(): List<RiskZone> = read {
        db.rawQuery("SELECT id, name, level, geojson FROM risk_zone ORDER BY id", null).use { cursor ->
            cursor.mapRows { row ->
                val level = RiskLevel.entries.firstOrNull { it.name == row.getString(2).uppercase() } ?: return@mapRows null
                val polygons = try {
                    GeoJsonPolygons.outerRings(row.getString(3))
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Skipping risk zone ${row.getString(0)}: ${e.message}")
                    return@mapRows null
                }
                if (polygons.isEmpty()) null else RiskZone(row.getString(0), row.getStringOrNull(1), level, polygons)
            }
        }
    }

    // ------------------------------------------------------------------ alert text

    fun alertTemplate(code: String, lang: String): AlertTemplate? =
        findTemplate(code, lang) ?: if (lang != FALLBACK_LANG) findTemplate(code, FALLBACK_LANG) else null

    private fun findTemplate(code: String, lang: String): AlertTemplate? = read {
        db.rawQuery(
            "SELECT $TEMPLATE_COLUMNS FROM alert_template WHERE code = ? AND lang = ?",
            arrayOf(code, lang),
        ).use { if (it.moveToFirst()) it.toTemplate() else null }
    }

    /** One template per code in [lang], using English for codes that have no [lang] text. */
    fun alertTemplates(lang: String): List<AlertTemplate> = read {
        val rows = db.rawQuery(
            "SELECT $TEMPLATE_COLUMNS FROM alert_template WHERE lang IN (?, ?) ORDER BY code",
            arrayOf(lang, FALLBACK_LANG),
        ).use { it.mapRows(Cursor::toTemplate) }
        rows.groupBy { it.code }.values.map { versions -> versions.firstOrNull { it.lang == lang } ?: versions.first() }
    }

    fun alertKeywords(): List<AlertKeyword> = read {
        db.rawQuery("SELECT code, lang, keyword FROM alert_keyword ORDER BY code, lang", null).use { cursor ->
            cursor.mapRows { AlertKeyword(it.getString(0), it.getString(1), it.getString(2)) }
        }
    }

    // ------------------------------------------------------------------ phrasebook, embassy, radio

    /** Phrases in [lang]; ids missing in that language fall back to English. Pack order is preserved. */
    fun phrases(lang: String): List<Phrase> = read {
        val rows = db.rawQuery(
            "SELECT id, lang, category, text, icon FROM phrase WHERE lang IN (?, ?) ORDER BY rowid",
            arrayOf(lang, FALLBACK_LANG),
        ).use { cursor ->
            cursor.mapRows { Phrase(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getStringOrNull(4)) }
        }
        rows.groupBy { it.id }.values.map { versions -> versions.firstOrNull { it.lang == lang } ?: versions.first() }
    }

    fun embassy(countryCode: String): Embassy? = read {
        db.rawQuery(
            "SELECT country_code, name, phone, address, lat, lon, url FROM embassy WHERE country_code = ? COLLATE NOCASE",
            arrayOf(countryCode.trim()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val lat = cursor.getDoubleOrNull(4)
            val lon = cursor.getDoubleOrNull(5)
            Embassy(
                countryCode = cursor.getString(0),
                name = cursor.getString(1),
                phone = cursor.getStringOrNull(2),
                address = cursor.getStringOrNull(3),
                point = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
                url = cursor.getStringOrNull(6),
            )
        }
    }

    fun radios(): List<RadioStation> = read {
        db.rawQuery("SELECT name, frequency, lang FROM radio ORDER BY rowid", null).use { cursor ->
            cursor.mapRows { RadioStation(it.getString(0), it.getString(1), it.getStringOrNull(2)) }
        }
    }

    // ------------------------------------------------------------------ routing graph (used by RoutingEngine)

    fun graphNodes(): List<GraphNode> = read {
        db.rawQuery("SELECT id, lat, lon, elevation_m FROM node", null).use { cursor ->
            cursor.mapRows { GraphNode(it.getLong(0), GeoPoint(it.getDouble(1), it.getDouble(2)), it.getDoubleOrNull(3)) }
        }
    }

    fun graphEdges(): List<GraphEdge> = read {
        db.rawQuery("SELECT from_id, to_id, length_m, risk_cost, road_class FROM edge ORDER BY from_id", null).use { cursor ->
            cursor.mapRows { GraphEdge(it.getLong(0), it.getLong(1), it.getDouble(2), it.getDouble(3), it.getStringOrNull(4)) }
        }
    }

    /**
     * Streams the whole road network straight into a primitive-array builder (no per-row objects), nodes
     * ordered by id. Call [RoadGraphBuilder.build] on a compute dispatcher.
     */
    fun readRoadGraph(): RoadGraphBuilder = read {
        val builder = RoadGraphBuilder(rowCount(Table.NODE).toInt(), rowCount(Table.EDGE).toInt())
        db.rawQuery("SELECT id, lat, lon FROM node ORDER BY id", null).use { cursor ->
            while (cursor.moveToNext()) builder.addNode(cursor.getLong(0), cursor.getDouble(1), cursor.getDouble(2))
        }
        db.rawQuery("SELECT from_id, to_id, length_m, risk_cost FROM edge", null).use { cursor ->
            while (cursor.moveToNext()) builder.addEdge(cursor.getLong(0), cursor.getLong(1), cursor.getDouble(2), cursor.getDouble(3))
        }
        builder
    }

    // ------------------------------------------------------------------ helpers

    private inline fun <T> read(block: () -> T): T {
        db.acquireReference()
        try {
            return block()
        } finally {
            db.releaseReference()
        }
    }

    companion object {
        private const val TAG = "PackQueries"
        private const val FALLBACK_LANG = "en"
        private const val POI_COLUMNS = "id, type, name, name_ta, lat, lon, phone, is_official, elevation_m, capacity"
        private const val TEMPLATE_COLUMNS = "code, lang, severity, title, body"

        /** Box sizes tried by [nearestPois] before it falls back to scanning every POI of the type. */
        private val SEARCH_RADII_M = doubleArrayOf(2_000.0, 10_000.0, 50_000.0)

        /** Opens [file] read-only. @throws android.database.sqlite.SQLiteException if it is missing or not a database. */
        fun open(file: File): PackQueries = PackQueries(
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS),
        )
    }
}

private fun Cursor.toPoi(): Poi? {
    val type = PoiType.entries.firstOrNull { it.name == getString(1) } ?: return null   // unknown type: skip row
    return Poi(
        id = getString(0),
        type = type,
        name = getString(2),
        nameTa = getStringOrNull(3),
        point = GeoPoint(getDouble(4), getDouble(5)),
        phone = getStringOrNull(6),
        isOfficial = getInt(7) != 0,
        elevationM = getDoubleOrNull(8),
        capacity = getIntOrNull(9),
    )
}

private fun Cursor.toTemplate() = AlertTemplate(getString(0), getString(1), getInt(2), getString(3), getString(4))

private inline fun <T : Any> Cursor.mapRows(transform: (Cursor) -> T?): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) transform(this)?.let(out::add)
    return out
}
