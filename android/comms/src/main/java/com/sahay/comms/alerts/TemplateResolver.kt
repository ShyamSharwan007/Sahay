package com.sahay.comms.alerts

import android.util.Log
import com.sahay.comms.net.CommsApi
import com.sahay.core.contracts.ConnectivityMonitor
import com.sahay.core.contracts.PackRepository
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Alert wording for one template code, in the user's language and in English. */
data class TemplateTexts(val severity: Int, val title: String, val body: String, val titleEn: String, val bodyEn: String)

/**
 * Finds alert template text: the active pack first, then templates cached from `GET /alert-templates`,
 * then (online only) that endpoint itself. Returns null when no source knows the code.
 */
@Singleton
class TemplateResolver(
    private val packRepository: PackRepository,
    private val cache: TemplateCacheDao,
    private val api: CommsApi,
    private val isOnline: () -> Boolean,
    private val clock: Clock,
) {
    @Inject constructor(
        packRepository: PackRepository,
        cache: TemplateCacheDao,
        api: CommsApi,
        connectivity: ConnectivityMonitor,
    ) : this(packRepository, cache, api, { connectivity.state.value.internet }, Clock.systemUTC())

    // Last failed download per language, so a burst of alerts does not wait for the timeout again and again.
    private val fetchFailedAtSec = ConcurrentHashMap<String, Long>()

    suspend fun texts(code: String, lang: String): TemplateTexts? {
        val english = find(code, "en")
        val local = if (lang == "en") english else find(code, lang)
        val primary = local ?: english ?: return null
        val en = english ?: primary
        return TemplateTexts(en.severity, primary.title, primary.body, en.title, en.body)
    }

    private data class Found(val severity: Int, val title: String, val body: String)

    private suspend fun find(code: String, lang: String): Found? {
        packRepository.alertTemplate(code, lang)?.let { return Found(it.severity, it.title, it.body) }
        lookupCache(code, lang)?.let { return it }
        if (!downloadTemplates(lang)) return null
        return lookupCache(code, lang)
    }

    private suspend fun lookupCache(code: String, lang: String): Found? =
        (cache.find(code, lang) ?: cache.find(code, "en"))?.let { Found(it.severity, it.title, it.body) }

    /** True if the cache was refreshed for [lang]. */
    private suspend fun downloadTemplates(lang: String): Boolean {
        val now = clock.instant().epochSecond
        val failedAt = fetchFailedAtSec[lang]
        if (!isOnline() || (failedAt != null && now - failedAt < RETRY_AFTER_FAILURE_SEC)) return false
        return try {
            cache.putAll(api.alertTemplates(lang).map { TemplateEntity(it.code, lang, it.severity, it.title, it.body) })
            fetchFailedAtSec.remove(lang)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not download alert templates ($lang): ${e.message}")
            fetchFailedAtSec[lang] = now
            false
        }
    }

    private companion object {
        const val TAG = "TemplateResolver"
        const val RETRY_AFTER_FAILURE_SEC = 60L
    }
}
