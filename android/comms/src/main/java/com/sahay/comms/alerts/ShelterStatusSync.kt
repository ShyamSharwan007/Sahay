package com.sahay.comms.alerts

import android.util.Log
import com.sahay.comms.net.CommsApi
import com.sahay.comms.wire.WireMessage
import com.sahay.comms.wire.WireReader
import com.sahay.core.contracts.PackRepository
import kotlinx.coroutines.CancellationException

/**
 * `GET /shelters/status` → verify every `SH1*S*` wire → [PackRepository.updateShelterStatus].
 * Only the signed wire is trusted (docs/CONTRACTS.md §3.1); the JSON fields next to it are ignored.
 */
internal class ShelterStatusSync(
    private val api: CommsApi,
    private val reader: WireReader,
    private val packRepository: PackRepository,
) {
    /** Number of statuses applied. Best effort: a failure is logged and never reaches the alert refresh. */
    suspend fun sync(regionId: String): Int = try {
        api.shelterStatuses(regionId).count { dto -> apply(dto.wire) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Shelter status refresh failed: ${e.message}")
        0
    }

    private suspend fun apply(wire: String?): Boolean {
        val update = reader.read(wire ?: return false) as? WireMessage.ShelterStatusUpdate ?: return false
        packRepository.updateShelterStatus(update.shelterId, update.status, update.timestamp)
        return true
    }

    private companion object {
        const val TAG = "ShelterStatusSync"
    }
}
