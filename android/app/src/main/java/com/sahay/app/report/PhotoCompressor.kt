package com.sahay.app.report

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max

/**
 * Shrinks a photo for upload: long side at most [MAX_SIDE] px, JPEG quality [QUALITY] (about 150–250 KB).
 * Decoding and re-encoding drops every EXIF tag, including the GPS position; only the orientation is applied first.
 * The report already carries its own location, so nothing else about the photo is sent.
 */
object PhotoCompressor {
    const val MAX_SIDE = 1280
    const val QUALITY = 70
    private const val MIN_QUALITY = 40
    private const val QUALITY_STEP = 10

    /** The server accepts up to 1 MB; stay well under it. */
    const val MAX_BYTES = 900_000

    /** Null when the file cannot be read or is not an image. */
    fun compress(resolver: ContentResolver, uri: Uri): ByteArray? = try {
        val orientation = resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            decoded?.let { encode(rotateAndScale(it, orientation)) }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /** Largest power of two that still leaves the long side at or above [MAX_SIDE] (or 1 for small photos). */
    internal fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= MAX_SIDE) sample *= 2
        return sample
    }

    /** Size with the long side limited to [MAX_SIDE], aspect ratio kept; smaller photos are not enlarged. */
    internal fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        val longSide = max(width, height)
        if (longSide <= MAX_SIDE) return width to height
        val factor = MAX_SIDE.toDouble() / longSide
        return max(1, (width * factor).toInt()) to max(1, (height * factor).toInt())
    }

    private fun rotateAndScale(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        val (w, h) = scaledSize(source.width, source.height)
        if (w != source.width) matrix.postScale(w.toFloat() / source.width, h.toFloat() / source.height)
        return if (matrix.isIdentity) source else Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun encode(bitmap: Bitmap): ByteArray? {
        var quality = QUALITY
        while (true) {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
            if (out.size() <= MAX_BYTES || quality <= MIN_QUALITY) return out.toByteArray().takeIf { it.size <= MAX_BYTES }
            quality -= QUALITY_STEP
        }
    }
}
