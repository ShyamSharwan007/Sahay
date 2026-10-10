package com.sahay.comms.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Lets the manifest-registered receiver reach the Hilt graph (receivers in a library cannot use @AndroidEntryPoint here). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmsReceiverEntryPoint {
    fun smsAlertProcessor(): SmsAlertProcessor
}

/**
 * Receives SMS (only the system may send us this broadcast, see the manifest permission) and hands each
 * complete message to [SmsAlertProcessor]. Work is capped at [WORK_TIMEOUT_MS] because a receiver may only
 * hold the process for a short time.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pieces = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty()
            .map { IncomingSms(it.originatingAddress, it.messageBody.orEmpty()) }
        val messages = SmsJoiner.join(pieces)
        if (messages.isEmpty()) return

        val processor = EntryPointAccessors
            .fromApplication(context.applicationContext, SmsReceiverEntryPoint::class.java)
            .smsAlertProcessor()
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                withTimeoutOrNull(WORK_TIMEOUT_MS) { messages.forEach { processor.handle(it) } }
                    ?: Log.w(TAG, "SMS handling timed out after $WORK_TIMEOUT_MS ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "SMS handling failed", e)   // never crash the app because of one odd SMS
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val TAG = "SmsReceiver"
        const val WORK_TIMEOUT_MS = 8_000L
    }
}

/** Puts the pieces of multipart SMS back together. */
object SmsJoiner {
    /**
     * Pieces of one long message arrive as separate parts with the same sender, in order. Parts from the same
     * sender in one broadcast are therefore concatenated; senders keep their first-seen order.
     */
    fun join(parts: List<IncomingSms>): List<IncomingSms> =
        parts.groupBy { it.sender }
            .map { (sender, pieces) -> IncomingSms(sender, pieces.joinToString("") { it.body }) }
            .filter { it.body.isNotBlank() }
}
