package com.sahay.comms.alerts

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sahay.comms.R
import com.sahay.core.contracts.DeepLinks
import com.sahay.core.contracts.SahayAlert
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Shows a system notification for a freshly received alert. */
interface AlertNotifier {
    fun notify(alert: SahayAlert)
}

/**
 * Severity 2 and 3 use the high-importance "alerts" channel; severity 0 and 1 use the quieter "alerts_info".
 * Text is already in the user's language. Tapping opens the app with `DeepLinks.EXTRA = alert:<id>`.
 * Does nothing when notifications are not allowed (POST_NOTIFICATIONS denied or switched off).
 */
@Singleton
class SystemAlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlertNotifier {

    @SuppressLint("MissingPermission") // areNotificationsEnabled() covers the Android 13+ permission
    override fun notify(alert: SahayAlert) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            ensureChannels(manager)
            val urgent = alert.severity >= URGENT_SEVERITY
            val title = if (alert.isSimulation) {
                context.getString(R.string.comms_notification_simulation, alert.title)
            } else {
                alert.title
            }
            val notification = NotificationCompat.Builder(context, if (urgent) CHANNEL_ALERTS else CHANNEL_INFO)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(alert.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
                .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(alert.id))
                .build()
            manager.notify(alert.id.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification not allowed: ${e.message}")
        }
    }

    private fun openAppIntent(alertId: String): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.putExtra(DeepLinks.EXTRA, DeepLinks.alert(alertId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, alertId.hashCode(), launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannels(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ALERTS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.comms_channel_alerts))
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_INFO, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.comms_channel_alerts_info))
                .build(),
        )
    }

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_INFO = "alerts_info"
        private const val URGENT_SEVERITY = 2
        private const val TAG = "SystemAlertNotifier"
    }
}
