package com.sahay.comms.sms

import com.sahay.comms.wire.WireMessage
import com.sahay.core.contracts.Channel
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.PeopleGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small in-memory cache of verified `SH1*G*` group messages received by SMS, for the GroupService.
 * One entry per ~110 m cell (the wire has 3 decimals); the newest message for a cell wins.
 */
@Singleton
class GroupWireCache @Inject constructor() {

    private val state = MutableStateFlow<List<PeopleGroup>>(emptyList())
    val groups: StateFlow<List<PeopleGroup>> = state.asStateFlow()

    fun put(message: WireMessage.Group) {
        val group = PeopleGroup(
            id = "sms_${message.lat}_${message.lon}",
            point = GeoPoint(message.lat, message.lon),
            size = message.size,
            status = message.status,
            lastSeenEpochSec = message.timestamp,
            channel = Channel.SMS,
        )
        state.update { current ->
            val existing = current.firstOrNull { it.id == group.id }
            if (existing != null && existing.lastSeenEpochSec > group.lastSeenEpochSec) {
                current
            } else {
                (current.filterNot { it.id == group.id } + group)
                    .sortedByDescending { it.lastSeenEpochSec }
                    .take(MAX_GROUPS)
            }
        }
    }

    private companion object {
        const val MAX_GROUPS = 32
    }
}
