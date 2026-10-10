package com.sahay.comms.reports

import android.util.Log
import com.sahay.comms.net.ApiException
import com.sahay.comms.net.ReportDto
import com.sahay.comms.net.ReportRequest
import com.sahay.comms.net.ReportsApi
import com.sahay.core.contracts.AlertRepository
import com.sahay.core.contracts.AuthTokenProvider
import com.sahay.core.contracts.Channel
import com.sahay.core.contracts.ConnectivityMonitor
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.HazardType
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.ReportRepository
import com.sahay.core.contracts.RoutingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel as SignalChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hazard reports: saved in Room first, then sent by internet when there is one.
 *
 * - [submit] never throws. The report is stored before any network call, so it survives a crash or no signal.
 * - A report stays PENDING until the server accepts it; it is retried when the connection returns and every
 *   [RETRY_DELAY_MS] while the server keeps failing. A refusal that cannot get better (4xx) marks it FAILED.
 * - [reports] is the server's list (last [ReportMerger.WINDOW_MIN] minutes) merged with the reports made here.
 * - Every change of that list updates the routing engine's blocked points.
 *
 * Only the internet channel exists so far; the `channel` column is ready for SMS and mesh delivery.
 */
@Singleton
class RealReportRepository internal constructor(
    private val dao: ReportDao,
    private val api: ReportsApi,
    private val auth: AuthTokenProvider,
    private val packRepository: PackRepository,
    private val locationProvider: LocationProvider,
    private val alertRepository: AlertRepository,
    private val routing: RoutingEngine,
    private val connectivity: ConnectivityMonitor,
    private val photos: ReportPhotoStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val retryDelayMs: Long,
    recomputeEveryMs: Long,
) : ReportRepository {

    @Inject constructor(
        dao: ReportDao,
        api: ReportsApi,
        auth: AuthTokenProvider,
        packRepository: PackRepository,
        locationProvider: LocationProvider,
        alertRepository: AlertRepository,
        routing: RoutingEngine,
        connectivity: ConnectivityMonitor,
        photos: ReportPhotoStore,
    ) : this(
        dao, api, auth, packRepository, locationProvider, alertRepository, routing, connectivity, photos,
        Clock.systemUTC(), CoroutineScope(SupervisorJob() + Dispatchers.Default), RETRY_DELAY_MS, RECOMPUTE_EVERY_MS,
    )

    private val serverReports = MutableStateFlow<List<HazardReport>>(emptyList())

    // Trust fades with time, so the list is rebuilt every minute even when nothing else changed.
    private val clockTicks = flow { while (true) { emit(Unit); delay(recomputeEveryMs) } }

    override val reports: StateFlow<List<HazardReport>> = combine(
        dao.observeAll(), serverReports, alertRepository.alerts, clockTicks,
    ) { local, server, alerts, _ -> ReportMerger.merge(local, server, alerts, nowSec()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val pendingCount: StateFlow<Int> = dao.observePendingCount().stateIn(scope, SharingStarted.Eagerly, 0)

    private val sendLock = Mutex()
    private val flushRequests = SignalChannel<Unit>(SignalChannel.CONFLATED)
    private val retryScheduled = AtomicBoolean(false)

    init {
        scope.launch {
            reports.map(ReportMerger::blockedPoints).distinctUntilChanged().collect { routing.setBlockedPoints(it) }
        }
        scope.launch {
            for (request in flushRequests) {
                if (!flushPending()) scheduleRetry()
            }
        }
        scope.launch {
            // Starts with the current state, so reports left over from the last run go out at app start.
            connectivity.state.map { it.internet }.distinctUntilChanged().filter { it }.collect {
                flushRequests.trySend(Unit)
                refresh()
            }
        }
    }

    // ------------------------------------------------------------------ ReportRepository

    override suspend fun submit(type: HazardType, point: GeoPoint, note: String?, photoJpeg: ByteArray?): HazardReport {
        val localId = UUID.randomUUID().toString()
        val usable = isUsable(point)
        val reporter = recentFix()                      // read once: lat and lon must come from the same fix
        val entity = ReportEntity(
            localId = localId,
            serverId = null,
            type = type.code,
            lat = point.lat,
            lon = point.lon,
            reporterLat = reporter?.lat,
            reporterLon = reporter?.lon,
            note = cleanNote(note),
            photoPath = if (usable) photos.save(localId, photoJpeg) else null,
            createdAtEpochSec = nowSec(),
            status = (if (usable) ReportStatus.PENDING else ReportStatus.FAILED).name,
            channel = Channel.INTERNET.name,
            serverTrust = null,
            serverPhotoUrl = null,
        )
        // A point the server could never accept is neither stored nor sent; the caller just sees it did not go out.
        if (usable && save(entity)) sendNow(localId)
        return currentView(entity)
    }

    /** False if the report could not be stored (full disk, corrupt database). Never throws. */
    private suspend fun save(entity: ReportEntity): Boolean = try {
        dao.insert(entity)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Could not save the report: ${e.message}")
        photos.delete(entity.photoPath)
        false
    }

    /** `GET /reports`, replacing the server part of [reports]. Local reports are kept either way. */
    override suspend fun refresh(): Result<Unit> {
        if (!connectivity.state.value.internet) return Result.failure(IOException("Offline"))
        return try {
            val items = api.reports(packRepository.activePack.value?.regionId, ReportMerger.WINDOW_MIN, token())
            serverReports.value = items.mapNotNull(ReportMerger::fromDto)
            val cutoff = nowSec() - KEEP_FINISHED_SEC
            dao.finishedBefore(cutoff).forEach { photos.delete(it.photoPath) }
            dao.deleteFinishedBefore(cutoff)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Report refresh failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------ sending

    /** Tries to deliver one fresh report now. Waits for the answer but survives the caller leaving the screen. */
    private suspend fun sendNow(localId: String) {
        if (!connectivity.state.value.internet) return
        val outcome = scope.async { sendLock.withLock { sendOne(localId) } }.await()
        if (outcome == SendOutcome.RETRY_LATER) scheduleRetry()
    }

    /** Sends every PENDING report, oldest first. Returns false if some are still waiting and a retry makes sense. */
    private suspend fun flushPending(): Boolean {
        if (!connectivity.state.value.internet) return true      // the connectivity watcher asks again on reconnect
        for (report in dao.pending()) {
            val outcome = sendLock.withLock { sendOne(report.localId) }
            if (outcome == SendOutcome.RETRY_LATER) return false  // the server or network is struggling: do not hammer it
        }
        for (report in dao.photosToUpload()) {
            val outcome = sendLock.withLock { uploadPhoto(report.localId) }
            if (outcome == SendOutcome.RETRY_LATER) return false
        }
        return true
    }

    private enum class SendOutcome { DONE, RETRY_LATER }

    /** Must be called with [sendLock] held. Re-reads the row, so a report another caller just sent is skipped. */
    private suspend fun sendOne(localId: String): SendOutcome {
        val entity = dao.find(localId)?.takeIf { it.status == ReportStatus.PENDING.name } ?: return SendOutcome.DONE
        val request = ReportRequest(
            type = entity.type,
            lat = entity.lat,
            lon = entity.lon,
            reporterLat = entity.reporterLat ?: 0.0,      // 0,0 = "no fix" for the server
            reporterLon = entity.reporterLon ?: 0.0,
            note = entity.note,
            photoBase64 = null,                           // the photo goes up separately, see uploadPhoto
            createdAt = entity.createdAtEpochSec,
            channel = entity.channel,
        )
        return try {
            val answer = post(request) ?: return SendOutcome.RETRY_LATER
            dao.markSent(localId, answer.id, answer.trustScore.coerceIn(0.0, 1.0), answer.photoUrl)
            // The report is safe on the server now; a failing photo upload is retried later and never undoes that.
            if (entity.photoPath != null && uploadPhoto(localId) == SendOutcome.RETRY_LATER) SendOutcome.RETRY_LATER else SendOutcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (isFinalRefusal(e.httpCode)) {
                Log.w(TAG, "Server refused a report for good (HTTP ${e.httpCode})")
                dao.markFailed(localId)
                photos.delete(entity.photoPath)
                SendOutcome.DONE
            } else {
                SendOutcome.RETRY_LATER
            }
        } catch (e: IOException) {
            Log.i(TAG, "Report not sent yet: ${e.message}")
            SendOutcome.RETRY_LATER
        } catch (e: Exception) {
            Log.w(TAG, "Report not sent: ${e.message}")
            SendOutcome.RETRY_LATER
        }
    }

    /**
     * Uploads the photo of a report the server already has. Must be called with [sendLock] held.
     * A refusal that cannot get better (too big, not an image, not found) gives the photo up; the report stays.
     */
    private suspend fun uploadPhoto(localId: String): SendOutcome {
        val entity = dao.find(localId)
            ?.takeIf { it.status == ReportStatus.SENT.name && !it.photoUploaded && it.serverId != null && it.photoPath != null }
            ?: return SendOutcome.DONE
        val serverId = entity.serverId ?: return SendOutcome.DONE
        val jpeg = photos.read(entity.photoPath)
        if (jpeg == null) { // the file is gone: nothing left to send
            dao.markPhotoUploaded(localId, null, null)
            return SendOutcome.DONE
        }
        return try {
            val token = token() ?: return SendOutcome.RETRY_LATER
            val answer = try {
                api.uploadPhoto(serverId, jpeg, token)
            } catch (e: ApiException) {
                if (e.httpCode != HTTP_UNAUTHORIZED) throw e
                val fresh = token(forceRefresh = true)?.takeIf { it != token } ?: throw e
                api.uploadPhoto(serverId, jpeg, fresh)
            }
            dao.markPhotoUploaded(localId, answer.photoUrl, answer.reviewStatus ?: "pending")
            SendOutcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (isFinalRefusal(e.httpCode) || e.httpCode == HTTP_PAYLOAD_TOO_LARGE) {
                Log.w(TAG, "Server refused a photo for good (HTTP ${e.httpCode})")
                dao.markPhotoUploaded(localId, null, null)
                SendOutcome.DONE
            } else {
                SendOutcome.RETRY_LATER
            }
        } catch (e: Exception) {
            Log.i(TAG, "Photo not sent yet: ${e.message}")
            SendOutcome.RETRY_LATER
        }
    }

    /** POST with the user's token; on 401 once more with a refreshed one. Null when signed out. */
    private suspend fun post(request: ReportRequest): ReportDto? {
        val token = token() ?: return null
        return try {
            api.postReport(request, token)
        } catch (e: ApiException) {
            if (e.httpCode != HTTP_UNAUTHORIZED) throw e
            val fresh = token(forceRefresh = true)?.takeIf { it != token } ?: throw e
            api.postReport(request, fresh)
        }
    }

    /** 4xx means the request itself is wrong, except: expired login, timeout and rate limit, which can pass later. */
    private fun isFinalRefusal(code: Int?): Boolean =
        code != null && code in 400..499 && code != HTTP_UNAUTHORIZED && code != HTTP_TIMEOUT && code != HTTP_TOO_MANY_REQUESTS

    private fun scheduleRetry() {
        if (!retryScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(retryDelayMs)
            retryScheduled.set(false)
            flushRequests.trySend(Unit)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** What the app should show for [entity] right now (it may have just been sent). */
    private suspend fun currentView(entity: ReportEntity): HazardReport {
        val latest = try {
            dao.find(entity.localId) ?: entity
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            entity
        }
        val others = serverReports.value.filter { !it.mine }
        return ReportMerger.toHazardReport(latest, others, alertRepository.alerts.value, nowSec())
    }

    private suspend fun token(forceRefresh: Boolean = false): String? = try {
        auth.idToken(forceRefresh)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No login token: ${e.message}")
        null
    }

    /** Where the reporter is, if a fix younger than [FIX_MAX_AGE_MS] exists. A stale position would distort trust. */
    private fun recentFix(): GeoPoint? = locationProvider.lastFix.value
        ?.takeIf { clock.millis() - it.timeMs <= FIX_MAX_AGE_MS }
        ?.point

    private fun isUsable(point: GeoPoint) =
        point.lat.isFinite() && point.lon.isFinite() && point.lat in -90.0..90.0 && point.lon in -180.0..180.0

    /** Blank becomes null; the server accepts at most [MAX_NOTE_CHARS] characters. */
    private fun cleanNote(note: String?): String? {
        val trimmed = note?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.length <= MAX_NOTE_CHARS) return trimmed
        val end = if (Character.isHighSurrogate(trimmed[MAX_NOTE_CHARS - 1])) MAX_NOTE_CHARS - 1 else MAX_NOTE_CHARS
        return trimmed.substring(0, end)
    }

    private fun nowSec() = clock.instant().epochSecond

    internal companion object {
        private const val TAG = "ReportRepository"
        const val RETRY_DELAY_MS = 60_000L
        const val RECOMPUTE_EVERY_MS = 60_000L
        const val MAX_NOTE_CHARS = 140
        const val FIX_MAX_AGE_MS = 10 * 60_000L
        private const val KEEP_FINISHED_SEC = 48 * 3600L
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TIMEOUT = 408
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
