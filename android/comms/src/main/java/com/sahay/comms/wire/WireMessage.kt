package com.sahay.comms.wire

import com.sahay.core.contracts.GroupStatus
import com.sahay.core.contracts.HazardType
import com.sahay.core.contracts.ShelterStatus

/** Every message type of docs/CONTRACTS.md §4. Timestamps are Unix epoch seconds (UTC). */
sealed class WireMessage {
    abstract val timestamp: Long

    /** Server-signed messages carry the base64url Ed25519 signature (86 chars). */
    sealed class Signed : WireMessage() {
        abstract val signature: String
    }

    /** `SH1*A*<id6>*<templateCode>*<sev>*<lat>,<lon>*<radiusM>*<flags>*<ts>*<sig>` */
    data class Alert(
        val id: String,
        val templateCode: String,
        val severity: Int,
        val lat: Double,
        val lon: Double,
        val radiusM: Int,
        val isSimulation: Boolean,
        override val timestamp: Long,
        override val signature: String,
    ) : Signed()

    /** `SH1*S*<shelterId>*<O/F/C>*<ts>*<sig>` */
    data class ShelterStatusUpdate(
        val shelterId: String,
        val status: ShelterStatus,
        override val timestamp: Long,
        override val signature: String,
    ) : Signed()

    /** `SH1*G*<lat>,<lon>*<size>*<A/S/R>*<ts>*<sig>` */
    data class Group(
        val lat: Double,
        val lon: Double,
        val size: Int,
        val status: GroupStatus,
        override val timestamp: Long,
        override val signature: String,
    ) : Signed()

    /** `SH1*R*<typeCode>*<lat>,<lon>*<sev>*<ts>*<uid8>` (unsigned, from a user) */
    data class Report(
        val type: HazardType,
        val lat: Double,
        val lon: Double,
        val severity: Int,
        override val timestamp: Long,
        val uid: String,
    ) : WireMessage()

    /** `SH1*P*<lat>,<lon>*<ts>*<uid8>` */
    data class Presence(
        val lat: Double,
        val lon: Double,
        override val timestamp: Long,
        val uid: String,
    ) : WireMessage()

    /** `SH1*B*<lat>,<lon>*<ts>*<uid8>`; a cancel is encoded as `0,0`. */
    data class Beacon(
        val lat: Double,
        val lon: Double,
        override val timestamp: Long,
        val uid: String,
    ) : WireMessage() {
        val isCancel: Boolean get() = lat == 0.0 && lon == 0.0

        companion object {
            fun cancel(timestamp: Long, uid: String) = Beacon(0.0, 0.0, timestamp, uid)
        }
    }
}

/** Why a wire string was refused. Used for logging and tests; never shown to users. */
enum class WireError {
    TOO_LONG, NOT_ASCII, WRONG_PREFIX, UNKNOWN_TYPE, WRONG_FIELD_COUNT, BAD_FIELD, BAD_SIGNATURE_FORMAT, STALE, FROM_FUTURE,
}

sealed interface WireParseResult {
    data class Ok(val message: WireMessage) : WireParseResult
    data class Rejected(val error: WireError, val detail: String = "") : WireParseResult
}
