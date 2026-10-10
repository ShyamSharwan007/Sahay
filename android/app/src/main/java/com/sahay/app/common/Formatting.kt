package com.sahay.app.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.sahay.R
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private const val KM_THRESHOLD_M = 1000.0
private const val NOW_TICK_MS = 30_000L

/** "650 m" below 1 km, "1.4 km" above. Nearest 10 m once past 100 m so numbers don't jitter. */
@Composable
fun distanceText(meters: Double): String {
    val m = meters.coerceAtLeast(0.0)
    val locale = LocalConfiguration.current.locales[0]
    return if (m < KM_THRESHOLD_M) {
        val rounded = if (m < 100) m.roundToInt() else (m / 10).roundToInt() * 10
        stringResource(R.string.distance_m, rounded)
    } else {
        stringResource(R.string.distance_km, String.format(locale, "%.1f", m / KM_THRESHOLD_M))
    }
}

/** Whole minutes between [epochSec] and [nowSec]; never negative (clock skew). */
fun ageMinutes(epochSec: Long, nowSec: Long): Int = ((nowSec - epochSec).coerceAtLeast(0) / 60).toInt()

/** "Just now", "12 min ago", "3 h ago", "2 d ago". */
@Composable
fun timeAgoText(epochSec: Long, nowSec: Long): String {
    val minutes = ageMinutes(epochSec, nowSec)
    return when {
        minutes < 1 -> stringResource(R.string.time_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.time_min_ago, minutes, minutes)
        minutes < 24 * 60 -> (minutes / 60).let { pluralStringResource(R.plurals.time_hours_ago, it, it) }
        else -> (minutes / (24 * 60)).let { pluralStringResource(R.plurals.time_days_ago, it, it) }
    }
}

/** Current time in epoch seconds, refreshed every 30 s so "x min ago" labels stay honest. */
@Composable
fun rememberNowSec(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(NOW_TICK_MS)
            now = System.currentTimeMillis() / 1000
        }
    }
    return now
}

/** Opens the dialer (no permission needed). Returns false on devices without a phone app. */
fun Context.dial(number: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
    true
} catch (_: ActivityNotFoundException) {
    false
}
