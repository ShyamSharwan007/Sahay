package com.sahay.comms.reports

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the photo of a queued report as a file (a database row cannot hold a multi-megabyte blob).
 * Every call is best effort: a photo that cannot be stored just means the report goes without it.
 */
@Singleton
class ReportPhotoStore(private val dir: File) {

    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "report_photos"))

    /** Returns the stored file's path, or null if there is no photo, it is too big, or the disk write failed. */
    fun save(localId: String, jpeg: ByteArray?): String? {
        if (jpeg == null || jpeg.isEmpty() || jpeg.size > MAX_BYTES) return null
        return try {
            dir.mkdirs()
            File(dir, "$localId.jpg").also { it.writeBytes(jpeg) }.path
        } catch (e: IOException) {
            Log.w(TAG, "Could not keep the report photo: ${e.message}")
            null
        }
    }

    fun read(path: String?): ByteArray? {
        if (path == null) return null
        return try {
            File(path).takeIf { it.isFile }?.readBytes()
        } catch (e: IOException) {
            null
        }
    }

    fun delete(path: String?) {
        if (path != null) runCatching { File(path).delete() }
    }

    companion object {
        /** The server ignores photos for now; keep the upload small on a weak connection. */
        const val MAX_BYTES = 1_000_000
        private const val TAG = "ReportPhotoStore"
    }
}
