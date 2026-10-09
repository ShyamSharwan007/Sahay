package com.sahay.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * Status colors (DESIGN §2). Reserved for meaning, never decoration.
 * `*Container` = status color at 12% (light) / 18% (dark, emergency) over the surface.
 */
@Immutable
data class SahayColors(
    val safe: Color,
    val safeContainer: Color,
    val watch: Color,
    val watchContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val info: Color,
    val infoContainer: Color,
    /** Text/icon color on a filled status color. */
    val onStatus: Color,
)

val LocalSahayColors = compositionLocalOf { lightSahayColors() }

/** True for Dark and Emergency; components use it for outline-vs-layered-surface decisions. */
val LocalSahayDark = compositionLocalOf { false }

/** True while Emergency Mode is on: no shadows, shimmer or animation. */
val LocalEmergency = compositionLocalOf { false }

private fun tint(status: Color, surface: Color, alpha: Float) = status.copy(alpha = alpha).compositeOver(surface)

private fun buildSahayColors(
    safe: Color, watch: Color, warning: Color, danger: Color, info: Color,
    onStatus: Color, surface: Color, containerAlpha: Float,
) = SahayColors(
    safe = safe, safeContainer = tint(safe, surface, containerAlpha),
    watch = watch, watchContainer = tint(watch, surface, containerAlpha),
    warning = warning, warningContainer = tint(warning, surface, containerAlpha),
    danger = danger, dangerContainer = tint(danger, surface, containerAlpha),
    info = info, infoContainer = tint(info, surface, containerAlpha),
    onStatus = onStatus,
)

internal fun lightSahayColors() = buildSahayColors(
    safe = Color(0xFF18794C), watch = Color(0xFF9A6700), warning = Color(0xFFC2410C),
    danger = Color(0xFFC62828), info = Color(0xFF2457C5),
    onStatus = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), containerAlpha = 0.12f,
)

internal fun darkSahayColors() = buildSahayColors(
    safe = Color(0xFF4ADE95), watch = Color(0xFFF2C14E), warning = Color(0xFFFF9F5A),
    danger = Color(0xFFFF6B6B), info = Color(0xFF86ABFF),
    onStatus = Color(0xFF0D1413), surface = Color(0xFF141D1C), containerAlpha = 0.18f,
)

internal fun emergencySahayColors() = buildSahayColors(
    safe = Color(0xFF30D158), watch = Color(0xFFF2C14E), warning = Color(0xFFFF9F5A),
    danger = Color(0xFFFF453A), info = Color(0xFF86ABFF),
    onStatus = Color(0xFF000000), surface = Color(0xFF0B0B0B), containerAlpha = 0.18f,
)

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B6E6E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEDE9),
    onPrimaryContainer = Color(0xFF00302D),
    secondary = Color(0xFF0B6E6E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBF0EE),
    onSecondaryContainer = Color(0xFF132120),
    background = Color(0xFFF6F8F7),
    onBackground = Color(0xFF132120),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF132120),
    surfaceVariant = Color(0xFFEBF0EE),
    onSurfaceVariant = Color(0xFF4A5A57),
    surfaceTint = Color(0xFF0B6E6E),
    outline = Color(0xFFC5D0CD),
    outlineVariant = Color(0xFFC5D0CD),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF8E3E3),
    onErrorContainer = Color(0xFF5C1111),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEBF0EE),
    surfaceContainerHighest = Color(0xFFEBF0EE),
)

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF5FD3CA),
    onPrimary = Color(0xFF00332F),
    primaryContainer = Color(0xFF0F4A47),
    onPrimaryContainer = Color(0xFFBDF1EB),
    secondary = Color(0xFF5FD3CA),
    onSecondary = Color(0xFF00332F),
    secondaryContainer = Color(0xFF1C2726),
    onSecondaryContainer = Color(0xFFE5EEEC),
    background = Color(0xFF0D1413),
    onBackground = Color(0xFFE5EEEC),
    surface = Color(0xFF141D1C),
    onSurface = Color(0xFFE5EEEC),
    surfaceVariant = Color(0xFF1C2726),
    onSurfaceVariant = Color(0xFFA3B3B0),
    surfaceTint = Color(0xFF5FD3CA),
    outline = Color(0xFF2F3D3B),
    outlineVariant = Color(0xFF2F3D3B),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF0D1413),
    errorContainer = Color(0xFF3A1B1B),
    onErrorContainer = Color(0xFFFFD9D9),
    // Layered surfaces: each step is slightly lighter than the one below.
    surfaceContainerLowest = Color(0xFF0D1413),
    surfaceContainerLow = Color(0xFF111A19),
    surfaceContainer = Color(0xFF141D1C),
    surfaceContainerHigh = Color(0xFF1C2726),
    surfaceContainerHighest = Color(0xFF243230),
)

internal val EmergencyColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF64D2FF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF0A2F3D),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF64D2FF),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1A1A1A),
    onSecondaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0B0B0B),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFBDBDBD),
    surfaceTint = Color(0xFF64D2FF),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF3A3A3A),
    error = Color(0xFFFF453A),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF3A1210),
    onErrorContainer = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF050505),
    surfaceContainer = Color(0xFF0B0B0B),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF1A1A1A),
)
