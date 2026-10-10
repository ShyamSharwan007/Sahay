package com.sahay.core.contracts

// FROZEN CONTRACT — shared by :app (A), :engine (B) and :comms (D).
// Do not edit without "contract change approved". Exception: A may edit the two SahayConfig values.
// Pure Kotlin + coroutines only. No Android UI, no Compose, no third-party types.

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

// ---------------------------------------------------------------- config

object SahayConfig {
    const val BASE_URL = "https://sahay-vr1c.onrender.com/api/v1/"   // A updates when C deploys  // A updates when C deploys
    const val SMS_GATEWAY_NUMBER = "+910000000000"                   // A updates when D sets up the gateway phone
    const val EMERGENCY_NUMBER = "112"
    const val NETWORK_TIMEOUT_MS = 10_000L
    const val WALKING_M_PER_MIN = 75.0
    const val LOCAL_LANGUAGE = "ta"
    val USER_LANGUAGES = listOf("en", "de", "fr", "es", "ru", "ja", "ko", "zh", "ar")
    const val WIRE_PREFIX = "SH1"
    const val WIRE_SEP = '*'           // not '|' — pipe is outside the GSM-7 basic alphabet
    const val WIRE_MAX_LEN = 160
    const val MESH_MAX_HOPS = 3
}

/** Notification → app navigation. MainActivity reads intent extra [EXTRA]. */
object DeepLinks {
    const val EXTRA = "sahay_deeplink"
    const val NAVIGATE_SAFE = "navigate_safe"
    const val EMERGENCY = "emergency"
    const val GROUPS = "groups"
    fun alert(id: String) = "alert:$id"
}

// ---------------------------------------------------------------- shared basics

data class GeoPoint(val lat: Double, val lon: Double)

/** Text in several languages, keyed by language code. */
typealias LocalizedText = Map<String, String>

fun LocalizedText.pick(lang: String): String =
    this[lang] ?: this["en"] ?: values.firstOrNull() ?: ""

enum class Channel { INTERNET, SMS, MESH }

// ---------------------------------------------------------------- profile & auth (A implements)

data class EmergencyContact(val name: String, val phone: String, val relation: String)

data class UserProfile(
    val uid: String,
    val isGuest: Boolean,
    val displayName: String,
    val email: String?,
    val photoUrl: String?,
    val language: String,              // one of SahayConfig.USER_LANGUAGES
    val nationality: String?,          // ISO-3166 alpha-2, e.g. "DE"
    val phone: String?,                // E.164, only sent to server if smsAlertsOptIn
    val bloodGroup: String?,           // "A+", "O-", ... or null = unknown
    val allergies: String?,
    val medications: String?,
    val conditions: String?,
    val hotelName: String?,
    val hotelAddress: String?,
    val contacts: List<EmergencyContact>,
    val smsAlertsOptIn: Boolean,
    val groupFinderOptIn: Boolean,
    val onboardingComplete: Boolean,
)

interface ProfileStore {
    val profile: StateFlow<UserProfile?>
    suspend fun save(profile: UserProfile)
    suspend fun clear()
}

interface AuthTokenProvider {
    /** Firebase uid, or null when signed out. */
    val uid: String?
    /** Firebase ID token for the Authorization header; null when signed out or offline without cache. */
    suspend fun idToken(forceRefresh: Boolean = false): String?
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

interface UiPreferences {
    val themeMode: StateFlow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)
}

// ---------------------------------------------------------------- packs & places (B implements)

enum class PoiType { SHELTER, CANDIDATE_SHELTER, HOSPITAL, POLICE }
enum class ShelterStatus { OPEN, FULL, CLOSED, UNKNOWN }

data class Poi(
    val id: String,
    val type: PoiType,
    val name: String,
    val nameTa: String?,
    val point: GeoPoint,
    val phone: String?,
    val isOfficial: Boolean,
    val elevationM: Double?,
    val capacity: Int?,
    val status: ShelterStatus = ShelterStatus.UNKNOWN,
)

data class Region(val id: String, val name: String, val bbox: List<Double>)

data class ForecastDay(
    val date: LocalDate,
    val rainMm: Double,
    val windKmh: Double,
    val maxTempC: Double,
    val riskLevel: String,             // LOW | MODERATE | HIGH | SEVERE
)

data class Precaution(val id: String, val severity: Int, val title: LocalizedText, val body: LocalizedText)

data class Incident(
    val date: LocalDate,
    val type: String,
    val title: LocalizedText,
    val summary: LocalizedText,
    val sourceUrl: String?,
)

data class PackInfo(
    val regionId: String,
    val regionName: String,
    val packVersion: String,
    val bbox: List<Double>,
    val tripStart: LocalDate,
    val tripEnd: LocalDate,
    val downloadedAtEpochSec: Long,
    val forecast: List<ForecastDay>,
    val historySummary: LocalizedText,
    val incidents: List<Incident>,
    val precautions: List<Precaution>,
    val publicKeyB64: String,
    val sizeBytes: Long,
)

sealed interface PackDownloadState {
    data object Idle : PackDownloadState
    /** step is one of: MANIFEST, DATA, MAP, ASSETS, VERIFY */
    data class Downloading(val step: String, val progress: Float) : PackDownloadState
    data class Done(val pack: PackInfo) : PackDownloadState
    data class Failed(val reason: String, val retryable: Boolean) : PackDownloadState
}

data class AlertTemplate(val code: String, val lang: String, val severity: Int, val title: String, val body: String)
data class AlertKeyword(val code: String, val lang: String, val keyword: String)
data class Phrase(val id: String, val lang: String, val category: String, val text: String, val icon: String?)
data class Embassy(val countryCode: String, val name: String, val phone: String?, val address: String?, val point: GeoPoint?, val url: String?)
data class RadioStation(val name: String, val frequency: String, val lang: String?)

interface PackRepository {
    /** Online list with a built-in fallback so it never fails. */
    suspend fun regions(): List<Region>
    fun download(regionId: String, start: LocalDate, end: LocalDate): Flow<PackDownloadState>
    /** Active (fully downloaded and verified) pack, null if none. Survives app restarts. */
    val activePack: StateFlow<PackInfo?>
    suspend fun deletePack()

    suspend fun pois(types: Set<PoiType> = PoiType.entries.toSet()): List<Poi>
    suspend fun nearestPois(from: GeoPoint, type: PoiType, limit: Int = 5): List<Pair<Poi, Double>>   // metres
    suspend fun riskZones(): List<RiskZone>
    suspend fun alertTemplate(code: String, lang: String): AlertTemplate?   // falls back to "en"
    suspend fun alertTemplates(lang: String): List<AlertTemplate>
    suspend fun alertKeywords(): List<AlertKeyword>
    suspend fun phrases(lang: String): List<Phrase>
    suspend fun embassy(countryCode: String): Embassy?
    suspend fun radios(): List<RadioStation>
    /** D calls this after verifying a signed shelter-status wire. Stored locally, used by routing. */
    suspend fun updateShelterStatus(shelterId: String, status: ShelterStatus, updatedAtEpochSec: Long)
}

// ---------------------------------------------------------------- location, risk, routing (B implements)

data class LocationFix(val point: GeoPoint, val accuracyM: Float, val timeMs: Long)
enum class LocationMode { LOW_POWER, BALANCED, NAVIGATION }

interface LocationProvider {
    val lastFix: StateFlow<LocationFix?>
    fun hasPermission(): Boolean
    fun updates(mode: LocationMode): Flow<LocationFix>
    suspend fun currentFix(timeoutMs: Long = 10_000): LocationFix?
}

enum class RiskLevel { HIGH, MEDIUM }

data class RiskZone(
    val id: String,
    val name: String?,
    val level: RiskLevel,
    val polygons: List<List<GeoPoint>>,   // outer rings only
)

interface RiskMonitor {
    val currentZone: StateFlow<RiskZone?>
    suspend fun zoneAt(point: GeoPoint): RiskZone?
    fun startMonitoring()
    fun stopMonitoring()
}

enum class RouteWarning { START_FAR_FROM_ROAD, NO_OPEN_SHELTER_USING_HOSPITAL, PASSES_RISK_AREA, NO_PACK }

data class Route(
    val points: List<GeoPoint>,
    val distanceM: Double,
    val etaMin: Int,
    val destination: Poi?,
    val avoidsRiskZones: Boolean,
    val isStraightLine: Boolean,
    val warnings: List<RouteWarning>,
)

interface RoutingEngine {
    suspend fun routeTo(from: GeoPoint, to: GeoPoint): Route?
    suspend fun routeToNearestSafe(from: GeoPoint): Route?
    /** Points from verified/likely FLOOD or ROAD_BLOCKED reports; edges within 50 m are avoided. */
    fun setBlockedPoints(points: List<GeoPoint>)
}

interface EmergencyModeController {
    val isActive: StateFlow<Boolean>
    /** Starts the foreground service, faster location, geofencing; D observes isActive for mesh/presence. */
    fun activate()
    fun deactivate()
}

// ---------------------------------------------------------------- alerts, SOS, reports, groups (D implements)

enum class AlertSource { INTERNET, SMS_SERVER, SMS_OFFICIAL, MESH, PASTED }

enum class Verification {
    VERIFIED_OFFICIAL,      // valid Ed25519 signature from our authority dashboard
    MATCHED_OFFICIAL_SMS,   // looks like a government SMS (keyword match), not cryptographically verified
    UNVERIFIED,             // pasted / free text
}

data class SahayAlert(
    val id: String,
    val templateCode: String?,
    val severity: Int,                 // 0 info, 1 watch, 2 warning, 3 emergency
    val area: GeoPoint?,
    val radiusM: Int?,
    val issuedAtEpochSec: Long,
    val isSimulation: Boolean,
    val title: String,                 // in the user's language
    val body: String,                  // simplified, in the user's language
    val titleEn: String,
    val bodyEn: String,
    val originalText: String?,         // raw SMS / pasted text if any
    val source: AlertSource,
    val verification: Verification,
    val receivedAtEpochSec: Long,
    val read: Boolean,
)

interface AlertRepository {
    /** Newest first, de-duplicated across channels. */
    val alerts: StateFlow<List<SahayAlert>>
    val unreadCount: StateFlow<Int>
    suspend fun refresh(): Result<Unit>
    /** Online: server LLM translation. Offline: keyword/template match. Never throws. */
    suspend fun translatePasted(text: String): SahayAlert
    suspend fun markRead(id: String)
}

data class SosResult(
    val sentTo: List<String>,
    val failed: List<String>,
    val includedLocation: GeoPoint?,
    val message: String,
)

interface SosService {
    /** Preview of the exact SMS text that will be sent. */
    suspend fun previewMessage(): String
    /** Sends the SOS SMS to every emergency contact. Works with no internet. */
    suspend fun sendSos(): SosResult
}

enum class HazardType(val code: String) {
    FLOOD("FL"), ROAD_BLOCKED("RB"), SHELTER_FULL("SF"), SHELTER_OPEN("SO"),
    POWER_LINE("PL"), LANDSLIDE("LS"), OTHER("OT");
    companion object { fun fromCode(code: String) = entries.firstOrNull { it.code == code } }
}

enum class TrustLabel { VERIFIED, LIKELY, UNCONFIRMED }

data class HazardReport(
    val id: String,
    val type: HazardType,
    val point: GeoPoint,
    val note: String?,
    val photoUrl: String?,
    val createdAtEpochSec: Long,
    val trustScore: Double,            // 0..1
    val label: TrustLabel,
    val mine: Boolean,
    val channel: Channel,
    val pendingSync: Boolean,          // true while queued offline
    /** Local file of the photo on this phone (own reports only); null when none. Added with photo upload. */
    val photoPath: String? = null,
    /** Photo review: "pending", "approved" or "rejected"; null when the report has no photo. */
    val reviewStatus: String? = null,
)

interface ReportRepository {
    val reports: StateFlow<List<HazardReport>>
    val pendingCount: StateFlow<Int>
    /** Queues locally and sends by internet → SMS → mesh. Never throws for missing network. */
    suspend fun submit(type: HazardType, point: GeoPoint, note: String?, photoJpeg: ByteArray?): HazardReport
    suspend fun refresh(): Result<Unit>
}

enum class GroupStatus { AT_SHELTER, SAFE_AREA, RISK_ZONE }

data class PeopleGroup(
    val id: String,
    val point: GeoPoint,
    val size: Int,
    val status: GroupStatus,
    val lastSeenEpochSec: Long,
    val channel: Channel,
)

data class BuddyBeacon(val id: String, val point: GeoPoint, val createdAtEpochSec: Long, val mine: Boolean)

data class GroupsSnapshot(
    val groups: List<PeopleGroup>,
    val beacons: List<BuddyBeacon>,
    val nearbyAppUsers: Int,           // from Bluetooth mesh, 0 if mesh off
    val minGroupSize: Int,
    val updatedAtEpochSec: Long?,
    val isStale: Boolean,              // older than 15 min
)

interface GroupService {
    val snapshot: StateFlow<GroupsSnapshot>
    val myBeaconActive: StateFlow<Boolean>
    suspend fun refresh(around: GeoPoint): Result<Unit>
    suspend fun setBeacon(active: Boolean, at: GeoPoint?)
}

data class ConnectivityState(
    val internet: Boolean,
    val cellular: Boolean,             // SIM present and in service (SMS possible)
    val meshActive: Boolean,
    val meshPeers: Int,
)

interface ConnectivityMonitor {
    val state: StateFlow<ConnectivityState>
}

// ---------------------------------------------------------------- map (data only; B exports the SahayMap composable)

data class MapViewState(
    val center: GeoPoint? = null,
    val zoom: Double = 15.0,
    val followUser: Boolean = true,
    val darkStyle: Boolean = false,
    val myLocation: LocationFix? = null,
    val pois: List<Poi> = emptyList(),
    val route: Route? = null,
    val riskZones: List<RiskZone> = emptyList(),
    val reports: List<HazardReport> = emptyList(),
    val groups: List<PeopleGroup> = emptyList(),
    val beacons: List<BuddyBeacon> = emptyList(),
    val alerts: List<SahayAlert> = emptyList(),   // drawn as circles when area + radiusM present
)
