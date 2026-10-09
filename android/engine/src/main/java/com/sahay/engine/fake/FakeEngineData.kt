package com.sahay.engine.fake

import com.sahay.core.contracts.AlertKeyword
import com.sahay.core.contracts.AlertTemplate
import com.sahay.core.contracts.Embassy
import com.sahay.core.contracts.ForecastDay
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.Incident
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.Phrase
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.Precaution
import com.sahay.core.contracts.RadioStation
import com.sahay.core.contracts.Region
import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone
import com.sahay.core.contracts.Route
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Hard-coded demo data around Mahabalipuram, shared by the Fake* classes of :engine. */
internal object FakeEngineData {

    val center = GeoPoint(12.6208, 80.1945)

    val regions = listOf(
        Region("mahabalipuram", "Mahabalipuram", listOf(80.16, 12.59, 80.21, 12.65)),
        Region("iiitdm-kancheepuram", "IIITDM Kancheepuram", listOf(80.13, 12.82, 80.18, 12.86)),
        Region("chennai-central", "Chennai Central", listOf(80.20, 12.97, 80.30, 13.10)),
    )

    val pois = listOf(
        Poi("poi_s1", PoiType.SHELTER, "Govt Higher Secondary School", "அரசு மேல்நிலைப் பள்ளி",
            GeoPoint(12.6262, 80.1921), "+914427442233", true, 14.0, 400),
        Poi("poi_s2", PoiType.SHELTER, "Cyclone Shelter Kovalam Road", "புயல் பாதுகாப்பு மையம்",
            GeoPoint(12.6171, 80.1890), "+914427442244", true, 11.0, 250),
        Poi("poi_c1", PoiType.CANDIDATE_SHELTER, "Community Hall", "சமுதாயக் கூடம்",
            GeoPoint(12.6235, 80.1968), null, false, 9.0, null),
        Poi("poi_h1", PoiType.HOSPITAL, "Govt Hospital Mahabalipuram", "அரசு மருத்துவமனை",
            GeoPoint(12.6249, 80.1902), "+914427442255", true, 12.0, null),
        Poi("poi_h2", PoiType.HOSPITAL, "Primary Health Centre", "ஆரம்ப சுகாதார நிலையம்",
            GeoPoint(12.6190, 80.1932), "+914427442266", true, 8.0, null),
        Poi("poi_p1", PoiType.POLICE, "Mahabalipuram Police Station", "மகாபலிபுரம் காவல் நிலையம்",
            GeoPoint(12.6226, 80.1913), "100", true, 10.0, null),
    )

    val riskZones = listOf(
        RiskZone(
            id = "rz_coast", name = "Shore Temple low coast", level = RiskLevel.HIGH,
            polygons = listOf(
                listOf(
                    GeoPoint(12.6150, 80.1960), GeoPoint(12.6280, 80.1960),
                    GeoPoint(12.6280, 80.2030), GeoPoint(12.6150, 80.2030),
                    GeoPoint(12.6150, 80.1960),
                ),
            ),
        ),
    )

    /** Five points, about 650 m, from the demo centre to the first shelter. */
    fun fakeRoute(destination: Poi? = pois.first()): Route {
        val points = listOf(
            center, GeoPoint(12.6222, 80.1938), GeoPoint(12.6238, 80.1930),
            GeoPoint(12.6251, 80.1925), destination?.point ?: pois.first().point,
        )
        val distance = 650.0
        return Route(
            points = points,
            distanceM = distance,
            etaMin = kotlin.math.ceil(distance / com.sahay.core.contracts.SahayConfig.WALKING_M_PER_MIN).toInt(),
            destination = destination,
            avoidsRiskZones = true,
            isStraightLine = false,
            warnings = emptyList(),
        )
    }

    fun forecast(today: LocalDate = LocalDate.now()) = listOf(
        ForecastDay(today, 12.4, 22.0, 31.0, "LOW"),
        ForecastDay(today.plusDays(1), 68.0, 48.0, 29.0, "HIGH"),
        ForecastDay(today.plusDays(2), 120.5, 66.0, 28.0, "SEVERE"),
    )

    val precautions = listOf(
        Precaution("p_save_112", 1,
            mapOf("en" to "Save emergency number 112", "de" to "Notrufnummer 112 speichern"),
            mapOf("en" to "Store 112 in your phone. It works without credit.",
                "de" to "Speichern Sie 112 im Telefon. Der Anruf ist kostenlos.")),
        Precaution("p_charge", 1,
            mapOf("en" to "Keep your phone charged", "de" to "Handy geladen halten"),
            mapOf("en" to "Charge your phone and a power bank before heavy rain.",
                "de" to "Laden Sie Handy und Powerbank vor dem Starkregen auf.")),
        Precaution("p_shelter", 2,
            mapOf("en" to "Know your nearest shelter", "de" to "Nächste Notunterkunft kennen"),
            mapOf("en" to "Open the map and check the nearest shelter now.",
                "de" to "Öffnen Sie die Karte und prüfen Sie die nächste Notunterkunft.")),
    )

    val incidents = listOf(
        Incident(LocalDate.of(2023, 12, 4), "cyclone",
            mapOf("en" to "Cyclone Michaung", "de" to "Zyklon Michaung"),
            mapOf("en" to "Heavy rain flooded low roads near the coast for two days.",
                "de" to "Starkregen überflutete zwei Tage lang tiefe Straßen an der Küste."),
            "https://example.org/michaung"),
        Incident(LocalDate.of(2021, 11, 11), "flood",
            mapOf("en" to "Northeast monsoon floods", "de" to "Überschwemmungen im Nordostmonsun"),
            mapOf("en" to "Record rain closed roads around Mahabalipuram.",
                "de" to "Rekordregen sperrte Straßen rund um Mahabalipuram."),
            null),
    )

    fun packInfo(regionId: String, start: LocalDate, end: LocalDate, nowSec: Long): PackInfo {
        val region = regions.firstOrNull { it.id == regionId } ?: regions.first()
        return PackInfo(
            regionId = region.id,
            regionName = region.name,
            packVersion = "fake.1",
            bbox = region.bbox,
            tripStart = start,
            tripEnd = end,
            downloadedAtEpochSec = nowSec,
            forecast = forecast(start),
            historySummary = mapOf(
                "en" to "Heavy rain on about 3 days a year in the last 5 years.",
                "de" to "In den letzten 5 Jahren an etwa 3 Tagen pro Jahr Starkregen.",
            ),
            incidents = incidents,
            precautions = precautions,
            publicKeyB64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            sizeBytes = 25_000_000,
        )
    }

    // ---- alert templates (CONTRACTS §8.1), English only
    private val templateRows = listOf(
        Triple("RAIN_HVY", 1, "Heavy rain expected" to "Heavy rain is expected. Avoid low roads. Keep your phone charged."),
        Triple("RAIN_XHVY", 2, "Very heavy rain expected" to "Very heavy rain is coming. Stay indoors. Avoid underpasses and rivers."),
        Triple("FLD_WATCH", 1, "Flooding possible nearby" to "Flooding is possible in low areas. Know your nearest shelter."),
        Triple("FLD_WARN", 2, "Flooding likely" to "Flooding is likely. Avoid low roads and underpasses. Move valuables up."),
        Triple("FLD_EVAC", 3, "Flooding: go to safety" to "Move to higher ground or a shelter now. Do not walk through flood water."),
        Triple("CYC_WATCH", 1, "Cyclone may affect area" to "A cyclone may affect this area. Follow updates. Prepare to move."),
        Triple("CYC_WARN", 2, "Cyclone warning" to "Stay indoors. Secure your belongings. Keep away from windows."),
        Triple("CYC_LANDFALL", 3, "Cyclone landfall soon" to "Go to a shelter now. Do not wait. Take your phone and documents."),
        Triple("WIND_HIGH", 2, "Strong winds expected" to "Stay away from trees and hoardings. Stay indoors if you can."),
        Triple("SEA_ROUGH", 2, "Rough sea" to "Stay away from the beach. Do not swim or take boats."),
        Triple("COAST_EVAC", 3, "Coastal evacuation ordered" to "Leave the coast now. Follow the route to the nearest shelter."),
        Triple("SURGE", 3, "Storm surge danger" to "Move away from the coast now. Go to higher ground."),
        Triple("LIGHTNING", 1, "Lightning risk" to "Stay indoors. Keep away from open fields and tall trees."),
        Triple("ROAD_CLOSED", 1, "Roads closed" to "Roads are closed in the area. Use another route or wait."),
        Triple("POWER_OUT", 1, "Power cuts expected" to "Charge your phone now. Keep a torch ready."),
        Triple("STAY_INDOORS", 2, "Stay indoors" to "Stay indoors until further notice. Do not go out unless told."),
        Triple("SHELTER_OPEN", 1, "Relief shelters open" to "Relief shelters are open. Open the map to find the nearest one."),
        Triple("BOIL_WATER", 1, "Boil your drinking water" to "Drink only boiled or bottled water. Avoid ice and raw food."),
        Triple("ALL_CLEAR", 0, "Danger has passed" to "The danger has passed. Take care on roads. Follow local advice."),
        Triple("TEST", 0, "Test message" to "This is a test message. No action is needed."),
    )

    val alertTemplates = templateRows.map { (code, severity, text) ->
        AlertTemplate(code, "en", severity, text.first, text.second)
    }

    val alertKeywords = listOf(
        AlertKeyword("FLD_WARN", "en", "flood"),
        AlertKeyword("CYC_WARN", "en", "cyclone"),
        AlertKeyword("CYC_WARN", "hi", "चक्रवात"),
        AlertKeyword("FLD_WARN", "ta", "வெள்ளம்"),
    )

    // ---- phrases (CONTRACTS §8.2): id, category, icon, English, Tamil
    private data class PhraseRow(val id: String, val category: String, val icon: String, val en: String, val ta: String)

    private val phraseRows = listOf(
        PhraseRow("tourist_need_help", "emergency", "help", "I am a tourist. I don't speak Tamil. Please help me.",
            "நான் ஒரு சுற்றுலாப் பயணி. எனக்குத் தமிழ் தெரியாது. தயவுசெய்து உதவுங்கள்."),
        PhraseRow("take_me_to_shelter", "emergency", "shelter", "Please take me to a shelter.", "என்னை பாதுகாப்பு மையத்திற்கு அழைத்துச் செல்லுங்கள்."),
        PhraseRow("where_is_shelter", "directions", "shelter", "Where is the nearest shelter?", "அருகிலுள்ள பாதுகாப்பு மையம் எங்கே?"),
        PhraseRow("where_is_hospital", "directions", "hospital", "Where is the nearest hospital?", "அருகிலுள்ள மருத்துவமனை எங்கே?"),
        PhraseRow("call_police", "emergency", "police", "Please call the police.", "தயவுசெய்து காவல்துறையை அழையுங்கள்."),
        PhraseRow("call_ambulance", "emergency", "ambulance", "Please call an ambulance.", "தயவுசெய்து ஆம்புலன்ஸை அழையுங்கள்."),
        PhraseRow("i_am_injured", "medical", "injury", "I am injured.", "நான் காயமடைந்துள்ளேன்."),
        PhraseRow("i_have_allergy", "medical", "allergy", "I have an allergy.", "எனக்கு ஒவ்வாமை உள்ளது."),
        PhraseRow("i_need_water", "basic", "water", "I need drinking water.", "எனக்கு குடிநீர் வேண்டும்."),
        PhraseRow("i_need_to_charge_phone", "basic", "charge", "I need to charge my phone.", "நான் என் தொலைபேசியை சார்ஜ் செய்ய வேண்டும்."),
        PhraseRow("is_this_road_safe", "directions", "road", "Is this road safe?", "இந்த சாலை பாதுகாப்பானதா?"),
        PhraseRow("where_is_high_ground", "directions", "high_ground", "Where is higher ground?", "உயரமான இடம் எங்கே?"),
        PhraseRow("my_hotel_is", "basic", "hotel", "My hotel is here.", "என் தங்கும் விடுதி இங்கே உள்ளது."),
        PhraseRow("i_am_lost", "directions", "lost", "I am lost.", "நான் வழி தவறிவிட்டேன்."),
        PhraseRow("thank_you", "basic", "thanks", "Thank you.", "நன்றி."),
        PhraseRow("yes", "basic", "yes", "Yes.", "ஆம்."),
        PhraseRow("no", "basic", "no", "No.", "இல்லை."),
    )

    val phrases: List<Phrase> = phraseRows.flatMap {
        listOf(
            Phrase(it.id, "en", it.category, it.en, it.icon),
            Phrase(it.id, "ta", it.category, it.ta, it.icon),
        )
    }

    val embassies = mapOf(
        "DE" to Embassy("DE", "Consulate General of Germany, Chennai", "+914440475600",
            "9 Boat Club Road, Chennai", GeoPoint(13.0236, 80.2527), null),
    )

    val radios = listOf(RadioStation("All India Radio Chennai", "720 kHz AM", "ta"))

    // ---- geometry helpers

    /** Great-circle distance in metres. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = (b.lat - a.lat) * PI / 180
        val dLon = (b.lon - a.lon) * PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Ray-casting point-in-polygon test (ring may be open or closed). */
    fun contains(ring: List<GeoPoint>, p: GeoPoint): Boolean {
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
            ) inside = !inside
            j = i
        }
        return inside
    }
}
