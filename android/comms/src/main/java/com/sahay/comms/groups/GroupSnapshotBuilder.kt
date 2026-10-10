package com.sahay.comms.groups

import com.sahay.core.contracts.Channel
import com.sahay.core.contracts.GroupsSnapshot
import com.sahay.core.contracts.PeopleGroup
import kotlin.math.roundToLong

/** What the last successful `GET /groups` returned. */
internal data class ServerGroups(
    val groups: List<PeopleGroup>,
    val minGroupSize: Int,
    val fetchedAtEpochSec: Long,
)

/**
 * Merges the server's groups with the `SH1*G*` groups from the SMS cache into one [GroupsSnapshot].
 * Pure function of its inputs, so staleness and expiry are testable with a fixed "now".
 */
internal object GroupSnapshotBuilder {
    const val STALE_AFTER_SEC = 15 * 60L
    const val DROP_AFTER_SEC = 30 * 60L
    const val DEFAULT_MIN_GROUP_SIZE = 5

    fun build(server: ServerGroups?, smsGroups: List<PeopleGroup>, nowSec: Long): GroupsSnapshot {
        val groups = (server?.groups.orEmpty() + smsGroups)
            .filter { nowSec - it.lastSeenEpochSec <= DROP_AFTER_SEC }
            .groupBy { cellOf(it) }
            .values
            .map { sameCell -> sameCell.maxWith(NEWEST_FIRST) }
            .sortedByDescending { it.lastSeenEpochSec }
        // "Updated" is the newest thing we know: the last server answer (even an empty one) or a fresh SMS group.
        val updatedAt = listOfNotNull(server?.fetchedAtEpochSec, groups.maxOfOrNull { it.lastSeenEpochSec }).maxOrNull()
        return GroupsSnapshot(
            groups = groups,
            beacons = emptyList(),
            nearbyAppUsers = 0,
            minGroupSize = server?.minGroupSize ?: DEFAULT_MIN_GROUP_SIZE,
            updatedAtEpochSec = updatedAt,
            isStale = updatedAt != null && nowSec - updatedAt > STALE_AFTER_SEC,
        )
    }

    /** ~110 m cell: the wire and the server both use 3 decimals. */
    private fun cellOf(group: PeopleGroup): Pair<Long, Long> =
        (group.point.lat * 1000).roundToLong() to (group.point.lon * 1000).roundToLong()

    /** Newest wins; on a tie the server's answer (internet) beats an SMS copy. */
    private val NEWEST_FIRST = compareBy<PeopleGroup>({ it.lastSeenEpochSec }, { it.channel == Channel.INTERNET })
}
