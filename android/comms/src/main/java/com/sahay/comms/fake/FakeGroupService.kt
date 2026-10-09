package com.sahay.comms.fake

import com.sahay.core.contracts.BuddyBeacon
import com.sahay.core.contracts.Channel
import com.sahay.core.contracts.GeoPoint
import com.sahay.core.contracts.GroupService
import com.sahay.core.contracts.GroupStatus
import com.sahay.core.contracts.GroupsSnapshot
import com.sahay.core.contracts.PeopleGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeGroupService @Inject constructor() : GroupService {

    private val state = MutableStateFlow(snapshot(beacons = emptyList()))
    private val beaconActive = MutableStateFlow(false)

    override val snapshot: StateFlow<GroupsSnapshot> = state.asStateFlow()
    override val myBeaconActive: StateFlow<Boolean> = beaconActive.asStateFlow()

    override suspend fun refresh(around: GeoPoint): Result<Unit> {
        state.value = snapshot(state.value.beacons)
        return Result.success(Unit)
    }

    override suspend fun setBeacon(active: Boolean, at: GeoPoint?) {
        beaconActive.value = active
        val mine = if (active && at != null) {
            listOf(BuddyBeacon("b_mine", at, System.currentTimeMillis() / 1000, mine = true))
        } else {
            emptyList()
        }
        state.value = state.value.copy(beacons = mine)
    }

    private fun snapshot(beacons: List<BuddyBeacon>): GroupsSnapshot {
        val now = System.currentTimeMillis() / 1000
        return GroupsSnapshot(
            groups = listOf(
                PeopleGroup("g_shelter", GeoPoint(12.6262, 80.1921), 7, GroupStatus.AT_SHELTER, now - 60, Channel.INTERNET),
                PeopleGroup("g_safe", GeoPoint(12.6180, 80.1900), 5, GroupStatus.SAFE_AREA, now - 120, Channel.INTERNET),
                PeopleGroup("g_risk", GeoPoint(12.6200, 80.1990), 6, GroupStatus.RISK_ZONE, now - 180, Channel.INTERNET),
            ),
            beacons = beacons,
            nearbyAppUsers = 0,
            minGroupSize = 5,
            updatedAtEpochSec = now,
            isStale = false,
        )
    }
}
