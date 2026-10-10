package com.sahay.app.me

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sahay.core.contracts.SahayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val photoCache = LruCache<String, ImageBitmap>(4)

/** Decorative: the name is always shown next to it. Offline or on any error, the person's initial is shown instead. */
@Composable
fun ProfileAvatar(photoUrl: String?, name: String, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val photo by produceState<ImageBitmap?>(initialValue = photoUrl?.let(photoCache::get), photoUrl) {
        if (value == null && photoUrl != null) {
            value = withContext(Dispatchers.IO) { downloadPhoto(photoUrl) }
        }
    }
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val image = photo
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                name.trim().firstOrNull()?.uppercase(LocalConfiguration.current.locales[0]).orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** Plain HTTPS download with the standard 10 s timeouts; null on any problem. */
private fun downloadPhoto(url: String): ImageBitmap? {
    if (!url.startsWith("https://")) return null
    val bitmap = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = SahayConfig.NETWORK_TIMEOUT_MS.toInt()
        connection.readTimeout = SahayConfig.NETWORK_TIMEOUT_MS.toInt()
        try {
            connection.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }
    bitmap?.let { photoCache.put(url, it) }
    return bitmap
}
