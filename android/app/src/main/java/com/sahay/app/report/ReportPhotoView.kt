package com.sahay.app.report

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sahay.R
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.SahayConfig
import com.sahay.designsystem.components.StatusChip
import com.sahay.designsystem.components.StatusKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val THUMB_MAX_PX = 480
private const val NETWORK_TIMEOUT_MS = 10_000
private val thumbCache = LruCache<String, ImageBitmap>(16)

/** Thumbnail of a report's photo: the local file for own reports, else the server's (approved) photo. Nothing if neither loads. */
@Composable
fun ReportPhotoThumb(report: HazardReport, modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val key = report.photoPath ?: report.photoUrl
    val image by produceState<ImageBitmap?>(initialValue = key?.let(thumbCache::get), key) {
        if (value == null && key != null) {
            value = withContext(Dispatchers.IO) { loadThumb(report.photoPath, report.photoUrl) }?.also { thumbCache.put(key, it) }
        }
    }
    image?.let {
        Image(
            bitmap = it,
            contentDescription = stringResource(R.string.report_photo_description),
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(RoundedCornerShape(12.dp)),
        )
    }
}

/** "Pending review" / "Approved" / "Not approved"; nothing when the report has no photo. */
@Composable
fun PhotoReviewChip(status: String?, modifier: Modifier = Modifier) {
    when (status) {
        "approved" -> StatusChip(StatusKind.Safe, stringResource(R.string.photo_approved), modifier)
        "rejected" -> StatusChip(StatusKind.Danger, stringResource(R.string.photo_rejected), modifier)
        "pending" -> StatusChip(StatusKind.Watch, stringResource(R.string.photo_pending_review), modifier)
    }
}

private fun loadThumb(path: String?, url: String?): ImageBitmap? = try {
    when {
        path != null && File(path).isFile -> decodeSampled { BitmapFactory.decodeFile(path, it) }
        url != null -> download(absoluteUrl(url))?.let { bytes -> decodeSampled { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) } }
        else -> null
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

/** Two passes: read the size, then decode at a power-of-two reduction so a 1280 px photo is not held in full. */
private fun decodeSampled(decode: (BitmapFactory.Options) -> android.graphics.Bitmap?): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_MAX_PX) sample *= 2
    return decode(BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/** The server sends a path like `/api/v1/reports/ID/photo`; it lives on the same host as the API. */
private fun absoluteUrl(photoUrl: String): String {
    if (photoUrl.startsWith("http")) return photoUrl
    val base = URL(SahayConfig.BASE_URL)
    return "${base.protocol}://${base.authority}$photoUrl"
}

private fun download(url: String): ByteArray? {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = NETWORK_TIMEOUT_MS
    connection.readTimeout = NETWORK_TIMEOUT_MS
    return try {
        if (connection.responseCode == HttpURLConnection.HTTP_OK) connection.inputStream.use { it.readBytes() } else null
    } finally {
        connection.disconnect()
    }
}
