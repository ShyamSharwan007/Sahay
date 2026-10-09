package com.sahay.engine.pack

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Test pack files. Uses `samples/mahabalipuram-sample.sqlite` (copied to src/test/resources by the
 * `copySampleSqlite` Gradle task) when it exists, otherwise builds a small pack with the same schema
 * (docs/CONTRACTS.md §5.2). Tests compare repository results with raw SQL, so they hold for either file.
 */
internal object TestPacks {
    private const val SAMPLE_RESOURCE = "/mahabalipuram-sample.sqlite"

    val sampleIsPresent: Boolean get() = TestPacks::class.java.getResource(SAMPLE_RESOURCE) != null

    /** Writes the pack to [target] and returns it. */
    fun materialize(target: File): File {
        target.parentFile?.mkdirs()
        val sample = TestPacks::class.java.getResourceAsStream(SAMPLE_RESOURCE)
        if (sample != null) {
            sample.use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            buildSyntheticPack(target)
        }
        return target
    }

    /** Raw read-only handle, used by tests as an oracle that does not go through the code under test. */
    fun openRaw(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

    private val SCHEMA = listOf(
        "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        """CREATE TABLE poi (id TEXT PRIMARY KEY, type TEXT NOT NULL, name TEXT NOT NULL, name_ta TEXT, lat REAL NOT NULL,
           lon REAL NOT NULL, phone TEXT, is_official INTEGER NOT NULL DEFAULT 0, elevation_m REAL, capacity INTEGER)""",
        "CREATE TABLE node (id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, elevation_m REAL)",
        """CREATE TABLE edge (from_id INTEGER NOT NULL, to_id INTEGER NOT NULL, length_m REAL NOT NULL,
           risk_cost REAL NOT NULL DEFAULT 0, road_class TEXT)""",
        "CREATE INDEX edge_from ON edge(from_id)",
        "CREATE TABLE risk_zone (id TEXT PRIMARY KEY, name TEXT, level TEXT NOT NULL, geojson TEXT NOT NULL)",
        """CREATE TABLE alert_template (code TEXT NOT NULL, lang TEXT NOT NULL, severity INTEGER NOT NULL, title TEXT NOT NULL,
           body TEXT NOT NULL, PRIMARY KEY (code, lang))""",
        "CREATE TABLE alert_keyword (code TEXT NOT NULL, lang TEXT NOT NULL, keyword TEXT NOT NULL)",
        """CREATE TABLE phrase (id TEXT NOT NULL, lang TEXT NOT NULL, category TEXT NOT NULL, text TEXT NOT NULL, icon TEXT,
           PRIMARY KEY (id, lang))""",
        """CREATE TABLE embassy (country_code TEXT PRIMARY KEY, name TEXT NOT NULL, phone TEXT, address TEXT, lat REAL, lon REAL,
           url TEXT)""",
        "CREATE TABLE radio (name TEXT NOT NULL, frequency TEXT NOT NULL, lang TEXT)",
    )

    private const val HIGH_ZONE_WITH_HOLE =
        """{"type":"Polygon","coordinates":[[[80.19,12.61],[80.20,12.61],[80.20,12.62],[80.19,12.62],[80.19,12.61]],
           [[80.193,12.613],[80.196,12.613],[80.196,12.616],[80.193,12.613]]]}"""
    private const val MEDIUM_TWO_POLYGONS =
        """{"type":"MultiPolygon","coordinates":[
           [[[80.17,12.60],[80.18,12.60],[80.18,12.61],[80.17,12.60]]],
           [[[80.21,12.64],[80.22,12.64],[80.22,12.65],[80.21,12.65],[80.21,12.64]]]]}"""

    private fun buildSyntheticPack(target: File) {
        target.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(target, null)
        try {
            db.beginTransaction()
            SCHEMA.forEach(db::execSQL)
            fun insert(table: String, vararg values: Pair<String, Any?>) {
                val cv = android.content.ContentValues()
                values.forEach { (k, v) ->
                    when (v) {
                        null -> cv.putNull(k)
                        is Int -> cv.put(k, v)
                        is Double -> cv.put(k, v)
                        else -> cv.put(k, v.toString())
                    }
                }
                db.insertOrThrow(table, null, cv)
            }
            mapOf(
                "region_id" to "mahabalipuram", "region_name" to "Mahabalipuram", "pack_version" to "test.1",
                "built_at" to "1760000000", "bbox" to "[80.16,12.59,80.21,12.65]", "public_key_b64" to "",
            ).forEach { (k, v) -> insert("meta", "key" to k, "value" to v) }

            fun poi(id: String, type: String, name: String, lat: Double, lon: Double, official: Int = 1) =
                insert("poi", "id" to id, "type" to type, "name" to name, "name_ta" to "$name (ta)", "lat" to lat, "lon" to lon,
                    "phone" to null, "is_official" to official, "elevation_m" to 10.0, "capacity" to null)
            poi("poi_s1", "SHELTER", "School shelter", 12.6262, 80.1921)
            poi("poi_s2", "SHELTER", "Cyclone shelter", 12.6171, 80.1890)
            poi("poi_c1", "CANDIDATE_SHELTER", "Community hall", 12.6235, 80.1968, official = 0)
            poi("poi_h1", "HOSPITAL", "Govt hospital", 12.6249, 80.1902)
            poi("poi_h2", "HOSPITAL", "Health centre", 12.6190, 80.1932)
            poi("poi_h_far", "HOSPITAL", "Chennai hospital", 13.0827, 80.2707)          // ~55 km away
            poi("poi_p1", "POLICE", "Police station", 12.6226, 80.1913)

            listOf(1 to 12.62, 2 to 12.621, 3 to 12.622).forEach { (id, lat) ->
                insert("node", "id" to id, "lat" to lat, "lon" to 80.19, "elevation_m" to 5.0)
            }
            listOf(1 to 2, 2 to 1, 2 to 3, 3 to 2).forEach { (from, to) ->
                insert("edge", "from_id" to from, "to_id" to to, "length_m" to 111.0, "risk_cost" to 0.5, "road_class" to "residential")
            }

            insert("risk_zone", "id" to "z_high", "name" to "Low coast", "level" to "HIGH", "geojson" to HIGH_ZONE_WITH_HOLE)
            insert("risk_zone", "id" to "z_medium", "name" to null, "level" to "MEDIUM", "geojson" to MEDIUM_TWO_POLYGONS)
            insert("risk_zone", "id" to "z_bad", "name" to "Broken", "level" to "HIGH", "geojson" to "{not json")

            fun template(code: String, lang: String, sev: Int) =
                insert("alert_template", "code" to code, "lang" to lang, "severity" to sev, "title" to "$code title $lang", "body" to "$code body $lang")
            template("FLD_EVAC", "en", 3); template("FLD_EVAC", "de", 3); template("TEST", "en", 0)
            insert("alert_keyword", "code" to "FLD_EVAC", "lang" to "en", "keyword" to "evacuate")
            insert("alert_keyword", "code" to "FLD_EVAC", "lang" to "ta", "keyword" to "வெளியேறு")

            fun phrase(id: String, lang: String, category: String) =
                insert("phrase", "id" to id, "lang" to lang, "category" to category, "text" to "$id $lang", "icon" to null)
            phrase("tourist_need_help", "en", "emergency"); phrase("tourist_need_help", "ta", "emergency"); phrase("tourist_need_help", "de", "emergency")
            phrase("yes", "en", "basic"); phrase("yes", "ta", "basic")
            phrase("thank_you", "en", "basic")

            insert("embassy", "country_code" to "DE", "name" to "German Consulate", "phone" to "+91 44 0000", "address" to "Chennai",
                "lat" to 13.04, "lon" to 80.25, "url" to "https://example.org")
            insert("embassy", "country_code" to "FR", "name" to "French Consulate", "phone" to null, "address" to null,
                "lat" to null, "lon" to null, "url" to null)
            insert("radio", "name" to "AIR Chennai", "frequency" to "101.4 FM", "lang" to "ta")
            db.setTransactionSuccessful()
            db.endTransaction()
        } finally {
            db.close()
        }
    }
}
