package com.sahay.engine.pack

import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.StatFs
import android.util.Log
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trip-pack repository backed by the backend API, a pack file on disk and an offline MapLibre region.
 *
 * Only one pack is active at a time. A new pack replaces it atomically after it has been fully downloaded
 * and verified; any failure leaves the previous pack untouched.
 */
@Singleton
class RealPackRepository internal constructor(
    private val storage: PackStorage,
    private val network: PackNetwork,
    private val shelterStatus: ShelterStatusStore,
    private val offlineMaps: OfflineMapStore,
    private val freeSpaceBytes: () -> Long,
    private val scope: CoroutineScope,
) : PackRepository {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        storage = PackStorage(context.filesDir),
        network = PackNetwork(),
        shelterStatus = ShelterStatusStore(context),
        offlineMaps = MapLibreOfflineMapStore(context),
        freeSpaceBytes = { StatFs(context.filesDir.path).availableBytes },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val activeState = MutableStateFlow<PackInfo?>(null)
    override val activePack: StateFlow<PackInfo?> = activeState.asStateFlow()

    /** The open pack file for [activePack]; swapped together with it, only while holding [stateLock]. */
    @Volatile private var queries: PackQueries? = null
    private val stateLock = Mutex()
    private val downloadLock = Mutex()

    // Must stay the last property: it starts running immediately and reads the fields above.
    private val restoreJob: Job = scope.launch { stateLock.withLock { restoreActivePack() } }

    // ================================================================== regions

    override suspend fun regions(): List<Region> = withContext(Dispatchers.IO) {
        try {
            withRetry { network.api.regions() }
                .mapNotNull(RegionDto::toRegionOrNull)
                .ifEmpty { BuiltInRegions.all }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Using built-in regions: ${e.message}")
            BuiltInRegions.all
        }
    }

    // ================================================================== download

    override fun download(regionId: String, start: LocalDate, end: LocalDate): Flow<PackDownloadState> =
        channelFlow {
            if (!downloadLock.tryLock()) {
                send(PackDownloadState.Failed("A download is already running.", true))
                return@channelFlow
            }
            try {
                runDownload(regionId, start, end)
            } finally {
                downloadLock.unlock()
            }
        }.flowOn(Dispatchers.IO)

    /** Steps: MANIFEST → DATA → MAP → ASSETS → VERIFY → switch. Cleans up and reports [PackDownloadState.Failed] on error. */
    private suspend fun ProducerScope<PackDownloadState>.runDownload(regionId: String, start: LocalDate, end: LocalDate) {
        restoreJob.join()
        val attempt = DownloadAttempt(downloadId = UUID.randomUUID().toString())
        try {
            val info = downloadAndActivate(attempt, regionId, start, end)
            send(PackDownloadState.Done(info))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { cleanUp(attempt) }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { cleanUp(attempt) }
            val failure = e.toDownloadFailure()
            Log.w(TAG, "Pack download failed: ${e.message}", e)
            send(PackDownloadState.Failed(failure.reason, failure.retryable))
        }
    }

    private suspend fun ProducerScope<PackDownloadState>.downloadAndActivate(
        attempt: DownloadAttempt,
        regionId: String,
        start: LocalDate,
        end: LocalDate,
    ): PackInfo {
        if (!PackStorage.isSafeSegment(regionId)) throw PackDownloadException("This region is not available.", false)
        if (end.isBefore(start)) throw PackDownloadException("The trip end date is before the start date.", false)

        // MANIFEST: fetch, validate, make sure there is room, save.
        send(PackDownloadState.Downloading(STEP_MANIFEST, 0f))
        val manifestText = fetchManifest(regionId, start, end)
        val manifest = decodeManifest(manifestText, regionId)
        if (freeSpaceBytes() < 2 * manifest.sqliteBytes + FREE_SPACE_MARGIN_BYTES) {
            throw PackDownloadException("Not enough storage", false)
        }
        val dir = prepareVersionDir(attempt, manifest)
        val manifestTarget = if (attempt.reusesActiveFolder) storage.pendingManifestFile(dir) else storage.manifestFile(dir)
        storage.writeAtomically(manifestTarget, manifestText.toByteArray())
        send(PackDownloadState.Downloading(STEP_MANIFEST, 1f))

        // DATA: pack.sqlite.part → verified → pack.sqlite
        send(PackDownloadState.Downloading(STEP_DATA, 0f))
        val part = storage.partFile(dir)
        withRetry { network.api.download(manifest.sqliteUrl) }.use { body ->
            PackFileDownloader.saveVerified(body, part, manifest.sqliteBytes, manifest.sqliteSha256) { progress ->
                trySend(PackDownloadState.Downloading(STEP_DATA, progress))
            }
        }
        storage.promote(part, storage.sqliteFile(dir))

        // MAP: offline region for the bbox (both styles)
        send(PackDownloadState.Downloading(STEP_MAP, 0f))
        attempt.mapStarted = true
        offlineMaps.download(OfflineMapRequest(attempt.downloadId, manifest.regionId, manifest.packVersion, manifest.bbox)) { progress ->
            trySend(PackDownloadState.Downloading(STEP_MAP, progress))
        }

        // ASSETS: nothing to fetch with the OpenFreeMap approach (fonts and sprites live in the offline region).
        send(PackDownloadState.Downloading(STEP_ASSETS, 0f))
        send(PackDownloadState.Downloading(STEP_ASSETS, 1f))

        // VERIFY: only a pack that opens and has data may become active.
        send(PackDownloadState.Downloading(STEP_VERIFY, 0f))
        val verified = openAndVerify(storage.sqliteFile(dir), manifest.regionId)
        attempt.openedPack = verified
        send(PackDownloadState.Downloading(STEP_VERIFY, 1f))

        // Switch: write the pointer atomically, then flip the in-memory state.
        val pointer = ActivePointer(manifest.regionId, manifest.packVersion, start.toString(), end.toString(), System.currentTimeMillis() / 1000)
        val info = manifest.toPackInfo(pointer, storage.sqliteFile(dir).length())
        stateLock.withLock {
            if (attempt.reusesActiveFolder) storage.promote(storage.pendingManifestFile(dir), storage.manifestFile(dir))
            storage.writeActive(pointer)
            attempt.openedPack = null          // ownership moves to the repository
            install(info, verified)
            attempt.switched = true
        }
        removeSupersededPacks(dir, attempt.downloadId)
        return info
    }

    private suspend fun fetchManifest(regionId: String, start: LocalDate, end: LocalDate): String =
        withRetry { network.api.manifest(regionId, start.toString(), end.toString()) }.use { it.string() }

    private fun decodeManifest(text: String, regionId: String): ManifestDto {
        val manifest = try {
            network.json.decodeFromString<ManifestDto>(text)
        } catch (e: SerializationException) {
            throw PackDownloadException("The pack information from the server was not valid.", false, e)
        }
        manifest.validationError(regionId)?.let { throw PackDownloadException("The pack information from the server was not valid.", false, IllegalStateException(it)) }
        return manifest
    }

    /**
     * Folder for this version. A stale folder of a version that is not active (an earlier crashed attempt) is
     * wiped. If the version IS active we keep its files and stage the new manifest next to it, so a failure
     * cannot change the pack that is in use.
     */
    private fun prepareVersionDir(attempt: DownloadAttempt, manifest: ManifestDto): File {
        val dir = storage.versionDir(manifest.regionId, manifest.packVersion)
        val active = activeState.value
        attempt.reusesActiveFolder = active?.regionId == manifest.regionId && active.packVersion == manifest.packVersion
        if (!attempt.reusesActiveFolder) {
            dir.deleteRecursively()
            attempt.createdFolder = dir
        }
        if (!dir.isDirectory && !dir.mkdirs()) throw PackDownloadException("Couldn't save the pack on this phone.", false)
        return dir
    }

    private fun openAndVerify(sqlite: File, expectedRegionId: String): PackQueries {
        val pack = try {
            PackQueries.open(sqlite)
        } catch (e: SQLiteException) {
            throw PackDownloadException("The downloaded pack could not be opened.", true, e)
        }
        try {
            val problem = when {
                pack.meta("region_id") != expectedRegionId -> "meta.region_id does not match '$expectedRegionId'"
                pack.rowCount(PackQueries.Table.POI) == 0L -> "no places in pack"
                pack.rowCount(PackQueries.Table.NODE) == 0L -> "no road nodes in pack"
                pack.rowCount(PackQueries.Table.EDGE) == 0L -> "no roads in pack"
                else -> null
            }
            if (problem != null) throw PackDownloadException("The downloaded pack is incomplete. Please try again.", true, IllegalStateException(problem))
            return pack
        } catch (e: Exception) {
            pack.close()
            if (e is SQLiteException) throw PackDownloadException("The downloaded pack could not be read.", true, e)
            throw e
        }
    }

    /** After a successful switch: drop older packs and their map regions. Failures here are only logged. */
    private suspend fun removeSupersededPacks(activeDir: File, downloadId: String) {
        try {
            storage.pruneExcept(activeDir)
        } catch (e: IOException) {
            Log.w(TAG, "Could not delete old pack files", e)
        }
        bestEffort("delete old offline map") { offlineMaps.keepOnly(downloadId) }
    }

    /** Undo a failed or cancelled attempt: partial files, new folder, half-installed map, open handle. */
    private suspend fun cleanUp(attempt: DownloadAttempt) {
        if (attempt.switched) return
        attempt.openedPack?.close()
        attempt.createdFolder?.let { it.deleteRecursively() }
        if (attempt.reusesActiveFolder) {
            val dir = activeState.value?.let { storage.versionDir(it.regionId, it.packVersion) }
            dir?.let {
                storage.partFile(it).delete()
                storage.pendingManifestFile(it).delete()
            }
        }
        if (attempt.mapStarted) bestEffort("discard offline map") { offlineMaps.discard(attempt.downloadId) }
    }

    /** State of one [download] run, shared between the steps and the clean-up. */
    private class DownloadAttempt(val downloadId: String) {
        var reusesActiveFolder = false
        var createdFolder: File? = null
        var mapStarted = false
        var openedPack: PackQueries? = null
        var switched = false
    }

    // ================================================================== active pack

    override suspend fun deletePack() {
        withContext(Dispatchers.IO) {
            restoreJob.join()
            stateLock.withLock {
                val old = queries
                queries = null
                activeState.value = null
                old?.close()
                storage.deleteAll()
            }
            bestEffort("clear shelter status") { shelterStatus.clear() }
            bestEffort("delete offline map") { offlineMaps.deleteAll() }
        }
    }

    /** Loads the pack named by active.json; an unreadable pack simply means "no active pack". */
    private fun restoreActivePack() {
        val pointer = storage.readActive() ?: return
        var opened: PackQueries? = null
        try {
            val dir = storage.versionDir(pointer.regionId, pointer.packVersion)
            val manifest = network.json.decodeFromString<ManifestDto>(storage.manifestFile(dir).readText())
            val sqlite = storage.sqliteFile(dir)
            opened = PackQueries.open(sqlite)
            check(opened.meta("region_id") == pointer.regionId) { "pack file belongs to another region" }
            install(manifest.toPackInfo(pointer, sqlite.length()), opened)
            opened = null
        } catch (e: Exception) {          // missing/corrupt files, bad JSON or dates, SQLite errors
            Log.w(TAG, "Could not restore the active pack", e)
        } finally {
            opened?.close()
        }
    }

    /** Publishes a new active pack and closes the previous file. Caller holds [stateLock] (or is restoring). */
    private fun install(info: PackInfo, pack: PackQueries) {
        val old = queries
        queries = pack
        activeState.value = info
        old?.close()
    }

    // ================================================================== queries

    override suspend fun pois(types: Set<PoiType>): List<Poi> =
        withShelterStatus(withPack(emptyList()) { pack -> pack.pois().filter { it.type in types } })

    override suspend fun nearestPois(from: GeoPoint, type: PoiType, limit: Int): List<Pair<Poi, Double>> {
        val nearest = withPack(emptyList()) { it.nearestPois(from, type, limit) }
        val merged = withShelterStatus(nearest.map { it.first }).map { it.id to it.status }.toMap()
        return nearest.map { (poi, distance) -> poi.copy(status = merged[poi.id] ?: poi.status) to distance }
    }

    override suspend fun riskZones(): List<RiskZone> = withPack(emptyList()) { it.riskZones() }

    override suspend fun alertTemplate(code: String, lang: String): AlertTemplate? =
        withPack(null) { it.alertTemplate(code, lang) }

    override suspend fun alertTemplates(lang: String): List<AlertTemplate> =
        withPack(emptyList()) { it.alertTemplates(lang) }

    override suspend fun alertKeywords(): List<AlertKeyword> = withPack(emptyList()) { it.alertKeywords() }

    override suspend fun phrases(lang: String): List<Phrase> = withPack(emptyList()) { it.phrases(lang) }

    override suspend fun embassy(countryCode: String): Embassy? = withPack(null) { it.embassy(countryCode) }

    override suspend fun radios(): List<RadioStation> = withPack(emptyList()) { it.radios() }

    override suspend fun updateShelterStatus(shelterId: String, status: ShelterStatus, updatedAtEpochSec: Long) {
        shelterStatus.update(shelterId, status, updatedAtEpochSec)
    }

    // ================================================================== engine-internal (routing, map)

    /** Road graph nodes of the active pack; empty when there is no pack. */
    internal suspend fun graphNodes(): List<GraphNode> = withPack(emptyList()) { it.graphNodes() }

    /** Road graph edges of the active pack; empty when there is no pack. */
    internal suspend fun graphEdges(): List<GraphEdge> = withPack(emptyList()) { it.graphEdges() }

    /**
     * Style URL for the map view. It is the same URL the offline region was downloaded with, so MapLibre
     * serves it (tiles, glyphs, sprites) from the offline cache when there is no internet.
     */
    internal fun offlineStyleUrl(dark: Boolean): String = MapStyles.url(dark)

    // ================================================================== helpers

    /** Runs [block] against the active pack on IO. Returns [fallback] when there is no pack or it cannot be read. */
    private suspend fun <T> withPack(fallback: T, block: (PackQueries) -> T): T = withContext(Dispatchers.IO) {
        restoreJob.join()
        val pack = queries ?: return@withContext fallback
        try {
            block(pack)
        } catch (e: SQLiteException) {
            Log.w(TAG, "Pack query failed", e); fallback
        } catch (e: IllegalStateException) {          // pack was closed by deletePack()/a new pack while we were reading
            Log.w(TAG, "Pack query aborted", e); fallback
        }
    }

    /** Merges the locally stored shelter status into [pois]. A broken status store never hides the POIs. */
    private suspend fun withShelterStatus(pois: List<Poi>): List<Poi> {
        if (pois.isEmpty()) return pois
        val statuses = try {
            shelterStatus.all()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Shelter status unavailable", e); return pois
        }
        return pois.map { poi -> statuses[poi.id]?.let { poi.copy(status = it) } ?: poi }
    }

    /** One retry after a short backoff for connection errors and 5xx answers (docs/CONTRACTS.md §9). */
    private suspend fun <T> withRetry(call: suspend () -> T): T = try {
        call()
    } catch (e: Exception) {
        if (e is CancellationException || !e.isTransient()) throw e
        delay(RETRY_BACKOFF_MS)
        call()
    }

    private fun Throwable.isTransient(): Boolean = when (this) {
        is HttpException -> code() in 500..599
        is IOException -> true
        else -> false
    }

    /** Maps any error to a short, user-safe message (never a raw exception text). */
    private fun Exception.toDownloadFailure(): PackDownloadException = when (this) {
        is PackDownloadException -> this
        is HttpException -> when (code()) {
            404 -> PackDownloadException("This pack is not available yet.", false, this)
            408, 429, in 500..599 -> PackDownloadException("The server is busy. Please try again soon.", true, this)
            else -> PackDownloadException("The pack could not be downloaded.", false, this)
        }
        is SocketTimeoutException -> PackDownloadException("The connection timed out. Check your internet and try again.", true, this)
        is IOException -> PackDownloadException("No internet connection. Connect and try again.", true, this)
        is OfflineMapException -> PackDownloadException("The map could not be downloaded. Check your internet and try again.", true, this)
        is SerializationException, is DateTimeParseException ->
            PackDownloadException("The pack information from the server was not valid.", false, this)
        else -> PackDownloadException("Something went wrong. Please try again.", true, this)
    }

    private suspend fun bestEffort(what: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not $what", e)
        }
    }

    private object BuiltInRegions {
        /** Same regions and boxes as docs/CONTRACTS.md §0; used when the server cannot be reached. */
        val all = listOf(
            Region("mahabalipuram", "Mahabalipuram", listOf(80.16, 12.59, 80.21, 12.65)),
            Region("iiitdm-kancheepuram", "IIITDM Kancheepuram", listOf(80.13, 12.82, 80.18, 12.86)),
        )
    }

    private companion object {
        const val TAG = "RealPackRepository"
        const val STEP_MANIFEST = "MANIFEST"
        const val STEP_DATA = "DATA"
        const val STEP_MAP = "MAP"
        const val STEP_ASSETS = "ASSETS"
        const val STEP_VERIFY = "VERIFY"
        const val FREE_SPACE_MARGIN_BYTES = 60L * 1024 * 1024
        const val RETRY_BACKOFF_MS = 700L
    }
}
