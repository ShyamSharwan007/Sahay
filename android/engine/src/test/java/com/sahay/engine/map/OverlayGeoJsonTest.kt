package com.sahay.engine.map

import com.sahay.core.contracts.AlertSource
import com.sahay.core.contracts.Channel
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.GroupStatus
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.HazardType
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.PeopleGroup
import com.sahay.core.contracts.Poi
import com.sahay.core.contracts.PoiType
import com.sahay.core.contracts.RiskLevel
import com.sahay.core.contracts.RiskZone
import com.sahay.core.contracts.Route
import com.sahay.core.contracts.SahayAlert
import com.sahay.core.contracts.ShelterStatus
import com.sahay.core.contracts.TrustLabel
import com.sahay.core.contracts.Verification
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeoJsonTest {

    private val light = MapPalette.LIGHT
    private val dark = MapPalette.DARK

    private fun features(json: String): List<JsonObject> {
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals("FeatureCollection", root.getValue("type").jsonPrimitive.content)
        return root.getValue("features").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.props() = getValue("properties").jsonObject
    private fun JsonObject.prop(name: String) = props().getValue(name).jsonPrimitive
    private fun JsonObject.geometry() = getValue("geometry").jsonObject

    private val square = listOf(GeoPoint(12.60, 80.18), GeoPoint(12.60, 80.19), GeoPoint(12.61, 80.19), GeoPoint(12.61, 80.18))

    // ------------------------------------------------------------------ risk zones

    @Test fun `high and medium zones follow the design`() {
        val zones = listOf(
            RiskZone("z1", "Low town", RiskLevel.HIGH, listOf(square)),
            RiskZone("z2", null, RiskLevel.MEDIUM, listOf(square, square)),
        )
        val out = features(OverlayGeoJson.riskZones(zones, light))

        assertEquals(3, out.size)
        val high = out[0]
        assertEquals(light.danger, high.prop("color").content)
        assertEquals(0.28, high.prop("fillOpacity").double, 1e-9)
        assertEquals(2.0, high.prop("lineWidth").double, 1e-9)
        val medium = out[1]
        assertEquals(light.warning, medium.prop("color").content)
        assertEquals(0.20, medium.prop("fillOpacity").double, 1e-9)
        assertEquals(0.0, medium.prop("lineWidth").double, 1e-9)
        assertEquals(setOf("z1#0", "z2#0", "z2#1"), out.map { it.prop("id").content }.toSet())
    }

    @Test fun `rings are closed and written as longitude then latitude`() {
        val out = features(OverlayGeoJson.riskZones(listOf(RiskZone("z", null, RiskLevel.HIGH, listOf(square))), light))
        val ring = out.single().geometry().getValue("coordinates").jsonArray.single().jsonArray

        assertEquals(5, ring.size)
        assertEquals(ring.first(), ring.last())
        assertEquals(80.18, ring[0].jsonArray[0].jsonPrimitive.double, 1e-9)    // lon first
        assertEquals(12.60, ring[0].jsonArray[1].jsonPrimitive.double, 1e-9)
    }

    @Test fun `degenerate rings and empty input give no features`() {
        val zones = listOf(
            RiskZone("a", null, RiskLevel.HIGH, listOf(emptyList())),
            RiskZone("b", null, RiskLevel.HIGH, listOf(listOf(GeoPoint(1.0, 1.0)))),
            RiskZone("c", null, RiskLevel.HIGH, listOf(listOf(GeoPoint(1.0, 1.0), GeoPoint(2.0, 2.0)))),
        )
        assertTrue(features(OverlayGeoJson.riskZones(zones, light)).isEmpty())
        assertTrue(features(OverlayGeoJson.riskZones(emptyList(), light)).isEmpty())
    }

    // ------------------------------------------------------------------ alert circles

    private fun alert(id: String, severity: Int, area: GeoPoint? = GeoPoint(12.62, 80.19), radius: Int? = 2000) = SahayAlert(
        id = id, templateCode = "FLD_EVAC", severity = severity, area = area, radiusM = radius, issuedAtEpochSec = 0,
        isSimulation = false, title = "t", body = "b", titleEn = "t", bodyEn = "b", originalText = null,
        source = AlertSource.INTERNET, verification = Verification.VERIFIED_OFFICIAL, receivedAtEpochSec = 0, read = false,
    )

    @Test fun `alert circles are 64-point polygons in the severity colour`() {
        val out = features(OverlayGeoJson.alertCircles(listOf(alert("a", 3), alert("b", 1)), light))

        assertEquals(2, out.size)
        assertEquals(light.danger, out[0].prop("color").content)
        assertEquals(light.watch, out[1].prop("color").content)
        assertEquals(65, out[0].geometry().getValue("coordinates").jsonArray.single().jsonArray.size)
    }

    @Test fun `alerts without an area or radius are not drawn`() {
        val out = features(OverlayGeoJson.alertCircles(listOf(alert("a", 2, area = null), alert("b", 2, radius = null), alert("c", 2, radius = 0)), light))
        assertTrue(out.isEmpty())
    }

    // ------------------------------------------------------------------ route

    private fun route(points: List<GeoPoint>, straight: Boolean = false) = Route(
        points = points, distanceM = 650.0, etaMin = 9, destination = null,
        avoidsRiskZones = true, isStraightLine = straight, warnings = emptyList(),
    )

    @Test fun `route is one line with casing colour and a dashed flag for straight-line fallbacks`() {
        val line = features(OverlayGeoJson.route(route(square), light)).single()
        assertEquals("LineString", line.geometry().getValue("type").jsonPrimitive.content)
        assertEquals(light.primary, line.prop("color").content)
        assertEquals(light.casing, line.prop("casing").content)
        assertFalse(line.prop("dashed").boolean)

        val dashed = features(OverlayGeoJson.route(route(square, straight = true), dark)).single()
        assertTrue(dashed.prop("dashed").boolean)
        assertEquals(dark.casing, dashed.prop("casing").content)
    }

    @Test fun `no route or a single point draws nothing`() {
        assertTrue(features(OverlayGeoJson.route(null, light)).isEmpty())
        assertTrue(features(OverlayGeoJson.route(route(listOf(GeoPoint(1.0, 1.0))), light)).isEmpty())
        assertTrue(features(OverlayGeoJson.route(route(emptyList()), light)).isEmpty())
    }

    // ------------------------------------------------------------------ places

    private fun poi(id: String, type: PoiType, status: ShelterStatus = ShelterStatus.UNKNOWN) = Poi(
        id = id, type = type, name = id, nameTa = null, point = GeoPoint(12.62, 80.19), phone = null,
        isOfficial = true, elevationM = null, capacity = null, status = status,
    )

    @Test fun `each place type has its own icon and full or closed shelters are greyed`() {
        val out = features(
            OverlayGeoJson.pois(
                listOf(
                    poi("s1", PoiType.SHELTER, ShelterStatus.OPEN),
                    poi("s2", PoiType.SHELTER, ShelterStatus.FULL),
                    poi("s3", PoiType.CANDIDATE_SHELTER, ShelterStatus.CLOSED),
                    poi("s4", PoiType.SHELTER),
                    poi("h1", PoiType.HOSPITAL, ShelterStatus.FULL),     // status means nothing for a hospital
                    poi("p1", PoiType.POLICE),
                ),
            ),
        )
        val icons = out.associate { it.prop("id").content to it.prop("icon").content }

        assertEquals("poi_shelter", icons["s1"])
        assertEquals("poi_shelter_off", icons["s2"])
        assertEquals("poi_candidate_shelter_off", icons["s3"])
        assertEquals("poi_shelter", icons["s4"])
        assertEquals("poi_hospital", icons["h1"])
        assertEquals("poi_police", icons["p1"])
    }

    @Test fun `every icon id used by places exists in the marker set`() {
        val known = setOf(
            "poi_shelter", "poi_shelter_off", "poi_candidate_shelter", "poi_candidate_shelter_off", "poi_hospital", "poi_police",
        )
        PoiType.entries.forEach { type ->
            assertTrue(MapIcons.iconId(type, off = false) in known)
            if (type == PoiType.SHELTER || type == PoiType.CANDIDATE_SHELTER) assertTrue(MapIcons.iconId(type, off = true) in known)
        }
    }

    // ------------------------------------------------------------------ reports

    private fun report(id: String, label: TrustLabel) = HazardReport(
        id = id, type = HazardType.FLOOD, point = GeoPoint(12.62, 80.19), note = null, photoUrl = null,
        createdAtEpochSec = 0, trustScore = 0.5, label = label, mine = false, channel = Channel.INTERNET, pendingSync = false,
    )

    @Test fun `reports are coloured by trust and unconfirmed ones are hollow`() {
        val out = features(
            OverlayGeoJson.reports(listOf(report("v", TrustLabel.VERIFIED), report("l", TrustLabel.LIKELY), report("u", TrustLabel.UNCONFIRMED)), light),
        ).associateBy { it.prop("id").content }

        assertEquals(light.danger, out.getValue("v").prop("fill").content)
        assertEquals(1.0, out.getValue("v").prop("fillOpacity").double, 1e-9)
        assertEquals(light.warning, out.getValue("l").prop("fill").content)
        assertEquals(0.0, out.getValue("u").prop("fillOpacity").double, 1e-9)
        assertEquals(light.muted, out.getValue("u").prop("stroke").content)
    }

    // ------------------------------------------------------------------ groups

    private fun group(id: String, size: Int, status: GroupStatus) =
        PeopleGroup(id, GeoPoint(12.62, 80.19), size, status, 0, Channel.INTERNET)

    @Test fun `groups show their size and take the colour of their status`() {
        val out = features(
            OverlayGeoJson.groups(
                listOf(group("a", 7, GroupStatus.AT_SHELTER), group("b", 5, GroupStatus.SAFE_AREA), group("c", 12, GroupStatus.RISK_ZONE)),
                light,
            ),
        ).associateBy { it.prop("id").content }

        assertEquals("7", out.getValue("a").prop("label").content)
        assertEquals(light.safe, out.getValue("a").prop("color").content)
        assertEquals(light.primary, out.getValue("b").prop("color").content)
        assertEquals(light.danger, out.getValue("c").prop("color").content)
        assertEquals(light.onStatus, out.getValue("c").prop("textColor").content)
    }

    @Test fun `group circle grows with size but stays within limits`() {
        val sizes = listOf(1, 3, 5, 10, 50, 100, 1_000, Int.MAX_VALUE)
        val radii = sizes.map { OverlayGeoJson.groupRadiusDp(it) }
        assertEquals(radii.sorted(), radii)
        assertTrue(radii.all { it in 12.0..36.0 })
        assertTrue(OverlayGeoJson.groupRadiusDp(100) > OverlayGeoJson.groupRadiusDp(5))
        assertEquals(OverlayGeoJson.groupRadiusDp(0), OverlayGeoJson.groupRadiusDp(1), 1e-9)      // nonsense sizes are clamped
    }

    @Test fun `big groups are labelled 99+`() {
        val label = features(OverlayGeoJson.groups(listOf(group("a", 100, GroupStatus.SAFE_AREA)), light)).single().prop("label").content
        assertEquals("99+", label)
        assertEquals("99", features(OverlayGeoJson.groups(listOf(group("a", 99, GroupStatus.SAFE_AREA)), light)).single().prop("label").content)
    }

    // ------------------------------------------------------------------ my location

    @Test fun `my location is a dot plus an accuracy circle`() {
        val fix = LocationFix(GeoPoint(12.62, 80.19), accuracyM = 25f, timeMs = 0)

        val dot = features(OverlayGeoJson.myDot(fix, light)).single()
        assertEquals(light.info, dot.prop("color").content)
        assertEquals(80.19, dot.geometry().getValue("coordinates").jsonArray[0].jsonPrimitive.double, 1e-9)

        val accuracy = features(OverlayGeoJson.myAccuracy(fix, light)).single()
        assertEquals(65, accuracy.geometry().getValue("coordinates").jsonArray.single().jsonArray.size)
    }

    @Test fun `no fix or no accuracy draws nothing for that part`() {
        assertTrue(features(OverlayGeoJson.myDot(null, light)).isEmpty())
        assertTrue(features(OverlayGeoJson.myAccuracy(null, light)).isEmpty())
        val noAccuracy = LocationFix(GeoPoint(12.62, 80.19), accuracyM = 0f, timeMs = 0)
        assertTrue(features(OverlayGeoJson.myAccuracy(noAccuracy, light)).isEmpty())
        assertEquals(1, features(OverlayGeoJson.myDot(noAccuracy, light)).size)
        assertTrue(features(OverlayGeoJson.myAccuracy(LocationFix(GeoPoint(1.0, 1.0), Float.NaN, 0), light)).isEmpty())
    }

    @Test fun `a new palette changes the output so the diff re-pushes it`() {
        val groups = listOf(group("a", 7, GroupStatus.AT_SHELTER))
        assertFalse(OverlayGeoJson.groups(groups, light) == OverlayGeoJson.groups(groups, dark))
    }
}
