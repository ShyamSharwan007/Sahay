package com.sahay.app.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sahay.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

const val LATEST_RELEASE_URL = "https://api.github.com/repos/ShyamSharwan007/Sahay/releases/latest"
const val APK_DOWNLOAD_URL = "https://github.com/ShyamSharwan007/Sahay/releases/latest/download/sahay.apk"

private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
private const val TIMEOUT_SECONDS = 10L

/** True when release tag [tag] ("v1.2") is a higher version than [current] ("1.1"). Unreadable tags are never newer. */
fun isNewerVersion(tag: String, current: String): Boolean {
    val latest = versionParts(tag) ?: return false
    val installed = versionParts(current) ?: return false
    for (i in 0 until maxOf(latest.size, installed.size)) {
        val a = latest.getOrElse(i) { 0 }
        val b = installed.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

/** "v1.2.3-beta" -> [1, 2, 3]; null when there is no number at all. */
private fun versionParts(version: String): List<Int>? {
    val core = version.trim().removePrefix("v").removePrefix("V").takeWhile { it.isDigit() || it == '.' }
    val parts = core.split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
    return parts.ifEmpty { null }
}

/**
 * Looks for a newer GitHub release, at most once every 6 hours and only while online.
 * Every failure (offline, timeout, bad JSON) is swallowed: the app simply shows no notice.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Newest release tag found, if it is newer than this app. */
    private val newerTag = MutableStateFlow<String?>(null)

    /** True while a newer version exists that the user has not dismissed. */
    val updateAvailable: Flow<Boolean> = combine(newerTag, dataStore.data.map { it[DISMISSED_TAG] }) { tag, dismissed ->
        tag != null && tag != dismissed
    }

    suspend fun dismiss() {
        val tag = newerTag.value ?: return
        dataStore.edit { it[DISMISSED_TAG] = tag }
    }

    /** Runs the check when 6 h have passed since the last one. Never throws. */
    suspend fun checkIfDue() {
        try {
            val now = clock.millis()
            val last = dataStore.data.first()[LAST_CHECK] ?: 0L
            if (now - last < CHECK_INTERVAL_MS || !isOnline()) return
            dataStore.edit { it[LAST_CHECK] = now }
            val tag = withContext(Dispatchers.IO) { fetchLatestTag() } ?: return
            if (isNewerVersion(tag, BuildConfig.VERSION_NAME)) newerTag.value = tag
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or a broken answer: no notice, no crash.
        }
    }

    private fun fetchLatestTag(): String? {
        val request = Request.Builder().url(LATEST_RELEASE_URL).header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body.string()
            return Json.parseToJsonElement(body).jsonObject["tag_name"]?.jsonPrimitive?.content
        }
    }

    private fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        val LAST_CHECK = longPreferencesKey("update_last_check_ms")
        val DISMISSED_TAG = stringPreferencesKey("update_dismissed_tag")
    }
}
