package com.sahay.engine.pack

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** `active.json`: which downloaded pack is in use, plus the trip data that is not part of the manifest. */
@Serializable
internal data class ActivePointer(
    val regionId: String,
    val packVersion: String,
    val tripStart: String,             // YYYY-MM-DD
    val tripEnd: String,
    val downloadedAtEpochSec: Long,
)

/**
 * File layout under `<filesDir>/packs`:
 * ```
 * active.json
 * <regionId>/<packVersion>/manifest.json
 * <regionId>/<packVersion>/pack.sqlite        (pack.sqlite.part while downloading)
 * ```
 */
internal class PackStorage(filesDir: File) {
    val root = File(filesDir, "packs")
    private val activeFile = File(root, ACTIVE_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    fun versionDir(regionId: String, packVersion: String): File {
        require(isSafeSegment(regionId) && isSafeSegment(packVersion)) { "Unsafe pack path: $regionId/$packVersion" }
        return File(File(root, regionId), packVersion)
    }

    fun sqliteFile(dir: File) = File(dir, SQLITE_FILE)
    fun partFile(dir: File) = File(dir, "$SQLITE_FILE.part")
    fun manifestFile(dir: File) = File(dir, MANIFEST_FILE)
    fun pendingManifestFile(dir: File) = File(dir, "$MANIFEST_FILE.new")

    /** Null when there is no pointer or it is unreadable (treated as "no active pack"). */
    fun readActive(): ActivePointer? {
        if (!activeFile.isFile) return null
        return try {
            val pointer = json.decodeFromString<ActivePointer>(activeFile.readText())
            pointer.takeIf { isSafeSegment(it.regionId) && isSafeSegment(it.packVersion) }
        } catch (e: SerializationException) {
            Log.w(TAG, "Ignoring corrupt active.json", e); null
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read active.json", e); null
        }
    }

    /** Atomic: readers see either the old or the new pointer, never a half-written file. */
    fun writeActive(pointer: ActivePointer) {
        writeAtomically(activeFile, json.encodeToString(ActivePointer.serializer(), pointer).toByteArray())
    }

    fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        promote(temp, target)
    }

    /** Renames [from] over [to], atomically where the file system allows it. */
    fun promote(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Deletes every pack except the one in [keep] (the new active version), plus empty region folders. */
    fun pruneExcept(keep: File) {
        root.listFiles()?.filter { it.isDirectory }?.forEach { regionDir ->
            regionDir.listFiles()?.filter { it != keep }?.forEach { it.deleteRecursively() }
            if (regionDir.listFiles().isNullOrEmpty()) regionDir.deleteRecursively()
        }
    }

    fun deleteAll() {
        root.deleteRecursively()
    }

    companion object {
        private const val TAG = "PackStorage"
        const val ACTIVE_FILE = "active.json"
        const val SQLITE_FILE = "pack.sqlite"
        const val MANIFEST_FILE = "manifest.json"
        private val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]{1,64}")

        /** Region ids and pack versions become folder names, so they must not contain separators or `..`. */
        fun isSafeSegment(value: String): Boolean = SAFE_SEGMENT.matches(value) && value != "." && value != ".."
    }
}
