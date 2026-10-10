package com.sahay.comms.wire

import com.sahay.core.contracts.GroupStatus
import com.sahay.core.contracts.HazardType
import com.sahay.core.contracts.SahayConfig
import com.sahay.core.contracts.ShelterStatus
import java.time.Clock
import java.util.Locale
import javax.inject.Inject

/**
 * Builds and parses the `SH1*...` wire strings of docs/CONTRACTS.md §4. Pure Kotlin, no Android types.
 *
 * Parsing checks the *shape* only (prefix, field counts, length, time window). Signatures are checked by
 * [SignatureVerifier]; use [WireReader] to do both.
 */
class WireCodec(private val clock: Clock) {

    @Inject constructor() : this(Clock.systemUTC())

    // ------------------------------------------------------------------ parse

    fun parse(wire: String): WireParseResult {
        val shape = parseShape(wire)
        if (shape !is WireParseResult.Ok) return shape
        val ageSec = clock.instant().epochSecond - shape.message.timestamp
        return when {
            ageSec > MAX_AGE_SEC -> reject(WireError.STALE, "$ageSec s old")
            -ageSec > MAX_FUTURE_SEC -> reject(WireError.FROM_FUTURE, "${-ageSec} s ahead")
            else -> shape
        }
    }

    /** Everything except the time window: length, charset, prefix, field count and field formats. */
    private fun parseShape(wire: String): WireParseResult {
        if (wire.length > SahayConfig.WIRE_MAX_LEN) return reject(WireError.TOO_LONG, "${wire.length} chars")
        if (!wire.all { it in ' '..'~' }) return reject(WireError.NOT_ASCII)

        val fields = wire.split(SahayConfig.WIRE_SEP)
        if (fields[0] != SahayConfig.WIRE_PREFIX) return reject(WireError.WRONG_PREFIX, fields[0].take(8))
        val type = fields.getOrNull(1) ?: return reject(WireError.WRONG_FIELD_COUNT, "no type")
        val expected = FIELD_COUNTS[type] ?: return reject(WireError.UNKNOWN_TYPE, type.take(4))
        if (fields.size != expected) return reject(WireError.WRONG_FIELD_COUNT, "$type has ${fields.size}, wants $expected")

        val message = when (type) {
            "A" -> parseAlert(fields)
            "S" -> parseShelter(fields)
            "G" -> parseGroup(fields)
            "R" -> parseReport(fields)
            "P" -> parsePresence(fields)
            else -> parseBeacon(fields)
        } ?: return reject(WireError.BAD_FIELD, "type $type")

        if (message is WireMessage.Signed && !SIGNATURE.matches(message.signature)) {
            return reject(WireError.BAD_SIGNATURE_FORMAT)
        }
        return WireParseResult.Ok(message)
    }

    private fun parseAlert(f: List<String>): WireMessage? {
        val (lat, lon) = latLon(f[5]) ?: return null
        return WireMessage.Alert(
            id = f[2].takeIf(ID::matches) ?: return null,
            templateCode = f[3].takeIf(TEMPLATE_CODE::matches) ?: return null,
            severity = severity(f[4]) ?: return null,
            lat = lat,
            lon = lon,
            radiusM = f[6].takeIf(RADIUS::matches)?.toInt() ?: return null,
            isSimulation = when (f[7]) { "S" -> true; "R" -> false; else -> return null },
            timestamp = timestamp(f[8]) ?: return null,
            signature = f[9],
        )
    }

    private fun parseShelter(f: List<String>): WireMessage? = WireMessage.ShelterStatusUpdate(
        shelterId = f[2].takeIf(ID::matches) ?: return null,
        status = when (f[3]) {
            "O" -> ShelterStatus.OPEN
            "F" -> ShelterStatus.FULL
            "C" -> ShelterStatus.CLOSED
            else -> return null
        },
        timestamp = timestamp(f[4]) ?: return null,
        signature = f[5],
    )

    private fun parseGroup(f: List<String>): WireMessage? {
        val (lat, lon) = latLon(f[2]) ?: return null
        return WireMessage.Group(
            lat = lat,
            lon = lon,
            size = f[3].takeIf(COUNT::matches)?.toInt() ?: return null,
            status = when (f[4]) {
                "A" -> GroupStatus.AT_SHELTER
                "S" -> GroupStatus.SAFE_AREA
                "R" -> GroupStatus.RISK_ZONE
                else -> return null
            },
            timestamp = timestamp(f[5]) ?: return null,
            signature = f[6],
        )
    }

    private fun parseReport(f: List<String>): WireMessage? {
        val (lat, lon) = latLon(f[3]) ?: return null
        return WireMessage.Report(
            type = HazardType.fromCode(f[2]) ?: return null,
            lat = lat,
            lon = lon,
            severity = severity(f[4]) ?: return null,
            timestamp = timestamp(f[5]) ?: return null,
            uid = f[6].takeIf(UID::matches) ?: return null,
        )
    }

    private fun parsePresence(f: List<String>): WireMessage? {
        val (lat, lon) = latLon(f[2]) ?: return null
        return WireMessage.Presence(lat, lon, timestamp(f[3]) ?: return null, f[4].takeIf(UID::matches) ?: return null)
    }

    private fun parseBeacon(f: List<String>): WireMessage? {
        val (lat, lon) = latLon(f[2]) ?: return null
        return WireMessage.Beacon(lat, lon, timestamp(f[3]) ?: return null, f[4].takeIf(UID::matches) ?: return null)
    }

    // ------------------------------------------------------------------ encode

    /**
     * Wire string for [message]. Signed types must already carry their signature (the server signs them;
     * the app only re-encodes them, e.g. to relay over the mesh).
     * @throws IllegalArgumentException if the result would not be a valid wire (too long, bad field).
     */
    fun encode(message: WireMessage): String {
        val body = when (message) {
            is WireMessage.Alert -> join(
                "A", message.id, message.templateCode, message.severity, coords(message.lat, message.lon, 5),
                message.radiusM, if (message.isSimulation) "S" else "R", message.timestamp, message.signature,
            )
            is WireMessage.ShelterStatusUpdate -> join(
                "S", message.shelterId, shelterLetter(message.status), message.timestamp, message.signature,
            )
            is WireMessage.Group -> join(
                "G", coords(message.lat, message.lon, 3), message.size, groupLetter(message.status),
                message.timestamp, message.signature,
            )
            is WireMessage.Report -> join(
                "R", message.type.code, coords(message.lat, message.lon, 5), message.severity,
                message.timestamp, message.uid,
            )
            is WireMessage.Presence -> join("P", coords(message.lat, message.lon, 3), message.timestamp, message.uid)
            is WireMessage.Beacon -> join(
                "B", if (message.isCancel) "0,0" else coords(message.lat, message.lon, 3), message.timestamp, message.uid,
            )
        }
        require(body.length <= SahayConfig.WIRE_MAX_LEN) { "Wire is ${body.length} chars, max ${SahayConfig.WIRE_MAX_LEN}" }
        require(parseShape(body) is WireParseResult.Ok) { "Not a valid wire: $body" }
        return body
    }

    private fun join(vararg parts: Any): String =
        (listOf<Any>(SahayConfig.WIRE_PREFIX) + parts).joinToString(SahayConfig.WIRE_SEP.toString())

    private fun shelterLetter(s: ShelterStatus) = when (s) {
        ShelterStatus.OPEN -> "O"
        ShelterStatus.FULL -> "F"
        ShelterStatus.CLOSED -> "C"
        ShelterStatus.UNKNOWN -> throw IllegalArgumentException("UNKNOWN has no wire form")
    }

    private fun groupLetter(s: GroupStatus) = when (s) {
        GroupStatus.AT_SHELTER -> "A"
        GroupStatus.SAFE_AREA -> "S"
        GroupStatus.RISK_ZONE -> "R"
    }

    private fun coords(lat: Double, lon: Double, decimals: Int) =
        String.format(Locale.ROOT, "%.${decimals}f,%.${decimals}f", lat, lon)

    private fun reject(error: WireError, detail: String = "") = WireParseResult.Rejected(error, detail)

    // ------------------------------------------------------------------ field helpers

    private fun latLon(s: String): Pair<Double, Double>? {
        val m = LAT_LON.matchEntire(s) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    private fun severity(s: String): Int? = s.singleOrNull()?.digitToIntOrNull()?.takeIf { it in 0..3 }

    private fun timestamp(s: String): Long? = s.takeIf(TIMESTAMP::matches)?.toLongOrNull()

    companion object {
        const val MAX_AGE_SEC = 48 * 3600L
        const val MAX_FUTURE_SEC = 10 * 60L

        /** Total number of `*`-separated fields per type, including `SH1` and the type letter. */
        private val FIELD_COUNTS = mapOf("A" to 10, "S" to 6, "G" to 7, "R" to 7, "P" to 5, "B" to 5)

        private val ID = Regex("[A-Za-z0-9_-]{1,32}")
        private val TEMPLATE_CODE = Regex("[A-Z0-9_]{1,24}")
        private val UID = Regex("[A-Za-z0-9_-]{1,8}")
        private val SIGNATURE = Regex("[A-Za-z0-9_-]{86}")
        private val LAT_LON = Regex("(-?\\d{1,3}(?:\\.\\d{1,8})?),(-?\\d{1,3}(?:\\.\\d{1,8})?)")
        private val RADIUS = Regex("\\d{1,7}")
        private val COUNT = Regex("\\d{1,6}")
        private val TIMESTAMP = Regex("\\d{1,12}")

        /** The part of a signed wire that is covered by the signature: everything up to and including the last `*`. */
        fun signedPortion(wire: String): String = wire.substring(0, wire.lastIndexOf(SahayConfig.WIRE_SEP) + 1)

        /** First 8 characters of the Firebase uid, or `anon0000` for guests/signed-out users. */
        fun uid8(firebaseUid: String?): String =
            firebaseUid?.filter { it.isLetterOrDigit() && it.code < 128 }?.take(8)?.takeIf { it.isNotEmpty() } ?: "anon0000"
    }
}
