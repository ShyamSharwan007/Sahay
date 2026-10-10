package com.sahay.comms.reports

import com.sahay.comms.alerts.await
import com.sahay.comms.reports.ReportHarness.Companion.NOW
import com.sahay.comms.reports.ReportHarness.Companion.acceptedResponse
import com.sahay.comms.reports.ReportHarness.Companion.errorResponse
import com.sahay.comms.reports.ReportHarness.Companion.serverReport
import com.sahay.core.contracts.AlertSource
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.HazardType
import com.sahay.core.contracts.LocationFix
import com.sahay.core.contracts.SahayAlert
import com.sahay.core.contracts.TrustLabel
import com.sahay.core.contracts.Verification
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealReportRepositoryTest {

    private var harness: ReportHarness? = null

    private fun harness(online: Boolean = true, pack: String? = null, retryDelayMs: Long = 100) =
        ReportHarness(
            RuntimeEnvironment.getApplication(), online, retryDelayMs,
            pack?.let(ReportHarness.Companion::pack),
        ).also { harness = it }

    @After fun tearDown() { harness?.close() }

    // ------------------------------------------------------------------ online

    @Test fun `online submit is sent at once and shows the server's trust`() = runBlocking {
        val h = harness()
        val report = h.repository.submit(HazardType.FLOOD, h.spot, "knee-deep water", null)

        assertFalse(report.pendingSync)
        assertEquals(0.72, report.trustScore, 1e-9)
        assertEquals(TrustLabel.VERIFIED, report.label)
        assertTrue(report.mine)

        assertEquals(1, h.posts.size)
        val sent = h.posts.single()
        assertEquals("Bearer tok-1", sent.authorization)
        with(sent.body) {
            assertEquals("FL", getValue("type").jsonPrimitive.content)
            assertEquals(12.6208, getValue("lat").jsonPrimitive.double, 1e-9)
            assertEquals(80.1945, getValue("lon").jsonPrimitive.double, 1e-9)
            assertEquals(12.6208, getValue("reporterLat").jsonPrimitive.double, 1e-9)    // the phone's own fix
            assertEquals("knee-deep water", getValue("note").jsonPrimitive.content)
            assertEquals(JsonNull, getValue("photoBase64"))
            assertEquals(NOW, getValue("createdAt").jsonPrimitive.content.toLong())
            assertEquals("INTERNET", getValue("channel").jsonPrimitive.content)
        }

        h.awaitNoPending()
        val listed = h.repository.reports.await { it.size == 1 }.single()
        assertEquals(report.id, listed.id)                       // the id does not change when the report is sent
        assertFalse(listed.pendingSync)
    }

    @Test fun `a trusted flood report is passed to routing as a blocked point`() = runBlocking {
        val h = harness()
        h.repository.submit(HazardType.FLOOD, h.spot, null, null)

        h.waitUntil("blocked point") { h.routing.latest == listOf(h.spot) }
    }

    @Test fun `routing is told about no blocked points at start`() = runBlocking {
        val h = harness()
        h.repository

        h.waitUntil("first routing update") { h.routing.calls.isNotEmpty() }
        assertEquals(emptyList<GeoPoint>(), h.routing.latest)
    }

    @Test fun `a photo is uploaded after the report and its state is kept`() = runBlocking {
        val h = harness()
        val photo = byteArrayOf(1, 2, 3, 4)
        val report = h.repository.submit(HazardType.FLOOD, h.spot, null, photo)

        assertEquals(JsonNull, h.posts.single().body.getValue("photoBase64"))   // the report itself carries no photo
        val upload = h.uploads.single()
        assertEquals("/api/v1/reports/r_9f2/photo", upload.path)
        assertTrue(upload.contentType!!.startsWith("multipart/form-data"))
        assertEquals("Bearer tok-1", upload.authorization)
        assertEquals(1, h.photoDir.listFiles().orEmpty().size)                    // kept for the thumbnail
        val row = h.db.reportDao().find(report.id)!!
        assertTrue(row.photoUploaded)
        assertEquals("pending", row.reviewStatus)
        assertEquals("pending", h.repository.reports.await { it.isNotEmpty() }.single().reviewStatus)
    }

    @Test fun `a failed photo upload is retried and the report stays sent`() = runBlocking {
        val h = harness()
        h.photoResponse = { n -> if (n == 1) errorResponse(500) else ReportHarness.photoAccepted() }
        val report = h.repository.submit(HazardType.FLOOD, h.spot, null, byteArrayOf(1, 2, 3))

        assertEquals("SENT", h.db.reportDao().find(report.id)!!.status)
        h.waitUntil("photo uploaded on retry") { h.db.reportDao().find(report.id)!!.photoUploaded }
        assertEquals(2, h.uploads.size)
        assertEquals(1, h.posts.size)                                             // the report was not sent again
    }

    @Test fun `a photo the server refuses for good is given up`() = runBlocking {
        val h = harness()
        h.photoResponse = { errorResponse(400) }
        val report = h.repository.submit(HazardType.FLOOD, h.spot, null, byteArrayOf(1, 2, 3))

        assertTrue(h.db.reportDao().find(report.id)!!.photoUploaded)
        assertEquals(1, h.uploads.size)
    }

    @Test fun `a photo taken offline goes up after the report when the connection returns`() = runBlocking {
        val h = harness(online = false)
        val report = h.repository.submit(HazardType.FLOOD, h.spot, null, byteArrayOf(1, 2, 3))
        assertEquals(1, h.photoDir.listFiles().orEmpty().size)
        assertTrue(h.uploads.isEmpty())

        h.goOnline()
        h.waitUntil("photo uploaded") { h.db.reportDao().find(report.id)!!.photoUploaded }
        assertEquals(1, h.posts.size)
    }

    // ------------------------------------------------------------------ offline → pending → synced

    @Test fun `offline submit is saved as pending with a provisional trust and nothing is sent`() = runBlocking {
        val h = harness(online = false)
        val report = h.repository.submit(HazardType.ROAD_BLOCKED, h.spot, "tree down", null)

        assertTrue(report.pendingSync)
        assertEquals(0.325, report.trustScore, 1e-9)            // P = 1 (fix at the spot), C = O = E = 0, H = 0.5
        assertEquals(TrustLabel.UNCONFIRMED, report.label)
        assertTrue(report.mine)
        assertEquals(1, h.repository.pendingCount.await { it == 1 })
        assertTrue(h.repository.reports.await { it.isNotEmpty() }.single().pendingSync)
        delay(200)
        assertTrue(h.posts.isEmpty())
    }

    @Test fun `a pending report is sent when the connection returns`() = runBlocking {
        val h = harness(online = false)
        val queued = h.repository.submit(HazardType.FLOOD, h.spot, null, null)
        assertTrue(queued.pendingSync)
        assertEquals(1, h.repository.pendingCount.await { it == 1 })

        h.goOnline()

        h.awaitNoPending()
        assertEquals(1, h.posts.size)
        val synced = h.repository.reports.await { list -> list.size == 1 && !list.single().pendingSync }.single()
        assertEquals(queued.id, synced.id)
        assertEquals(0.72, synced.trustScore, 1e-9)               // the server's score replaced the provisional one
        assertEquals(TrustLabel.VERIFIED, synced.label)
    }

    @Test fun `pending reports are sent oldest first and only once`() = runBlocking {
        val h = harness(online = false)
        h.repository.submit(HazardType.FLOOD, h.spot, "first", null)
        h.db.reportDao().insert(h.db.reportDao().pending().first().copy(localId = "older", note = "older", createdAtEpochSec = NOW - 60))

        h.goOnline()
        h.awaitNoPending()
        h.goOnline(false)
        h.goOnline()
        delay(300)

        assertEquals(listOf("older", "first"), h.posts.map { it.body.getValue("note").jsonPrimitive.content })
    }

    @Test fun `reports left from the previous run are sent when the app starts online`() = runBlocking {
        val h = harness(online = true)
        h.db.reportDao().insert(entity("left-over"))

        h.repository                                               // start

        h.waitUntil("the left-over report is posted") { h.posts.size == 1 }
        h.awaitNoPending()
        assertEquals(0, h.repository.pendingCount.await { it == 0 })
    }

    @Test fun `a failing server keeps the report pending and it is retried`() = runBlocking {
        val h = harness(retryDelayMs = 100)
        h.postResponse = { n -> if (n < 3) errorResponse(503) else acceptedResponse() }

        val report = h.repository.submit(HazardType.FLOOD, h.spot, null, null)

        assertTrue(report.pendingSync)
        h.awaitNoPending()
        assertEquals(3, h.posts.size)
    }

    @Test fun `rate limiting (429) and timeouts (408) are retried, not given up on`() = runBlocking {
        val h = harness()
        h.postResponse = { n -> when (n) { 1 -> errorResponse(429); 2 -> errorResponse(408); else -> acceptedResponse() } }

        h.repository.submit(HazardType.FLOOD, h.spot, null, null)

        h.awaitNoPending()
        assertEquals(3, h.posts.size)
    }

    @Test fun `a dropped connection keeps the report pending`() = runBlocking {
        val h = harness()
        h.postResponse = { n ->
            if (n == 1) mockwebserver3.MockResponse.Builder().onRequestStart(mockwebserver3.SocketEffect.CloseSocket()).build()
            else acceptedResponse()
        }

        val report = h.repository.submit(HazardType.FLOOD, h.spot, null, null)

        assertTrue(report.pendingSync)
        h.awaitNoPending()
    }

    @Test fun `a report the server refuses for good is marked failed and not retried`() = runBlocking {
        val h = harness()
        h.postResponse = { errorResponse(400) }

        val report = h.repository.submit(HazardType.FLOOD, GeoPoint(48.0, 2.0), null, null)

        assertFalse(report.pendingSync)
        h.awaitNoPending()
        delay(400)
        assertEquals(1, h.posts.size)
        assertEquals(ReportStatus.FAILED.name, h.db.reportDao().find(report.id)!!.status)
        assertEquals(1, h.repository.reports.await { it.isNotEmpty() }.size)      // still visible to its author
    }

    @Test fun `an expired login token is refreshed once`() = runBlocking {
        val h = harness()
        h.postResponse = { n -> if (n == 1) errorResponse(401) else acceptedResponse() }

        val report = h.repository.submit(HazardType.FLOOD, h.spot, null, null)

        assertFalse(report.pendingSync)
        assertEquals(listOf("Bearer tok-1", "Bearer tok-2"), h.posts.map { it.authorization })
    }

    @Test fun `signed out users keep their reports pending without any request`() = runBlocking {
        val h = harness()
        h.auth.token = null

        val report = h.repository.submit(HazardType.FLOOD, h.spot, null, null)

        assertTrue(report.pendingSync)
        assertTrue(h.posts.isEmpty())
    }

    @Test fun `a 401 that a refresh cannot fix leaves the report pending`() = runBlocking {
        val h = harness(retryDelayMs = 60_000)
        h.auth.refreshed = "tok-1"                                 // same token again: nothing better to try
        h.postResponse = { errorResponse(401) }

        val report = h.repository.submit(HazardType.FLOOD, h.spot, null, null)

        assertTrue(report.pendingSync)
        assertEquals(1, h.posts.size)
    }

    // ------------------------------------------------------------------ what is sent

    @Test fun `without a fix the reporter position is sent as 0,0`() = runBlocking {
        val h = harness()
        h.lastFix.value = null

        val report = h.repository.submit(HazardType.FLOOD, h.spot, null, null)

        with(h.posts.single().body) {
            assertEquals(0.0, getValue("reporterLat").jsonPrimitive.double, 0.0)
            assertEquals(0.0, getValue("reporterLon").jsonPrimitive.double, 0.0)
        }
        assertTrue(report.mine)
    }

    @Test fun `an old fix is not used as the reporter position`() = runBlocking {
        val h = harness()
        h.lastFix.value = LocationFix(GeoPoint(13.0, 80.3), 5f, (NOW - 11 * 60) * 1000)

        h.repository.submit(HazardType.FLOOD, h.spot, null, null)

        assertEquals(0.0, h.posts.single().body.getValue("reporterLat").jsonPrimitive.double, 0.0)
    }

    @Test fun `notes are trimmed and cut to 140 characters, blank notes become null`() = runBlocking {
        val h = harness()
        h.repository.submit(HazardType.FLOOD, h.spot, "  " + "x".repeat(200) + "  ", null)
        h.repository.submit(HazardType.FLOOD, h.spot, "   ", null)

        assertEquals("x".repeat(140), h.posts[0].body.getValue("note").jsonPrimitive.content)
        assertEquals(JsonNull, h.posts[1].body.getValue("note"))
    }

    @Test fun `an impossible location is saved as failed and never sent`() = runBlocking {
        val h = harness()

        val report = h.repository.submit(HazardType.FLOOD, GeoPoint(Double.NaN, 80.0), null, null)

        assertFalse(report.pendingSync)
        assertTrue(h.posts.isEmpty())
        assertEquals(0, h.repository.pendingCount.value)
    }

    @Test fun `submit never throws, even if the database is broken`() = runBlocking {
        val h = harness(online = false)
        h.daoOverride = mockk {
            every { observeAll() } returns flowOf(emptyList())
            every { observePendingCount() } returns flowOf(0)
            coEvery { insert(any()) } throws IllegalStateException("disk full")
            coEvery { find(any()) } throws IllegalStateException("disk full")
            coEvery { pending() } returns emptyList()
        }

        val report = h.repository.submit(HazardType.FLOOD, h.spot, "still works", null)

        assertTrue(report.pendingSync)
        assertEquals("still works", report.note)
    }

    // ------------------------------------------------------------------ reading reports

    @Test fun `reports from the server are listed and flood and road reports that look real block routes`() = runBlocking {
        val h = harness()
        val far = GeoPoint(12.63, 80.20)
        h.reportsJson = "[" + listOf(
            serverReport("s1", "FL", h.spot, trust = 0.55, label = "LIKELY"),
            serverReport("s2", "RB", far, trust = 0.85, label = "VERIFIED"),
            serverReport("s3", "FL", GeoPoint(12.64, 80.19), trust = 0.2, label = "UNCONFIRMED"),
            serverReport("s4", "PL", GeoPoint(12.60, 80.18), trust = 0.9, label = "VERIFIED"),
        ).joinToString(",") + "]"

        h.repository

        val list = h.repository.reports.await { it.size == 4 }
        assertEquals(listOf("s1", "s2", "s3", "s4"), list.map { it.id }.sorted())
        h.waitUntil("blocked points") { h.routing.latest?.toSet() == setOf(h.spot, far) }
    }

    @Test fun `the list is newest first and drops reports older than three hours`() = runBlocking {
        val h = harness()
        h.reportsJson = "[" + listOf(
            serverReport("old", ageSec = 3 * 3600 + 60),
            serverReport("mid", ageSec = 1_000),
            serverReport("new", ageSec = 10),
        ).joinToString(",") + "]"

        h.repository

        assertEquals(listOf("new", "mid"), h.repository.reports.await { it.size == 2 }.map { it.id })
    }

    @Test fun `a report of mine that the server lists is shown once, under its local id, with the server's trust`() = runBlocking {
        val h = harness()
        val mine = h.repository.submit(HazardType.FLOOD, h.spot, null, null)       // server id r_9f2

        h.reportsJson = "[" + serverReport("r_9f2", trust = 0.9, label = "VERIFIED", mine = true, ageSec = 0) + "]"
        assertTrue(h.repository.refresh().isSuccess)

        val list = h.repository.reports.await { it.single().trustScore == 0.9 }
        assertEquals(1, list.size)
        assertEquals(mine.id, list.single().id)
        assertTrue(list.single().mine)
        assertFalse(list.single().pendingSync)
    }

    @Test fun `my pending report stays in the list when the server list does not have it`() = runBlocking {
        val h = harness(online = false)
        h.repository.submit(HazardType.FLOOD, h.spot, null, null)
        h.postResponse = { errorResponse(503) }
        h.goOnline()
        h.reportsJson = "[" + serverReport("s1", "RB", GeoPoint(12.63, 80.2)) + "]"
        h.repository.refresh()

        val list = h.repository.reports.await { it.size == 2 }
        assertEquals(1, list.count { it.mine && it.pendingSync })
        assertEquals(1, list.count { !it.mine })
    }

    @Test fun `refresh asks for the active region and the last 180 minutes`() = runBlocking {
        val h = harness(pack = "mahabalipuram")

        assertTrue(h.repository.refresh().isSuccess)

        val url = h.getUrls.last()
        assertTrue(url, url.contains("regionId=mahabalipuram") && url.contains("sinceMin=180"))
    }

    @Test fun `refresh without a pack asks for all regions, and sends the login token`() = runBlocking {
        val h = harness()

        assertTrue(h.repository.refresh().isSuccess)

        assertFalse(h.getUrls.last(), h.getUrls.last().contains("regionId"))
        assertEquals("Bearer tok-1", h.getAuthorizations.last())
    }

    @Test fun `a failed refresh reports the failure and keeps what was already shown`() = runBlocking {
        val h = harness()
        h.reportsJson = "[" + serverReport("s1") + "]"
        h.repository.reports.await { it.size == 1 }
        h.getCode = 500

        val result = h.repository.refresh()

        assertTrue(result.isFailure)
        assertEquals(listOf("s1"), h.repository.reports.value.map { it.id })
    }

    @Test fun `refresh while offline fails at once without a request`() = runBlocking {
        val h = harness(online = false)

        val result = h.repository.refresh()

        assertTrue(result.isFailure)
        assertTrue(h.getUrls.isEmpty())
    }

    @Test fun `one malformed or unknown report does not hide the others`() = runBlocking {
        val h = harness()
        h.reportsJson = "[" + listOf(
            serverReport("good"),
            """{"id":"broken","type":"FL"}""",
            serverReport("unknown", type = "ZZ"),
            "\"junk\"",
        ).joinToString(",") + "]"

        h.repository

        assertEquals(listOf("good"), h.repository.reports.await { it.isNotEmpty() }.map { it.id })
    }

    @Test fun `routing hears about every change, including the all-clear`() = runBlocking {
        val h = harness()
        h.reportsJson = "[" + serverReport("s1", "FL", h.spot, label = "LIKELY") + "]"
        h.repository
        h.waitUntil("blocked") { h.routing.latest == listOf(h.spot) }

        h.reportsJson = "[]"
        h.repository.refresh()

        h.waitUntil("cleared") { h.routing.latest == emptyList<GeoPoint>() }
    }

    // ------------------------------------------------------------------ provisional trust through the repository

    @Test fun `a queued report is scored with nearby reports and the active official alert`() = runBlocking {
        val h = harness()
        h.reportsJson = "[" + List(3) { serverReport("s$it", "FL", h.spot, trust = 0.3, label = "UNCONFIRMED") }.joinToString(",") + "]"
        h.repository.reports.await { it.size == 3 }
        h.alerts.value = listOf(
            SahayAlert(
                "a1", "FLD_EVAC", 3, h.spot, 2_000, NOW - 600, true, "t", "b", "t", "b", null,
                AlertSource.INTERNET, Verification.VERIFIED_OFFICIAL, NOW - 600, false,
            ),
        )
        h.goOnline(false)

        val report = h.repository.submit(HazardType.FLOOD, h.spot, null, null)

        // P = 1, C = 1, O = 1, E = 0, H = 0.5, D = 1 → 0.25 + 0.30 + 0.20 + 0.075
        assertEquals(0.825, report.trustScore, 1e-9)
        assertEquals(TrustLabel.VERIFIED, report.label)
        assertTrue(report.pendingSync)
        h.waitUntil("blocked point") { h.routing.latest?.contains(h.spot) == true }
    }

    @Test fun `an old queued report loses trust as time passes`() = runBlocking {
        val h = harness(online = false)
        h.db.reportDao().insert(entity("hour-old", createdAt = NOW - 3_600))

        val report = h.repository.reports.await { it.isNotEmpty() }.single()

        assertEquals(0.325 / Math.E, report.trustScore, 1e-9)
        assertNotEquals(TrustLabel.VERIFIED, report.label)
    }

    @Test fun `finished reports older than two days are cleaned up, pending ones never`() = runBlocking {
        val h = harness()
        h.db.reportDao().insert(entity("sent-old", status = ReportStatus.SENT, createdAt = NOW - 49 * 3600))
        h.db.reportDao().insert(entity("pending-old", status = ReportStatus.PENDING, createdAt = NOW - 49 * 3600))
        h.postResponse = { errorResponse(503) }

        h.repository.refresh()

        assertNull(h.db.reportDao().find("sent-old"))
        assertTrue(h.db.reportDao().find("pending-old") != null)
    }

    // ------------------------------------------------------------------ helpers

    private fun entity(
        id: String,
        status: ReportStatus = ReportStatus.PENDING,
        createdAt: Long = NOW,
    ) = ReportEntity(
        localId = id, serverId = null, type = "FL", lat = 12.6208, lon = 80.1945, reporterLat = 12.6208, reporterLon = 80.1945,
        note = id, photoPath = null, createdAtEpochSec = createdAt, status = status.name, channel = "INTERNET",
        serverTrust = null, serverPhotoUrl = null,
    )
}
