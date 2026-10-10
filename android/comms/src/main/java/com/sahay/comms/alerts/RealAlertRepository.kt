package com.sahay.comms.alerts

import android.content.Context
import android.util.Log
import com.sahay.comms.R
import com.sahay.comms.net.CommsApi
import com.sahay.comms.net.TranslateDto
import com.sahay.comms.wire.WireCodec
import com.sahay.comms.wire.WireMessage
import com.sahay.comms.wire.WireReader
import com.sahay.core.contracts.AlertRepository
import com.sahay.core.contracts.AlertSource
import com.sahay.core.contracts.ConnectivityMonitor
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.SahayAlert
import com.sahay.core.contracts.SahayConfig
import com.sahay.core.contracts.Verification
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alerts from every channel in one Room table, newest first. Signed wires (internet, SMS from the server) are
 * verified by [WireReader] before they get here; official-looking SMS and pasted text are labelled as such.
 *
 * While the app is visible and online it refreshes every [POLL_INTERVAL_MS] (no WorkManager).
 */
@Singleton
class RealAlertRepository internal constructor(
    private val context: Context,
    private val dao: AlertDao,
    private val api: CommsApi,
    private val packRepository: PackRepository,
    private val profileStore: ProfileStore,
    private val reader: WireReader,
    private val templates: TemplateResolver,
    private val notifier: AlertNotifier,
    private val isOnline: () -> Boolean,
    private val clock: Clock,
    private val scope: CoroutineScope,
    pollWhileVisible: Boolean,
) : AlertRepository {

    @Inject constructor(
        @ApplicationContext context: Context,
        dao: AlertDao,
        api: CommsApi,
        packRepository: PackRepository,
        profileStore: ProfileStore,
        reader: WireReader,
        templates: TemplateResolver,
        notifier: AlertNotifier,
        connectivity: ConnectivityMonitor,
    ) : this(
        context, dao, api, packRepository, profileStore, reader, templates, notifier,
        { connectivity.state.value.internet },
        Clock.systemUTC(),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        pollWhileVisible = true,
    )

    /** Stored alerts the user may see now: this trip's region and dates, not expired (see [AlertScope]). */
    private val visibleRows: Flow<List<AlertEntity>> =
        combine(dao.observeAll(), packRepository.activePack, minuteTicks()) { rows, pack, _ ->
            val alertScope = AlertScope.forPack(pack, clock)
            val now = nowSec()
            rows.filter { AlertScope.shows(alertScope, it.scoped(), now) }
        }

    override val alerts: StateFlow<List<SahayAlert>> = visibleRows
        .map { rows -> rows.map { it.toAlert() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val unreadCount: StateFlow<Int> = visibleRows
        .map { rows -> rows.count { !it.read } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private val shelterSync = ShelterStatusSync(api, reader, packRepository)
    private val refreshLock = Mutex()
    @Volatile private var lastRefreshSec: Long? = null
    @Volatile private var knownRegionId: String? = null

    private val poller = ForegroundPoller(scope, POLL_INTERVAL_MS, ::canPoll) { refresh() }

    init {
        scope.launch { packRepository.activePack.collect { onPackChanged(it) } }
        if (pollWhileVisible) poller.attachToProcess()
    }

    // ------------------------------------------------------------------ AlertRepository

    /**
     * `GET /alerts?region&from&to&since` (today .. trip end), each wire verified before it is stored. Then the signed
     * shelter statuses are refreshed in the same cycle (only if the alerts call worked, so an offline phone waits
     * for one timeout, not two). Nothing to do without a trip pack, or after the trip has ended.
     */
    override suspend fun refresh(): Result<Unit> = refreshLock.withLock {
        val pack = packRepository.activePack.value ?: return Result.success(Unit)
        val alertScope = AlertScope.forPack(pack, clock) ?: return Result.success(Unit)
        refreshAlerts(alertScope, pack.tripEnd).also { if (it.isSuccess) shelterSync.sync(pack.regionId) }
    }

    private suspend fun refreshAlerts(alertScope: AlertScope, tripEnd: LocalDate): Result<Unit> {
        return try {
            val regionId = alertScope.regionId
            val startedAt = nowSec()
            val oldest = startedAt - WireCodec.MAX_AGE_SEC
            val since = maxOf(oldest, (lastRefreshSec ?: oldest) - REFRESH_OVERLAP_SEC)
            for (dto in api.alerts(regionId, since, LocalDate.now(clock), tripEnd)) {
                if (dto.regionId != null && dto.regionId != regionId) continue   // server ignored the filter
                val wire = dto.wire ?: continue
                val alert = reader.read(wire) as? WireMessage.Alert ?: continue
                ingestSignedAlert(alert, wire, AlertSource.INTERNET, regionId, dto.expiresAt)
            }
            lastRefreshSec = startedAt
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Alert refresh failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Online: server translation. Offline (or if the server fails): keyword match against the pack.
     * A match is saved in the list (already read, since the user just looked at it); "no match" is only returned.
     */
    override suspend fun translatePasted(text: String): SahayAlert {
        val input = text.trim().take(MAX_PASTED_CHARS)
        return try {
            translateOnline(input) ?: translateOffline(input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pasted text could not be processed: ${e.message}")
            couldNotTranslate(input)
        }
    }

    override suspend fun markRead(id: String) = dao.markRead(id)

    // ------------------------------------------------------------------ used by SmsAlertProcessor

    /**
     * Stores a verified alert wire unless it was seen before on any channel ([IngestResult.DUPLICATE]) or is not for
     * this trip ([IngestResult.OUT_OF_SCOPE]: no pack, another region, outside the trip dates, or expired).
     * [regionId] is known when the alert came from the region-filtered API call; for SMS it is worked out from the
     * alert's circle and the pack's bounding box.
     */
    internal suspend fun ingestSignedAlert(
        alert: WireMessage.Alert,
        wire: String,
        source: AlertSource,
        regionId: String? = null,
        expiresAtSec: Long? = null,
    ): IngestResult {
        val key = sha256Hex(wire.trim())
        if (dao.exists(key)) return IngestResult.DUPLICATE
        val pack = packRepository.activePack.value
        val resolvedRegion = regionId
            ?: pack?.takeIf { AlertScope.circleTouchesBbox(alert.lat, alert.lon, alert.radiusM, it.bbox) }?.regionId
            ?: return IngestResult.OUT_OF_SCOPE
        val lang = userLanguage()
        val texts = templates.texts(alert.templateCode, lang) ?: fallbackTexts()
        val entity =
            AlertEntity(
                dedupeKey = key,
                alertId = alert.id,
                templateCode = alert.templateCode,
                severity = alert.severity,
                lat = alert.lat,
                lon = alert.lon,
                radiusM = alert.radiusM,
                issuedAtEpochSec = alert.timestamp,
                isSimulation = alert.isSimulation,
                title = texts.title,
                body = texts.body,
                titleEn = texts.titleEn,
                bodyEn = texts.bodyEn,
                originalText = null,
                source = source.name,
                verification = Verification.VERIFIED_OFFICIAL.name,
                receivedAtEpochSec = nowSec(),
                read = false,
                regionId = resolvedRegion,
                expiresAtEpochSec = expiresAtSec?.takeIf { it > alert.timestamp },
            )
        val alertScope = AlertScope.forPack(pack, clock)
        if (!AlertScope.shows(alertScope, entity.scoped(), nowSec())) return IngestResult.OUT_OF_SCOPE
        return if (storeAndNotify(entity)) IngestResult.NEW else IngestResult.DUPLICATE
    }

    /**
     * Stores a plain-text SMS that [OfficialAlertDetector] matched. Not cryptographically verified, so it is
     * labelled MATCHED_OFFICIAL_SMS. If online, the wording is improved by the server in the background.
     */
    internal suspend fun ingestOfficialSms(match: OfficialMatch, body: String): Boolean {
        val text = body.trim()
        val key = sha256Hex("sms:$text")
        if (dao.exists(key)) return false
        val lang = userLanguage()
        val texts = templates.texts(match.code, lang) ?: fallbackTexts()
        val now = nowSec()
        val isNew = storeAndNotify(
            AlertEntity(
                dedupeKey = key,
                alertId = "sms_${key.take(ID_HASH_CHARS)}",
                templateCode = match.code,
                severity = texts.severity.coerceIn(0, 3),
                lat = null,
                lon = null,
                radiusM = null,
                issuedAtEpochSec = now,
                isSimulation = false,
                title = texts.title,
                body = texts.body,
                titleEn = texts.titleEn,
                bodyEn = texts.bodyEn,
                originalText = text,
                source = AlertSource.SMS_OFFICIAL.name,
                verification = Verification.MATCHED_OFFICIAL_SMS.name,
                receivedAtEpochSec = now,
                read = false,
            ),
        )
        if (isNew && isOnline()) scope.launch { improveWithServer(key, text, lang) }
        return isNew
    }

    // ------------------------------------------------------------------ internals

    /**
     * When the pack is deleted or the region changes, the old region's alerts are deleted and their notifications
     * removed. The very first emission (null while the saved pack is still being restored) is not a deletion.
     */
    private suspend fun onPackChanged(pack: PackInfo?) = refreshLock.withLock {
        val newRegion = pack?.regionId
        if (newRegion == knownRegionId) return@withLock
        knownRegionId = newRegion
        lastRefreshSec = null                                    // the next refresh fetches the whole window again
        val stale = dao.regionalAlertIdsOutside(newRegion)
        dao.deleteRegionalOutside(newRegion)
        stale.forEach(notifier::cancel)
    }

    /** Emits now and then every minute, so alerts disappear when they expire without any other change. */
    private fun minuteTicks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(EXPIRY_CHECK_INTERVAL_MS)
        }
    }

    private suspend fun storeAndNotify(entity: AlertEntity): Boolean {
        val isNew = dao.insertIfNew(entity) != INSERT_IGNORED
        val fresh = nowSec() - entity.issuedAtEpochSec <= NOTIFY_MAX_AGE_SEC
        if (isNew && fresh) notifier.notify(entity.toAlert())
        return isNew
    }

    /** Replaces the template wording with the server's simplified translation of the original SMS. Best effort. */
    private suspend fun improveWithServer(key: String, text: String, lang: String) {
        try {
            val result = api.translate(text, lang)
            dao.updateTexts(key, result.translated.cleaned(), result.simplifiedEn.cleaned())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Kept template wording, server translation unavailable: ${e.message}")
        }
    }

    private suspend fun translateOnline(input: String): SahayAlert? {
        if (input.isBlank() || !isOnline()) return null
        val lang = userLanguage()
        val result: TranslateDto = try {
            api.translate(input, lang)
        } catch (e: IOException) {
            Log.i(TAG, "Translate failed, using offline match: ${e.message}")
            return null
        }
        val translated = result.translated.cleaned() ?: return null
        val code = result.matchedTemplateCode?.takeIf { TEMPLATE_CODE.matches(it) }
        val texts = code?.let { templates.texts(it, lang) }
        return savePasted(
            input = input,
            code = code,
            severity = texts?.severity ?: DEFAULT_PASTED_SEVERITY,
            title = texts?.title ?: context.getString(R.string.comms_pasted_title),
            titleEn = texts?.titleEn ?: context.getEnglishString(R.string.comms_pasted_title),
            body = translated,
            bodyEn = result.simplifiedEn.cleaned() ?: texts?.bodyEn ?: translated,
        )
    }

    private suspend fun translateOffline(input: String): SahayAlert {
        val match = OfficialAlertDetector.bestMatch(input, packRepository.alertKeywords())
            ?: return couldNotTranslate(input)
        val lang = userLanguage()
        val texts = templates.texts(match.code, lang) ?: fallbackTexts()
        return savePasted(
            input = input,
            code = match.code,
            severity = texts.severity,
            title = texts.title,
            titleEn = texts.titleEn,
            body = texts.body,
            bodyEn = texts.bodyEn,
        )
    }

    private suspend fun savePasted(
        input: String, code: String?, severity: Int, title: String, titleEn: String, body: String, bodyEn: String,
    ): SahayAlert {
        val key = sha256Hex("pasted:$input")
        val now = nowSec()
        val entity = AlertEntity(
            dedupeKey = key,
            alertId = "pasted_${key.take(ID_HASH_CHARS)}",
            templateCode = code,
            severity = severity.coerceIn(0, 3),
            lat = null,
            lon = null,
            radiusM = null,
            issuedAtEpochSec = now,
            isSimulation = false,
            title = title,
            body = body,
            titleEn = titleEn,
            bodyEn = bodyEn,
            originalText = input,
            source = AlertSource.PASTED.name,
            verification = Verification.UNVERIFIED.name,
            receivedAtEpochSec = now,
            read = true,
        )
        dao.upsert(entity)
        return entity.toAlert()
    }

    private fun couldNotTranslate(input: String): SahayAlert {
        val now = nowSec()
        return SahayAlert(
            id = "pasted_${sha256Hex("pasted:$input").take(ID_HASH_CHARS)}",
            templateCode = null,
            severity = 0,
            area = null,
            radiusM = null,
            issuedAtEpochSec = now,
            isSimulation = false,
            title = context.getString(R.string.comms_pasted_title),
            body = context.getString(R.string.comms_translate_offline_failed),
            titleEn = context.getEnglishString(R.string.comms_pasted_title),
            bodyEn = context.getEnglishString(R.string.comms_translate_offline_failed),
            originalText = input.ifEmpty { null },
            source = AlertSource.PASTED,
            verification = Verification.UNVERIFIED,
            receivedAtEpochSec = now,
            read = true,
        )
    }

    private fun fallbackTexts() = TemplateTexts(
        severity = DEFAULT_PASTED_SEVERITY,
        title = context.getString(R.string.comms_alert_fallback_title),
        body = context.getString(R.string.comms_alert_fallback_body),
        titleEn = context.getEnglishString(R.string.comms_alert_fallback_title),
        bodyEn = context.getEnglishString(R.string.comms_alert_fallback_body),
    )

    private fun userLanguage(): String =
        profileStore.profile.value?.language?.takeIf { it in SahayConfig.USER_LANGUAGES } ?: "en"

    private fun canPoll() = isOnline() && packRepository.activePack.value != null

    private fun nowSec() = clock.instant().epochSecond

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_BODY_CHARS)

    private companion object {
        const val TAG = "AlertRepository"
        const val POLL_INTERVAL_MS = 2 * 60_000L
        const val EXPIRY_CHECK_INTERVAL_MS = 60_000L
        const val REFRESH_OVERLAP_SEC = 5 * 60L
        const val NOTIFY_MAX_AGE_SEC = 6 * 3600L      // do not buzz for a backlog the user was offline for
        const val MAX_PASTED_CHARS = 2_000
        const val MAX_BODY_CHARS = 600
        const val ID_HASH_CHARS = 10
        const val DEFAULT_PASTED_SEVERITY = 1
        const val INSERT_IGNORED = -1L
        val TEMPLATE_CODE = Regex("[A-Z0-9_]{1,24}")
    }
}

/** What [RealAlertRepository.ingestSignedAlert] did with a verified alert. */
internal enum class IngestResult { NEW, DUPLICATE, OUT_OF_SCOPE }

private fun AlertEntity.toAlert() = SahayAlert(
    id = alertId,
    templateCode = templateCode,
    severity = severity,
    area = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
    radiusM = radiusM,
    issuedAtEpochSec = issuedAtEpochSec,
    isSimulation = isSimulation,
    title = title,
    body = body,
    titleEn = titleEn,
    bodyEn = bodyEn,
    originalText = originalText,
    source = AlertSource.entries.firstOrNull { it.name == source } ?: AlertSource.INTERNET,
    verification = Verification.entries.firstOrNull { it.name == verification } ?: Verification.UNVERIFIED,
    receivedAtEpochSec = receivedAtEpochSec,
    read = read,
)
