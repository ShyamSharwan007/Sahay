package com.sahay.engine.pack

import android.app.Application
import androidx.room.Room
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.ShelterStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Map side of a pack is not under test here; this just records what the repository asks of it. */
private class RecordingMapStore : OfflineMapStore {
    var deleteAllCalls = 0
    override suspend fun download(request: OfflineMapRequest, onProgress: (Float) -> Unit) = Unit
    override suspend fun keepOnly(downloadId: String) = Unit
    override suspend fun discard(downloadId: String) = Unit
    override suspend fun deleteAll() { deleteAllCalls++ }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealPackRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val app: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var filesDir: File
    private lateinit var storage: PackStorage
    private lateinit var maps: RecordingMapStore
    private lateinit var statusStore: ShelterStatusStore
    private lateinit var raw: android.database.sqlite.SQLiteDatabase
    private lateinit var regionId: String
    private lateinit var packVersion: String

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        storage = PackStorage(filesDir)
        maps = RecordingMapStore()
        val dao = Room.inMemoryDatabaseBuilder(app, ShelterStatusDatabase::class.java).allowMainThreadQueries().build().dao()
        statusStore = ShelterStatusStore(dao)
    }

    @After
    fun tearDown() {
        if (this::raw.isInitialized) raw.close()
        scope.cancel()
    }

    // ------------------------------------------------------------------ helpers

    private fun newRepository() = RealPackRepository(storage, PackNetwork(), statusStore, maps, { Long.MAX_VALUE }, scope)

    /** Lays out an installed pack exactly as a finished download would, then starts a repository on it. */
    private fun repositoryWithInstalledPack(): RealPackRepository {
        val staging = TestPacks.materialize(File(tmp.root, "source.sqlite"))
        raw = TestPacks.openRaw(staging)
        regionId = raw.metaValue("region_id")
        packVersion = raw.metaValue("pack_version").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = storage.versionDir(regionId, packVersion)
        dir.mkdirs()
        staging.copyTo(storage.sqliteFile(dir))
        storage.manifestFile(dir).writeText(manifestJson())
        storage.writeActive(ActivePointer(regionId, packVersion, "2026-10-10", "2026-10-14", 1_760_000_000))
        return newRepository()
    }

    private fun manifestJson() = """
        {"regionId":"$regionId","regionName":"Test region","packVersion":"$packVersion","bbox":[80.16,12.59,80.21,12.65],
         "sqliteUrl":"https://example.org/pack.sqlite","sqliteBytes":1000,"sqliteSha256":"00","publicKeyB64":"KEY",
         "forecast":[{"date":"2026-10-10","rainMm":12.4,"windKmh":22.0,"maxTempC":31.0,"riskLevel":"LOW"},
                     {"date":"not-a-date","rainMm":1.0,"windKmh":1.0,"maxTempC":1.0,"riskLevel":"LOW"}],
         "history":{"years":[2021],"avgRainMm":8.1,"heavyRainDays":3,"summary":{"en":"Wet Octobers","de":"Nasse Oktober"}},
         "incidents":[{"date":"2023-12-04","type":"cyclone","title":{"en":"Michaung"},"summary":{"en":"Heavy rain"},"sourceUrl":null}],
         "precautions":[{"id":"p1","severity":2,"title":{"en":"Charge phone"},"body":{"en":"Keep it charged"}}],
         "someFutureField":42}
    """.trimIndent()

    private fun android.database.sqlite.SQLiteDatabase.metaValue(key: String): String =
        rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { it.moveToFirst(); it.getString(0) }

    private fun android.database.sqlite.SQLiteDatabase.count(table: String): Int =
        rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    private fun <T> android.database.sqlite.SQLiteDatabase.rows(sql: String, vararg args: String, map: (android.database.Cursor) -> T): List<T> =
        rawQuery(sql, args).use { c -> generateSequence { if (c.moveToNext()) map(c) else null }.toList() }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(30_000) { block() } }

    private fun RealPackRepository.awaitActive() = blocking { activePack.first { it != null }!! }

    // ------------------------------------------------------------------ restore / delete

    @Test
    fun activePack_isRestoredFromActiveJson() {
        val repo = repositoryWithInstalledPack()
        val info = repo.awaitActive()

        assertEquals(regionId, info.regionId)
        assertEquals(packVersion, info.packVersion)
        assertEquals("2026-10-10", info.tripStart.toString())
        assertEquals("2026-10-14", info.tripEnd.toString())
        assertEquals(1_760_000_000L, info.downloadedAtEpochSec)
        assertEquals("KEY", info.publicKeyB64)
        assertEquals(listOf(80.16, 12.59, 80.21, 12.65), info.bbox)
        assertEquals("Nasse Oktober", info.historySummary["de"])
        assertEquals(1, info.forecast.size)                       // the entry with a bad date is skipped
        assertEquals(12.4, info.forecast[0].rainMm, 0.0)
        assertEquals(1, info.incidents.size)
        assertEquals("Charge phone", info.precautions.single().title["en"])
        assertEquals(storage.sqliteFile(storage.versionDir(regionId, packVersion)).length(), info.sizeBytes)
    }

    @Test
    fun noActiveJson_meansNoPackAndEmptyAnswers() {
        val repo = newRepository()
        blocking {
            assertNull(repo.activePack.value)
            assertTrue(repo.pois().isEmpty())
            assertTrue(repo.nearestPois(GeoPoint(12.6, 80.19), PoiType.SHELTER).isEmpty())
            assertTrue(repo.riskZones().isEmpty())
            assertNull(repo.alertTemplate("FLD_EVAC", "en"))
            assertTrue(repo.alertTemplates("en").isEmpty())
            assertTrue(repo.alertKeywords().isEmpty())
            assertTrue(repo.phrases("ta").isEmpty())
            assertNull(repo.embassy("DE"))
            assertTrue(repo.radios().isEmpty())
            assertTrue(repo.graphNodes().isEmpty())
            assertTrue(repo.graphEdges().isEmpty())
        }
    }

    @Test
    fun corruptActiveJson_meansNoPack() {
        storage.root.mkdirs()
        File(storage.root, PackStorage.ACTIVE_FILE).writeText("{ this is not json")
        val repo = newRepository()
        blocking { assertNull(repo.pois().firstOrNull()); assertNull(repo.activePack.value) }
    }

    @Test
    fun activeJsonPointingAtMissingFiles_meansNoPack() {
        storage.writeActive(ActivePointer("ghost", "v1", "2026-10-10", "2026-10-11", 0))
        val repo = newRepository()
        blocking { assertTrue(repo.pois().isEmpty()); assertNull(repo.activePack.value) }
    }

    @Test
    fun activeJsonWithPathTraversal_isIgnored() {
        storage.root.mkdirs()
        File(storage.root, PackStorage.ACTIVE_FILE).writeText(
            """{"regionId":"..","packVersion":"..","tripStart":"2026-10-10","tripEnd":"2026-10-11","downloadedAtEpochSec":0}""",
        )
        val repo = newRepository()
        blocking { assertNull(repo.pois().firstOrNull()); assertNull(repo.activePack.value) }
    }

    @Test
    fun deletePack_removesFilesStateStatusesAndOfflineMap() {
        val repo = repositoryWithInstalledPack()
        repo.awaitActive()
        blocking { repo.updateShelterStatus("poi_x", ShelterStatus.OPEN, 100) }

        blocking { repo.deletePack() }

        assertNull(repo.activePack.value)
        assertFalse(storage.root.exists())
        assertEquals(1, maps.deleteAllCalls)
        blocking {
            assertTrue(repo.pois().isEmpty())
            assertTrue(statusStore.all().isEmpty())
        }
        // a restart finds nothing to restore
        val restarted = newRepository()
        blocking { assertNull(restarted.pois().firstOrNull()); assertNull(restarted.activePack.value) }
    }

    // ------------------------------------------------------------------ places

    @Test
    fun pois_matchTheRowsOfThePack() {
        val repo = repositoryWithInstalledPack()
        val expected = raw.rows("SELECT id, type, name, name_ta, lat, lon, phone, is_official, elevation_m, capacity FROM poi ORDER BY id") { c ->
            listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getDouble(4), c.getDouble(5), c.getString(6),
                c.getInt(7) != 0, if (c.isNull(8)) null else c.getDouble(8), if (c.isNull(9)) null else c.getInt(9))
        }
        assertTrue("pack should contain POIs", expected.isNotEmpty())

        val actual = blocking { repo.pois() }

        assertEquals(expected.size, actual.size)
        val actualRows = actual.map { listOf(it.id, it.type.name, it.name, it.nameTa, it.point.lat, it.point.lon, it.phone, it.isOfficial, it.elevationM, it.capacity) }
        assertEquals(expected, actualRows)
        assertTrue(actual.all { it.status == ShelterStatus.UNKNOWN })
    }

    @Test
    fun pois_filterByType() {
        val repo = repositoryWithInstalledPack()
        for (type in PoiType.entries) {
            val expected = raw.rows("SELECT id FROM poi WHERE type = ? ORDER BY id", type.name) { it.getString(0) }
            val actual = blocking { repo.pois(setOf(type)) }.map { it.id }
            assertEquals("type $type", expected, actual)
        }
        assertEquals(raw.count("poi"), blocking { repo.pois(PoiType.entries.toSet()).size })
        assertTrue(blocking { repo.pois(emptySet()) }.isEmpty())
    }

    @Test
    fun nearestPois_equalsBruteForceSortedByDistance() {
        val repo = repositoryWithInstalledPack()
        val origins = listOf(
            GeoPoint(12.6208, 80.1945),       // centre of the Mahabalipuram demo area
            GeoPoint(12.6500, 80.2100),       // corner of its bbox
            GeoPoint(12.84, 80.15),           // IIITDM area
        )
        for (type in PoiType.entries) for (origin in origins) for (limit in listOf(1, 3, 5, 100)) {
            val all = blocking { repo.pois(setOf(type)) }
            val expected = all.map { it to GeoMath.haversineM(origin, it.point) }.sortedBy { it.second }.take(limit)

            val actual = blocking { repo.nearestPois(origin, type, limit) }

            assertEquals("type=$type origin=$origin limit=$limit", expected.map { it.first.id }, actual.map { it.first.id })
            actual.zip(expected).forEach { (a, e) -> assertEquals(e.second, a.second, 1e-6) }
            assertEquals(actual.map { it.second }, actual.map { it.second }.sorted())
        }
    }

    @Test
    fun nearestPois_findsPoisFarOutsideThePackArea() {
        val repo = repositoryWithInstalledPack()
        val antipode = GeoPoint(-12.6, -99.8)                 // other side of the world: needs the unbounded scan
        val type = blocking { repo.pois() }.first().type
        val expectedCount = minOf(3, raw.count("poi WHERE type = '${type.name}'"))

        val actual = blocking { repo.nearestPois(antipode, type, 3) }

        assertEquals(expectedCount, actual.size)
        assertTrue(actual.all { it.second > 1_000_000 })
    }

    @Test
    fun nearestPois_withNonPositiveLimit_isEmpty() {
        val repo = repositoryWithInstalledPack()
        assertTrue(blocking { repo.nearestPois(GeoPoint(12.62, 80.19), PoiType.SHELTER, 0) }.isEmpty())
        assertTrue(blocking { repo.nearestPois(GeoPoint(12.62, 80.19), PoiType.SHELTER, -2) }.isEmpty())
    }

    // ------------------------------------------------------------------ risk zones

    @Test
    fun riskZones_areOuterRingsOfEveryParsableRow() {
        val repo = repositoryWithInstalledPack()
        val rows = raw.rows("SELECT id, level, geojson FROM risk_zone ORDER BY id") { Triple(it.getString(0), it.getString(1), it.getString(2)) }
        val parsable = rows.filter { (_, _, geojson) -> runCatching { GeoJsonPolygons.outerRings(geojson) }.getOrNull()?.isNotEmpty() == true }
        assumeTrue("pack has no risk zones", rows.isNotEmpty())

        val zones = blocking { repo.riskZones() }

        assertEquals(parsable.map { it.first }, zones.map { it.id })
        zones.forEach { zone ->
            assertEquals(parsable.first { it.first == zone.id }.second, zone.level.name)
            assertTrue(zone.polygons.isNotEmpty())
            zone.polygons.forEach { ring ->
                assertTrue(ring.size >= 3)
                assertTrue(ring.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 })
            }
        }
    }

    @Test
    fun riskZones_synthetic_dropsHolesAndBrokenRows() {
        assumeTrue("exact zone shapes are only known for the generated pack", !TestPacks.sampleIsPresent)
        val repo = repositoryWithInstalledPack()

        val zones = blocking { repo.riskZones() }

        assertEquals(listOf("z_high", "z_medium"), zones.map { it.id })                 // z_bad skipped
        assertEquals(1, zones[0].polygons.size)                                           // hole ignored
        assertEquals(GeoPoint(12.61, 80.19), zones[0].polygons[0][0])                     // [lon, lat] → (lat, lon)
        assertNull(zones[1].name)
        assertEquals(2, zones[1].polygons.size)                                           // MultiPolygon
    }

    // ------------------------------------------------------------------ alert text, phrases, embassy, radio

    @Test
    fun alertTemplate_usesRequestedLanguageThenEnglish() {
        val repo = repositoryWithInstalledPack()
        val rows = raw.rows("SELECT code, lang, severity, title, body FROM alert_template") { listOf(it.getString(0), it.getString(1), it.getInt(2), it.getString(3), it.getString(4)) }
        assumeTrue("pack has no alert templates", rows.isNotEmpty())

        rows.forEach { (code, lang, severity, title, body) ->
            val t = blocking { repo.alertTemplate(code as String, lang as String) }
            assertNotNull(t)
            assertEquals(listOf(code, lang, severity, title, body), listOf(t!!.code, t.lang, t.severity, t.title, t.body))
        }
        val englishCode = rows.first { it[1] == "en" }[0] as String
        val fallback = blocking { repo.alertTemplate(englishCode, "xx") }
        assertEquals("en", fallback?.lang)
        assertNull(blocking { repo.alertTemplate("NO_SUCH_CODE", "en") })
    }

    @Test
    fun alertTemplates_oneEntryPerCodeWithEnglishFallback() {
        val repo = repositoryWithInstalledPack()
        val codes = raw.rows("SELECT DISTINCT code FROM alert_template ORDER BY code") { it.getString(0) }
        assumeTrue("pack has no alert templates", codes.isNotEmpty())
        val languages = raw.rows("SELECT DISTINCT lang FROM alert_template") { it.getString(0) }

        for (lang in languages + "xx") {
            val list = blocking { repo.alertTemplates(lang) }
            assertEquals("lang=$lang", codes.filter { code -> raw.count("alert_template WHERE code = '$code' AND lang IN ('$lang','en')") > 0 }, list.map { it.code })
            list.forEach { t ->
                val hasRequested = raw.count("alert_template WHERE code = '${t.code}' AND lang = '$lang'") > 0
                assertEquals(if (hasRequested) lang else "en", t.lang)
            }
        }
    }

    @Test
    fun alertKeywords_phrases_embassy_radios_matchThePack() {
        val repo = repositoryWithInstalledPack()

        val keywords = blocking { repo.alertKeywords() }
        assertEquals(raw.count("alert_keyword"), keywords.size)
        assertEquals(raw.rows("SELECT DISTINCT keyword FROM alert_keyword") { it.getString(0) }.toSet(), keywords.map { it.keyword }.toSet())

        val phraseLangs = raw.rows("SELECT DISTINCT lang FROM phrase") { it.getString(0) }
        for (lang in phraseLangs + "xx") {
            val phrases = blocking { repo.phrases(lang) }
            val expectedIds = raw.rows("SELECT DISTINCT id FROM phrase WHERE lang IN (?, 'en')", lang) { it.getString(0) }
            assertEquals("lang=$lang", expectedIds.toSet(), phrases.map { it.id }.toSet())
            assertEquals("ids must be unique", phrases.size, phrases.map { it.id }.toSet().size)
            phrases.forEach { p ->
                val hasRequested = raw.count("phrase WHERE id = '${p.id}' AND lang = '$lang'") > 0
                assertEquals(if (hasRequested) lang else "en", p.lang)
            }
        }

        val embassies = raw.rows("SELECT country_code, name, phone, address, lat, lon, url FROM embassy") { c ->
            listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), if (c.isNull(4)) null else c.getDouble(4), if (c.isNull(5)) null else c.getDouble(5), c.getString(6))
        }
        embassies.forEach { row ->
            val code = row[0] as String
            for (query in listOf(code, code.lowercase())) {
                val e = blocking { repo.embassy(query) }
                assertNotNull("embassy $query", e)
                assertEquals(row[1], e!!.name); assertEquals(row[2], e.phone); assertEquals(row[3], e.address); assertEquals(row[6], e.url)
                assertEquals(if (row[4] != null && row[5] != null) GeoPoint(row[4] as Double, row[5] as Double) else null, e.point)
            }
        }
        assertNull(blocking { repo.embassy("ZZ") })

        val radios = blocking { repo.radios() }
        assertEquals(raw.rows("SELECT name, frequency, lang FROM radio ORDER BY rowid") { listOf(it.getString(0), it.getString(1), it.getString(2)) },
            radios.map { listOf(it.name, it.frequency, it.lang) })
    }

    @Test
    fun phrases_synthetic_fallBackToEnglishPerPhrase() {
        assumeTrue("exact phrases are only known for the generated pack", !TestPacks.sampleIsPresent)
        val repo = repositoryWithInstalledPack()

        val tamil = blocking { repo.phrases("ta") }.associateBy { it.id }

        assertEquals("ta", tamil.getValue("tourist_need_help").lang)
        assertEquals("ta", tamil.getValue("yes").lang)
        assertEquals("en", tamil.getValue("thank_you").lang)       // no Tamil text, English shown instead
    }

    // ------------------------------------------------------------------ shelter status

    @Test
    fun updateShelterStatus_isMergedIntoPoisAndNearestPois() {
        val repo = repositoryWithInstalledPack()
        val shelter = blocking { repo.pois(setOf(PoiType.SHELTER, PoiType.CANDIDATE_SHELTER)) }.firstOrNull()
        assumeTrue("pack has no shelters", shelter != null)
        val id = shelter!!.id

        blocking { repo.updateShelterStatus(id, ShelterStatus.FULL, 1_000) }

        assertEquals(ShelterStatus.FULL, blocking { repo.pois() }.first { it.id == id }.status)
        assertEquals(ShelterStatus.FULL, blocking { repo.pois(setOf(shelter.type)) }.first { it.id == id }.status)
        val nearest = blocking { repo.nearestPois(shelter.point, shelter.type, 1) }.single()
        assertEquals(id, nearest.first.id)
        assertEquals(ShelterStatus.FULL, nearest.first.status)
        // everything else stays UNKNOWN
        assertTrue(blocking { repo.pois() }.filter { it.id != id }.all { it.status == ShelterStatus.UNKNOWN })
    }

    @Test
    fun updateShelterStatus_keepsTheNewestMessage() {
        val repo = repositoryWithInstalledPack()
        val id = blocking { repo.pois() }.first().id

        blocking {
            repo.updateShelterStatus(id, ShelterStatus.OPEN, 2_000)
            repo.updateShelterStatus(id, ShelterStatus.CLOSED, 1_000)       // older: ignored
        }
        assertEquals(ShelterStatus.OPEN, blocking { repo.pois() }.first { it.id == id }.status)

        blocking { repo.updateShelterStatus(id, ShelterStatus.FULL, 3_000) }      // newer: wins
        assertEquals(ShelterStatus.FULL, blocking { repo.pois() }.first { it.id == id }.status)
    }

    @Test
    fun shelterStatus_forUnknownShelterIsHarmless() {
        val repo = repositoryWithInstalledPack()
        blocking { repo.updateShelterStatus("not_in_pack", ShelterStatus.OPEN, 5) }
        assertTrue(blocking { repo.pois() }.all { it.status == ShelterStatus.UNKNOWN })
    }

    // ------------------------------------------------------------------ routing graph accessors

    @Test
    fun graphAccessors_returnNodesAndEdges() {
        val repo = repositoryWithInstalledPack()
        val nodes = blocking { repo.graphNodes() }
        val edges = blocking { repo.graphEdges() }
        assertEquals(raw.count("node"), nodes.size)
        assertEquals(raw.count("edge"), edges.size)
        assertTrue(nodes.isNotEmpty() && edges.isNotEmpty())
        val ids = nodes.map { it.id }.toSet()
        assertTrue("edges must reference known nodes", edges.take(200).all { it.fromId in ids && it.toId in ids })
        assertTrue(edges.all { it.lengthM >= 0 && it.riskCost in 0.0..5.0 })
    }

    @Test
    fun offlineStyleUrl_hasLightAndDarkVariants() {
        val repo = newRepository()
        assertEquals("https://tiles.openfreemap.org/styles/liberty", repo.offlineStyleUrl(dark = false))
        assertEquals("https://tiles.openfreemap.org/styles/dark", repo.offlineStyleUrl(dark = true))
    }
}
