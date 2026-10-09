package com.sahay.designsystem

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.sahay.core.contracts.ThemeMode

/** True in Emergency Mode or when the system animator scale is 0 ("Remove animations"). */
val LocalReduceMotion = compositionLocalOf { false }

/**
 * Sahay theme. [themeMode] SYSTEM follows the device; [emergency] overrides everything
 * with the black OLED theme (DESIGN §2).
 */
@Composable
fun SahayTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    emergency: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = emergency || when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        emergency -> EmergencyColorScheme
        dark -> DarkColorScheme
        else -> LightColorScheme
    }
    val sahayColors = remember(emergency, dark) {
        when {
            emergency -> emergencySahayColors()
            dark -> darkSahayColors()
            else -> lightSahayColors()
        }
    }
    val animationsOff = rememberAnimationsDisabled()

    CompositionLocalProvider(
        LocalSahayColors provides sahayColors,
        LocalSahayDark provides dark,
        LocalEmergency provides emergency,
        LocalReduceMotion provides (emergency || animationsOff),
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = SahayTypography, shapes = SahayShapes.material) {
            Surface(color = colorScheme.background, contentColor = colorScheme.onBackground, content = content)
        }
    }
}

/** Observes the system animator duration scale; 0 means the user removed animations. */
@Composable
private fun rememberAnimationsDisabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var disabled by remember { mutableStateOf(readAnimatorScale(resolver) == 0f) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                disabled = readAnimatorScale(resolver) == 0f
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return disabled
}

private fun readAnimatorScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
