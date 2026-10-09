package com.sahay.engine.pack

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [OfflineMapStore] on MapLibre's [OfflineManager]. All MapLibre calls run on the main thread, which is
 * where the SDK delivers its callbacks. Regions carry JSON metadata (app marker, region, version,
 * downloadId) so we only ever touch our own regions.
 */
internal class MapLibreOfflineMapStore(context: Context) : OfflineMapStore {
    private val appContext = context.applicationContext

    override suspend fun download(request: OfflineMapRequest, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            val manager = manager()
            val created = mutableListOf<OfflineRegion>()
            try {
                val styles = request.styleUrls.distinct()
                styles.forEachIndexed { index, styleUrl ->
                    val region = createRegion(manager, request, styleUrl)
                    created += region
                    awaitComplete(region) { fraction -> onProgress((index + fraction) / styles.size) }
                }
                onProgress(1f)
            } catch (t: Throwable) {
                withContext(NonCancellable) { created.forEach { runCatching { delete(it) } } }
                throw t
            }
        }
    }

    override suspend fun keepOnly(downloadId: String) = deleteWhere { it.downloadId != downloadId }

    override suspend fun discard(downloadId: String) = deleteWhere { it.downloadId == downloadId }

    override suspend fun deleteAll() = deleteWhere { true }

    // ------------------------------------------------------------------ regions

    private fun manager(): OfflineManager {
        MapLibre.getInstance(appContext)          // the SDK must be initialised before its file source is used
        return OfflineManager.getInstance(appContext)
    }

    private suspend fun createRegion(manager: OfflineManager, request: OfflineMapRequest, styleUrl: String): OfflineRegion {
        val (minLon, minLat, maxLon, maxLat) = request.bbox
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            LatLngBounds.from(maxLat, maxLon, minLat, minLon),     // north, east, south, west
            request.minZoom,
            request.maxZoom,
            appContext.resources.displayMetrics.density,
        )
        val metadata = RegionTag(request.downloadId, request.regionId, request.packVersion).toBytes()
        return suspendCancellableCoroutine { cont ->
            manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) = cont.resume(offlineRegion)
                override fun onError(error: String) = cont.resumeWithException(OfflineMapException("Cannot create offline region: $error"))
            })
        }
    }

    /**
     * Starts the download and waits until the region is complete. Gives up when no progress event
     * arrives for [STALL_TIMEOUT_MS] (offline, or a style that never finishes).
     */
    private suspend fun awaitComplete(region: OfflineRegion, onFraction: (Float) -> Unit) {
        val events = Channel<ProgressEvent>(Channel.UNLIMITED)
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                events.trySend(ProgressEvent(status.fraction(), status.isComplete))
            }

            // Individual resource errors are usually transient (the SDK retries); a real outage shows up as a stall.
            override fun onError(error: OfflineRegionError) {
                Log.w(TAG, "Offline region error: ${error.reason} ${error.message}")
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                Log.w(TAG, "Tile count limit exceeded: $limit")
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        try {
            var best = 0f
            while (true) {
                val event = withTimeoutOrNull(STALL_TIMEOUT_MS) { events.receive() }
                    ?: throw OfflineMapException("Map download stalled")
                best = maxOf(best, event.fraction)
                onFraction(best)
                if (event.complete) return
            }
        } finally {
            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            region.setObserver(null)
            events.close()
        }
    }

    /** Overall fraction, held below 1 until the SDK says the region is complete (the total can still grow). */
    private fun OfflineRegionStatus.fraction(): Float {
        if (isComplete) return 1f
        if (requiredResourceCount <= 0) return 0f
        return (completedResourceCount.toFloat() / requiredResourceCount).coerceIn(0f, MAX_INCOMPLETE_FRACTION)
    }

    private suspend fun deleteWhere(predicate: (RegionTag) -> Boolean) {
        withContext(Dispatchers.Main.immediate) {
            val manager = manager()
            for (region in listRegions(manager)) {
                val tag = RegionTag.parse(region.metadata) ?: continue          // not ours
                if (predicate(tag)) {
                    try {
                        delete(region)
                    } catch (e: OfflineMapException) {
                        Log.w(TAG, "Could not delete offline region ${region.id}: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun listRegions(manager: OfflineManager): List<OfflineRegion> = suspendCancellableCoroutine { cont ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) = cont.resume(offlineRegions?.toList().orEmpty())
            override fun onError(error: String) = cont.resumeWithException(OfflineMapException("Cannot list offline regions: $error"))
        })
    }

    private suspend fun delete(region: OfflineRegion): Unit = suspendCancellableCoroutine { cont ->
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = cont.resume(Unit)
            override fun onError(error: String) = cont.resumeWithException(OfflineMapException("Cannot delete offline region: $error"))
        })
    }

    private class ProgressEvent(val fraction: Float, val complete: Boolean)

    /** Metadata stored with each region. */
    private data class RegionTag(val downloadId: String, val regionId: String, val packVersion: String) {
        fun toBytes(): ByteArray = buildJsonObject {
            put(APP_KEY, APP_VALUE)
            put("downloadId", downloadId)
            put("regionId", regionId)
            put("packVersion", packVersion)
        }.toString().toByteArray()

        companion object {
            private const val APP_KEY = "app"
            private const val APP_VALUE = "sahay-pack"

            /** Null for regions that were not created by us or have unreadable metadata. */
            fun parse(bytes: ByteArray?): RegionTag? {
                if (bytes == null) return null
                return try {
                    val obj: JsonObject = Json.parseToJsonElement(String(bytes)).jsonObject
                    if (obj[APP_KEY]?.jsonPrimitive?.contentOrNull != APP_VALUE) return null
                    RegionTag(
                        downloadId = obj["downloadId"]?.jsonPrimitive?.contentOrNull ?: return null,
                        regionId = obj["regionId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        packVersion = obj["packVersion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }
    }

    private companion object {
        const val TAG = "MapLibreOfflineMapStore"
        const val STALL_TIMEOUT_MS = 45_000L
        const val MAX_INCOMPLETE_FRACTION = 0.99f
    }
}
