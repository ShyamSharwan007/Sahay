package com.sahay.engine.pack

/**
 * OpenFreeMap styles used for the offline map. The same URLs must be handed to the map view, because
 * MapLibre serves a style from its offline cache only when the URL matches the downloaded region.
 */
internal object MapStyles {
    const val LIGHT_URL = "https://tiles.openfreemap.org/styles/liberty"
    const val DARK_URL = "https://tiles.openfreemap.org/styles/dark"

    fun url(dark: Boolean): String = if (dark) DARK_URL else LIGHT_URL
}

/** What to download for one pack's map. [downloadId] tags the regions so a failed attempt can be undone. */
internal data class OfflineMapRequest(
    val downloadId: String,
    val regionId: String,
    val packVersion: String,
    /** `[minLon, minLat, maxLon, maxLat]` */
    val bbox: List<Double>,
    val styleUrls: List<String> = listOf(MapStyles.LIGHT_URL, MapStyles.DARK_URL),
    val minZoom: Double = MIN_ZOOM,
    val maxZoom: Double = MAX_ZOOM,
) {
    companion object {
        const val MIN_ZOOM = 12.0
        const val MAX_ZOOM = 16.0
    }
}

/** Raised when the map could not be downloaded; [message] is safe to log, never shown to the user as is. */
internal class OfflineMapException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The offline map side of a pack. A seam so the repository can be tested without MapLibre. */
internal interface OfflineMapStore {
    /**
     * Downloads every style of [request], reporting overall progress 0..1 (never goes backwards).
     * On failure or cancellation the regions created so far are deleted before this throws.
     * @throws OfflineMapException on failure or when the download stalls.
     */
    suspend fun download(request: OfflineMapRequest, onProgress: (Float) -> Unit)

    /** Deletes the regions of every other download, i.e. the previous pack's map. */
    suspend fun keepOnly(downloadId: String)

    /** Deletes the regions of one download (cleanup after a later pack step failed). */
    suspend fun discard(downloadId: String)

    /** Deletes all Sahay offline regions. */
    suspend fun deleteAll()
}
