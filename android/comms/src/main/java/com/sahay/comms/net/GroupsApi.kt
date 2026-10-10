package com.sahay.comms.net

import kotlinx.serialization.Serializable
import java.io.IOException

/** The presence and group part of the REST API (docs/CONTRACTS.md §3). Every call may throw [IOException] ([ApiException] for HTTP errors). */
interface GroupsApi {
    /** `POST /presence`. Needs a Firebase ID token. Not retried: the next heartbeat replaces it anyway. */
    suspend fun postPresence(request: PresenceRequest, idToken: String)

    /** `GET /groups?lat=&lon=&radiusM=`. The token is optional. Beacons are cut in this build and not read. */
    suspend fun groups(lat: Double, lon: Double, radiusM: Int, idToken: String?): GroupsDto
}

/** Body of `POST /presence`; coordinates are already rounded to 3 decimals. */
@Serializable
data class PresenceRequest(val lat: Double, val lon: Double)

/** Answer of `GET /groups`. [minSize] is null when the server did not say. */
data class GroupsDto(val groups: List<GroupDto>, val minSize: Int?)

/** The server's `Group` (docs/CONTRACTS.md §3.1). */
@Serializable
data class GroupDto(
    val id: String,
    val lat: Double,
    val lon: Double,
    val size: Int,
    val status: String,
    val lastSeen: Long,
)
