package com.sahay.comms.groups

import com.sahay.core.contracts.Channel
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.GroupStatus
import com.sahay.core.contracts.PeopleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSnapshotBuilderTest {

    private val now = 1_760_000_000L

    private fun group(
        id: String, lat: Double = 12.621, lon: Double = 80.193, ageSec: Long = 60, size: Int = 7,
        status: GroupStatus = GroupStatus.AT_SHELTER, channel: Channel = Channel.INTERNET,
    ) = PeopleGroup(id, GeoPoint(lat, lon), size, status, now - ageSec, channel)

    private fun server(vararg groups: PeopleGroup, minSize: Int = 5, fetchedAgoSec: Long = 10) =
        ServerGroups(groups.toList(), minSize, now - fetchedAgoSec)

    @Test fun `nothing known gives an empty snapshot that is not stale`() {
        val snapshot = GroupSnapshotBuilder.build(null, emptyList(), now)
        assertTrue(snapshot.groups.isEmpty())
        assertTrue(snapshot.beacons.isEmpty())
        assertEquals(0, snapshot.nearbyAppUsers)
        assertEquals(5, snapshot.minGroupSize)
        assertNull(snapshot.updatedAtEpochSec)
        assertFalse(snapshot.isStale)
    }

    @Test fun `server and SMS groups in different cells are both kept, newest first`() {
        val a = group("g_1", lat = 12.621, ageSec = 300)
        val b = group("sms_b", lat = 12.640, ageSec = 100, channel = Channel.SMS)
        val snapshot = GroupSnapshotBuilder.build(server(a), listOf(b), now)
        assertEquals(listOf("sms_b", "g_1"), snapshot.groups.map { it.id })
    }

    @Test fun `in one cell the newest wins`() {
        val fromServer = group("g_1", ageSec = 600)
        val fromSms = group("sms_x", ageSec = 120, size = 9, channel = Channel.SMS)
        val snapshot = GroupSnapshotBuilder.build(server(fromServer), listOf(fromSms), now)
        assertEquals(listOf("sms_x"), snapshot.groups.map { it.id })
        assertEquals(9, snapshot.groups.single().size)
    }

    @Test fun `on equal age the server copy beats the SMS copy`() {
        val fromServer = group("g_1", ageSec = 120)
        val fromSms = group("sms_x", ageSec = 120, channel = Channel.SMS)
        val snapshot = GroupSnapshotBuilder.build(server(fromServer), listOf(fromSms), now)
        assertEquals(listOf("g_1"), snapshot.groups.map { it.id })
    }

    @Test fun `coordinates inside the same 110 m cell count as one place`() {
        val a = group("g_1", lat = 12.6210, lon = 80.1930, ageSec = 300)
        val b = group("sms_b", lat = 12.6212, lon = 80.1928, ageSec = 60, channel = Channel.SMS)   // same 3-decimal cell
        val snapshot = GroupSnapshotBuilder.build(server(a), listOf(b), now)
        assertEquals(1, snapshot.groups.size)
    }

    @Test fun `groups older than 30 minutes are dropped, 30 minutes exactly is kept`() {
        val kept = group("kept", lat = 12.60, ageSec = 30 * 60)
        val dropped = group("dropped", lat = 12.61, ageSec = 30 * 60 + 1)
        val snapshot = GroupSnapshotBuilder.build(server(kept, dropped), emptyList(), now)
        assertEquals(listOf("kept"), snapshot.groups.map { it.id })
    }

    @Test fun `snapshot is stale after 15 minutes without news`() {
        val fresh = GroupSnapshotBuilder.build(server(fetchedAgoSec = 15 * 60), emptyList(), now)
        val old = GroupSnapshotBuilder.build(server(fetchedAgoSec = 15 * 60 + 1), emptyList(), now)
        assertFalse(fresh.isStale)
        assertTrue(old.isStale)
    }

    @Test fun `an empty but recent server answer is fresh, not stale`() {
        val snapshot = GroupSnapshotBuilder.build(server(fetchedAgoSec = 5), emptyList(), now)
        assertEquals(now - 5, snapshot.updatedAtEpochSec)
        assertFalse(snapshot.isStale)
    }

    @Test fun `SMS groups alone set the update time and can go stale`() {
        val sms = group("sms_a", ageSec = 20 * 60, channel = Channel.SMS)
        val snapshot = GroupSnapshotBuilder.build(null, listOf(sms), now)
        assertEquals(now - 20 * 60, snapshot.updatedAtEpochSec)
        assertTrue(snapshot.isStale)
        assertEquals(1, snapshot.groups.size)
    }

    @Test fun `minimum group size comes from the server`() {
        assertEquals(3, GroupSnapshotBuilder.build(server(minSize = 3), emptyList(), now).minGroupSize)
    }
}
