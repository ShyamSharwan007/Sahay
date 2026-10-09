package com.sahay.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// PLACEHOLDER theme using the primary/background tokens from DESIGN.md §2. Replaced by the real design system.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6E6E),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8F7),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF132120),
    onSurface = Color(0xFF132120),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FD3CA),
    onPrimary = Color(0xFF00332F),
    background = Color(0xFF0D1413),
    surface = Color(0xFF141D1C),
    onBackground = Color(0xFFE5EEEC),
    onSurface = Color(0xFFE5EEEC),
)

@Composable
fun SahayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
