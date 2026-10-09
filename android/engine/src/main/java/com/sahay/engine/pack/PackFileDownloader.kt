package com.sahay.engine.pack

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.ResponseBody
import java.io.File
import java.security.MessageDigest

/** A download step failed. [reason] is a short sentence for the user; [retryable] tells the UI to offer "Try again". */
internal class PackDownloadException(val reason: String, val retryable: Boolean, cause: Throwable? = null) :
    Exception(reason, cause)

/** Streams a response to disk while hashing it, then checks size and SHA-256 against the manifest. */
internal object PackFileDownloader {
    private const val BUFFER_BYTES = 64 * 1024
    private const val PROGRESS_STEP = 0.01f

    /**
     * Writes [body] to [target] (overwriting it) and verifies it. [onProgress] gets 0..1, roughly every 1 %.
     * Progress is measured against [expectedBytes] because servers may omit Content-Length.
     * On any failure [target] is deleted.
     * @throws PackDownloadException if the content does not match [expectedBytes]/[expectedSha256].
     */
    suspend fun saveVerified(
        body: ResponseBody,
        target: File,
        expectedBytes: Long,
        expectedSha256: String,
        onProgress: (Float) -> Unit,
    ) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastReported = 0f
            val buffer = ByteArray(BUFFER_BYTES)
            body.byteStream().use { input ->
                target.outputStream().buffered().use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > expectedBytes) throw corrupt("received more than $expectedBytes bytes")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        val fraction = written.toFloat() / expectedBytes
                        if (fraction - lastReported >= PROGRESS_STEP) {
                            lastReported = fraction
                            onProgress(fraction)
                        }
                    }
                }
            }
            if (written != expectedBytes) throw corrupt("expected $expectedBytes bytes, got $written")
            val actual = digest.digest().toHex()
            if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) throw corrupt("checksum mismatch")
            onProgress(1f)
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    private fun corrupt(detail: String) = PackDownloadException("The download was damaged. Please try again.", true, IllegalStateException(detail))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
