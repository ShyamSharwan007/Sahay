package com.sahay.comms.alerts

import android.content.Context
import androidx.room.Room
import com.sahay.comms.net.AlertDto
import com.sahay.comms.net.ApiException
import com.sahay.comms.net.CommsApi
import com.sahay.comms.net.ShelterStatusDto
import com.sahay.comms.net.TemplateDto
import com.sahay.comms.net.TranslateDto
import com.sahay.comms.sms.GroupWireCache
import com.sahay.comms.sms.SmsAlertProcessor
import com.sahay.comms.wire.TestVectors
import com.sahay.core.contracts.AlertKeyword
import com.sahay.core.contracts.AlertTemplate
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.ProfileStore
import com.sahay.core.contracts.SahayAlert
import com.sahay.core.contracts.UserProfile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.time.LocalDate

/** Records what the app would show as a notification. */
class RecordingNotifier : AlertNotifier {
    val shown = mutableListOf<SahayAlert>()
    override fun notify(alert: SahayAlert) { synchronized(shown) { shown += alert } }
}

/** In-memory stand-in for the backend. */
class FakeCommsApi : CommsApi {
    var alertWires: List<String?> = emptyList()
    var failAlerts = false
    var translateResult: TranslateDto? = null          // null = the call fails
    var shelterWires: List<String?> = emptyList()
    var failShelters = false
    val shelterRequests = mutableListOf<String>()
    val alertRequests = mutableListOf<Pair<String, Long>>()
    val translateRequests = mutableListOf<Pair<String, String>>()

    override suspend fun alerts(regionId: String, sinceEpochSec: Long): List<AlertDto> {
        alertRequests += regionId to sinceEpochSec
        if (failAlerts) throw ApiException("HTTP 503", 503)
        return alertWires.map { AlertDto(id = "x", wire = it) }
    }

    override suspend fun alertTemplates(lang: String): List<TemplateDto> = throw ApiException("not used")

    override suspend fun shelterStatuses(regionId: String): List<ShelterStatusDto> {
        shelterRequests += regionId
        if (failShelters) throw ApiException("HTTP 503", 503)
        return shelterWires.map { ShelterStatusDto(shelterId = "x", wire = it) }
    }

    override suspend fun translate(text: String, targetLang: String): TranslateDto {
        translateRequests += text to targetLang
        return translateResult ?: throw ApiException("HTTP 500", 500)
    }
}

/** Everything the alert code needs, wired with an in-memory Room database and a fixed clock. */
class AlertHarness(
    context: Context,
    language: String = "en",
    var online: Boolean = false,
    hasPack: Boolean = true,
) {
    val db: CommsDatabase = Room.inMemoryDatabaseBuilder(context, CommsDatabase::class.java)
        .allowMainThreadQueries().build()
    val api = FakeCommsApi()
    val notifier = RecordingNotifier()
    val groupCache = GroupWireCache()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val keywords = listOf(
        AlertKeyword("CYC_WARN", "en", "cyclone"), AlertKeyword("CYC_WARN", "en", "warning"),
        AlertKeyword("FLD_WARN", "en", "flood"), AlertKeyword("FLD_WARN", "en", "warning"),
    )
    private val templates = mapOf(
        ("FLD_EVAC" to "en") to AlertTemplate("FLD_EVAC", "en", 3, "Flood: go to safety", "Move to higher ground or a shelter now."),
        ("FLD_EVAC" to "de") to AlertTemplate("FLD_EVAC", "de", 3, "Hochwasser: sofort in Sicherheit", "Gehen Sie jetzt in eine Notunterkunft."),
        ("CYC_WARN" to "en") to AlertTemplate("CYC_WARN", "en", 2, "Cyclone warning", "Stay indoors. Secure your belongings."),
    )

    val pack: PackRepository = mockk {
        every { activePack } returns MutableStateFlow(if (hasPack) packInfo() else null)
        coEvery { alertTemplate(any(), any()) } answers {
            val code = firstArg<String>()
            val lang = secondArg<String>()
            templates[code to lang] ?: templates[code to "en"]
        }
        coEvery { alertKeywords() } returns keywords
        coEvery { updateShelterStatus(any(), any(), any()) } just runs
    }

    private val profileStore: ProfileStore = mockk {
        every { profile } returns MutableStateFlow(userProfile(language))
    }

    private val resolver = TemplateResolver(pack, db.templateCacheDao(), api, { online }, TestVectors.clock)

    val repository = RealAlertRepository(
        context = context,
        dao = db.alertDao(),
        api = api,
        packRepository = pack,
        profileStore = profileStore,
        reader = TestVectors.reader(),
        templates = resolver,
        notifier = notifier,
        isOnline = { online },
        clock = TestVectors.clock,
        scope = scope,
        pollWhileVisible = false,
    )

    val processor = SmsAlertProcessor(TestVectors.reader(), repository, pack, groupCache)

    fun close() {
        scope.cancel()
        db.close()
    }

    private fun packInfo() = PackInfo(
        regionId = "mahabalipuram", regionName = "Mahabalipuram", packVersion = "test", bbox = listOf(80.16, 12.59, 80.21, 12.65),
        tripStart = LocalDate.of(2025, 10, 1), tripEnd = LocalDate.of(2025, 10, 10), downloadedAtEpochSec = 0,
        forecast = emptyList(), historySummary = emptyMap(), incidents = emptyList(), precautions = emptyList(),
        publicKeyB64 = TestVectors.publicKeyB64, sizeBytes = 0,
    )

    private fun userProfile(language: String) = UserProfile(
        uid = "u1", isGuest = true, displayName = "Test", email = null, photoUrl = null, language = language,
        nationality = null, phone = null, bloodGroup = null, allergies = null, medications = null, conditions = null,
        hotelName = null, hotelAddress = null, contacts = emptyList(), smsAlertsOptIn = false,
        groupFinderOptIn = false, onboardingComplete = true,
    )
}

/** Waits (real time) until the flow holds a value that satisfies [predicate]. */
suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = withTimeout(5_000) { first(predicate) }
