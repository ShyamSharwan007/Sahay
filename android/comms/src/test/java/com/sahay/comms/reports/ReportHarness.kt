package com.sahay.comms.reports

import android.content.Context
import androidx.room.Room
import com.sahay.comms.net.OkHttpCommsApi
import com.sahay.core.contracts.AlertRepository
import com.sahay.core.contracts.AuthTokenProvider
import com.sahay.core.contracts.ConnectivityMonitor
import com.sahay.core.contracts.ConnectivityState
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.LocationProvider
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.Route
import com.sahay.core.contracts.RoutingEngine
import com.sahay.core.contracts.SahayAlert
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** What the fake server saw in a `POST /reports`. */
class PostedReport(val body: JsonObject, val authorization: String?)

class FakeAuth(var token: String? = "tok-1", var refreshed: String? = "tok-2") : AuthTokenProvider {
    override val uid: String? = "u1"
    override suspend fun idToken(forceRefresh: Boolean): String? = if (forceRefresh) refreshed else token
}

class RecordingRouting : RoutingEngine {
    val calls = CopyOnWriteArrayList<List<GeoPoint>>()
    override suspend fun routeTo(from: GeoPoint, to: GeoPoint): Route? = null
    override suspend fun routeToNearestSafe(from: GeoPoint): Route? = null
    override fun setBlockedPoints(points: List<GeoPoint>) { calls += points }
    val latest: List<GeoPoint>? get() = calls.lastOrNull()
}

/**
 * [RealReportRepository] wired to an in-memory Room database, a MockWebServer and a fixed clock.
 * The repository starts on first use, so a test can prepare the database and the server first.
 */
class ReportHarness(
    private val context: Context,
    online: Boolean = true,
    private val retryDelayMs: Long = 100,
    initialPack: PackInfo? = null,
) {
    val clock: Clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC)
    val spot = GeoPoint(12.6208, 80.1945)

    val db: ReportDatabase = Room.inMemoryDatabaseBuilder(context, ReportDatabase::class.java).allowMainThreadQueries().build()
    val connectivity = MutableStateFlow(ConnectivityState(internet = online, cellular = true, meshActive = false, meshPeers = 0))
    val alerts = MutableStateFlow<List<SahayAlert>>(emptyList())
    val lastFix = MutableStateFlow<LocationFix?>(LocationFix(spot, 8f, NOW * 1000))
    val auth = FakeAuth()
    val routing = RecordingRouting()
    val photoDir: File = Files.createTempDirectory("report_photos").toFile()

    // ---- the fake server
    val posts = CopyOnWriteArrayList<PostedReport>()
    val getUrls = CopyOnWriteArrayList<String>()
    val getAuthorizations = CopyOnWriteArrayList<String?>()
    /** Answer to the n-th POST (1-based). */
    @Volatile var postResponse: (Int) -> MockResponse = { acceptedResponse() }
    /** Body of `GET /reports`; `getCode` is its HTTP status. */
    @Volatile var reportsJson: String = "[]"
    @Volatile var getCode: Int = 200

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    request.method == "POST" && path.endsWith("/reports") -> {
                        val body = Json.parseToJsonElement(request.bodyText()).jsonObject
                        posts += PostedReport(body, request.headers["Authorization"])
                        postResponse(posts.size)
                    }
                    request.method == "GET" && path.endsWith("/reports") -> {
                        getUrls += request.url.toString()
                        getAuthorizations += request.headers["Authorization"]
                        MockResponse.Builder().code(getCode).addHeader("Content-Type", "application/json").body(reportsJson).build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        start()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository: RealReportRepository by lazy {
        val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
        val pack = mockk<PackRepository> { every { this@mockk.activePack } returns MutableStateFlow(initialPack) }
        val location = mockk<LocationProvider> { every { this@mockk.lastFix } returns this@ReportHarness.lastFix }
        val alertRepository = mockk<AlertRepository> { every { this@mockk.alerts } returns this@ReportHarness.alerts }
        val monitor = mockk<ConnectivityMonitor> { every { state } returns connectivity }
        RealReportRepository(
            dao = daoOverride ?: db.reportDao(),
            api = OkHttpCommsApi(client, server.url("/api/v1/"), 1),
            auth = auth,
            packRepository = pack,
            locationProvider = location,
            alertRepository = alertRepository,
            routing = routing,
            connectivity = monitor,
            photos = ReportPhotoStore(photoDir),
            clock = clock,
            scope = scope,
            retryDelayMs = retryDelayMs,
            recomputeEveryMs = 60_000,
        )
    }

    /** Replace the DAO before the repository starts, to simulate a broken database. */
    var daoOverride: ReportDao? = null

    fun goOnline(online: Boolean = true) { connectivity.value = connectivity.value.copy(internet = online) }

    /** Waits (real time) until [condition] holds. */
    suspend fun waitUntil(what: String, condition: suspend () -> Boolean) {
        try {
            withTimeout(15_000) { while (!condition()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what")
        }
    }

    /** Waits until the database holds no PENDING report (reads the database, not the StateFlow whose first value is 0). */
    suspend fun awaitNoPending() = waitUntil("no pending reports") { db.reportDao().pending().isEmpty() }

    fun close() {
        scope.cancel()
        server.close()
        db.close()
        photoDir.deleteRecursively()
    }

    private fun RecordedRequest.bodyText(): String = body?.utf8() ?: ""

    companion object {
        const val NOW = 1_760_000_000L

        fun acceptedResponse(id: String = "r_9f2", trust: Double = 0.72, label: String = "VERIFIED"): MockResponse =
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(
                """{"id":"$id","type":"FL","lat":12.6208,"lon":80.1945,"note":null,"photoUrl":null,"createdAt":$NOW,""" +
                    """"trustScore":$trust,"label":"$label","mine":true,"channel":"INTERNET"}""",
            ).build()

        fun errorResponse(code: Int): MockResponse =
            MockResponse.Builder().code(code).body("""{"error":{"code":"X","message":"x"}}""").build()

        /** One element of `GET /reports`. */
        fun serverReport(
            id: String, type: String = "FL", at: GeoPoint = GeoPoint(12.6208, 80.1945), ageSec: Long = 300,
            trust: Double = 0.5, label: String = "LIKELY", mine: Boolean = false,
        ) = """{"id":"$id","type":"$type","lat":${at.lat},"lon":${at.lon},"note":null,"photoUrl":null,""" +
            """"createdAt":${NOW - ageSec},"trustScore":$trust,"label":"$label","mine":$mine,"channel":"INTERNET"}"""

        fun pack(regionId: String) = PackInfo(
            regionId = regionId, regionName = regionId, packVersion = "test", bbox = listOf(80.16, 12.59, 80.21, 12.65),
            tripStart = LocalDate.of(2025, 10, 1), tripEnd = LocalDate.of(2025, 10, 10), downloadedAtEpochSec = 0,
            forecast = emptyList(), historySummary = emptyMap(), incidents = emptyList(), precautions = emptyList(),
            publicKeyB64 = "", sizeBytes = 0,
        )
    }
}
