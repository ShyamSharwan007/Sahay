package com.sahay.comms.sms

import android.util.Log
import com.sahay.comms.alerts.OfficialAlertDetector
import com.sahay.comms.alerts.RealAlertRepository
import com.sahay.comms.wire.WireMessage
import com.sahay.comms.wire.WireReader
import com.sahay.core.contracts.AlertSource
import com.sahay.core.contracts.PackRepository
import com.sahay.core.contracts.SahayConfig
import javax.inject.Inject
import javax.inject.Singleton

/** One complete SMS (multipart pieces already joined). */
data class IncomingSms(val sender: String?, val body: String)

/** What happened to an incoming SMS. */
enum class SmsOutcome { SERVER_ALERT, SHELTER_STATUS, GROUP, OFFICIAL_ALERT, DUPLICATE, DROPPED, IGNORED }

/**
 * Decides what an incoming SMS is and stores the result:
 *  - `SH1*...` wire: signature checked; alert → alerts, shelter status → pack, group → [GroupWireCache];
 *  - anything else: kept only if it reads like an official hazard alert ([OfficialAlertDetector]).
 */
@Singleton
class SmsAlertProcessor @Inject constructor(
    private val reader: WireReader,
    private val alerts: RealAlertRepository,
    private val packRepository: PackRepository,
    private val groupCache: GroupWireCache,
) {
    suspend fun handle(sms: IncomingSms): SmsOutcome {
        val body = sms.body.trim()
        if (body.isEmpty()) return SmsOutcome.IGNORED
        return if (body.startsWith("${SahayConfig.WIRE_PREFIX}${SahayConfig.WIRE_SEP}")) {
            handleWire(body)
        } else {
            handlePlainText(sms.sender, body)
        }
    }

    private suspend fun handleWire(wire: String): SmsOutcome =
        when (val message = reader.read(wire)) {
            is WireMessage.Alert ->
                if (alerts.ingestSignedAlert(message, wire, AlertSource.SMS_SERVER)) SmsOutcome.SERVER_ALERT else SmsOutcome.DUPLICATE
            is WireMessage.ShelterStatusUpdate -> {
                packRepository.updateShelterStatus(message.shelterId, message.status, message.timestamp)
                SmsOutcome.SHELTER_STATUS
            }
            is WireMessage.Group -> {
                groupCache.put(message)
                SmsOutcome.GROUP
            }
            null -> SmsOutcome.DROPPED                      // WireReader already logged why
            else -> {
                Log.w(TAG, "Dropped ${message::class.simpleName} received by SMS")   // users' messages are not accepted here
                SmsOutcome.DROPPED
            }
        }

    private suspend fun handlePlainText(sender: String?, body: String): SmsOutcome {
        val match = OfficialAlertDetector.detect(body, sender, packRepository.alertKeywords()) ?: return SmsOutcome.IGNORED
        return if (alerts.ingestOfficialSms(match, body)) SmsOutcome.OFFICIAL_ALERT else SmsOutcome.DUPLICATE
    }

    private companion object {
        const val TAG = "SmsAlertProcessor"
    }
}
