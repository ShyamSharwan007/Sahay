package com.sahay.comms.sos

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Why one SMS (or all of them) could not be sent. Turned into text by [ResourceSosStrings]. */
enum class SmsFailure { NO_CONTACTS, NO_PERMISSION, AIRPLANE_MODE, NO_SIM, NO_SERVICE, INVALID_NUMBER, TIMEOUT, GENERIC }

/** Sends one text message. A seam so the SOS logic can be tested without a phone. */
interface SmsDispatcher {
    /** A reason no SMS can be sent right now (no permission, airplane mode, no SIM), or null if sending looks possible. */
    fun unavailableReason(): SmsFailure?

    /**
     * Sends [text] to [phone] and suspends until the network reports the result of every part.
     * Returns null on success. Has no timeout of its own; the caller bounds the wait. Never throws except on cancellation.
     */
    suspend fun send(phone: String, text: String): SmsFailure?
}

/** [SmsDispatcher] on top of [SmsManager], using the `sentIntent` of each part to learn whether it left the phone. */
@Singleton
class AndroidSmsDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsDispatcher {

    override fun unavailableReason(): SmsFailure? = when {
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ->
            SmsFailure.NO_PERMISSION
        isAirplaneModeOn() -> SmsFailure.AIRPLANE_MODE
        !hasUsableSim() -> SmsFailure.NO_SIM
        else -> null
    }

    override suspend fun send(phone: String, text: String): SmsFailure? {
        val manager = smsManager() ?: return SmsFailure.NO_SIM
        val action = "$ACTION_PREFIX.${UUID.randomUUID()}"
        val partResults = ConcurrentHashMap<Int, Int>()
        val allReported = CompletableDeferred<Unit>()
        val parts = try {
            manager.divideMessage(text)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not split the message: ${e.message}")
            return SmsFailure.GENERIC
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                partResults[intent.getIntExtra(EXTRA_PART, -1)] = resultCode
                if (partResults.size >= parts.size) allReported.complete(Unit)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            // Each part needs its own request code, otherwise Android hands back one shared PendingIntent.
            val sentIntents = ArrayList(parts.indices.map { sentIntent(action, it) })
            manager.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
            allReported.await()
            return partResults.values.firstOrNull { it != Activity.RESULT_OK }?.let(::failureFor)
        } catch (e: CancellationException) {
            throw e                                   // the caller's timeout; CancellationException is a RuntimeException
        } catch (e: SecurityException) {
            return SmsFailure.NO_PERMISSION
        } catch (e: IllegalArgumentException) {
            return SmsFailure.INVALID_NUMBER          // SmsManager rejects empty or malformed destinations
        } catch (e: RuntimeException) {
            Log.w(TAG, "SMS send failed: ${e.message}")
            return SmsFailure.GENERIC
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun sentIntent(action: String, part: Int): PendingIntent {
        val intent = Intent(action).setPackage(context.packageName).putExtra(EXTRA_PART, part)
        return PendingIntent.getBroadcast(context, part, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
    }

    private fun failureFor(resultCode: Int): SmsFailure = when (resultCode) {
        SmsManager.RESULT_ERROR_RADIO_OFF -> SmsFailure.AIRPLANE_MODE
        SmsManager.RESULT_ERROR_NO_SERVICE -> SmsFailure.NO_SERVICE
        SmsManager.RESULT_ERROR_NULL_PDU -> SmsFailure.INVALID_NUMBER
        else -> SmsFailure.GENERIC
    }

    @Suppress("DEPRECATION")   // getDefault() is the only option below API 31
    private fun smsManager(): SmsManager? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()
    } catch (e: RuntimeException) {
        null
    }

    private fun isAirplaneModeOn() =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    /** Any SIM slot that is ready. Phones without telephony (tablets, emulators) count as "no SIM". */
    private fun hasUsableSim(): Boolean = try {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) && telephony != null &&
            (0 until maxOf(telephony.phoneCount, 1)).any { telephony.getSimState(it) == TelephonyManager.SIM_STATE_READY }
    } catch (e: RuntimeException) {
        false
    }

    private companion object {
        const val TAG = "SmsDispatcher"
        const val ACTION_PREFIX = "com.sahay.comms.SOS_SENT"
        const val EXTRA_PART = "part"
    }
}
