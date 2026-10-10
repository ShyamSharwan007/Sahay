package com.sahay.engine.risk

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sahay.core.contracts.DeepLinks
import com.sahay.engine.R

/** Tells the user, outside the app, that they have walked into a flood-prone area. */
internal fun interface RiskNotifier {
    fun notifyEnteringHighZone()
}

/** Shows the notification in channel [CHANNEL_ID]; tapping it opens the app on "Go to safety". */
internal class AndroidRiskNotifier(private val context: Context) : RiskNotifier {

    @SuppressLint("MissingPermission")      // areNotificationsEnabled() covers the Android 13+ permission
    override fun notifyEnteringHighZone() {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return      // user said no: the in-app status card still shows the risk
        try {
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.engine_risk_channel_name))
                    .build(),
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_risk_notification)
                .setContentTitle(context.getString(R.string.engine_risk_notification_title))
                .setContentText(context.getString(R.string.engine_risk_notification_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(navigateSafeIntent())
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification not allowed: ${e.message}")
        }
    }

    /** Launch intent of this app carrying the "go to safety" deep link; null if the app has no launcher activity. */
    private fun navigateSafeIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.putExtra(DeepLinks.EXTRA, DeepLinks.NAVIGATE_SAFE)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, REQUEST_CODE, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "risk"
        private const val NOTIFICATION_ID = 7101
        private const val REQUEST_CODE = 7101
        private const val TAG = "RiskNotifier"
    }
}
