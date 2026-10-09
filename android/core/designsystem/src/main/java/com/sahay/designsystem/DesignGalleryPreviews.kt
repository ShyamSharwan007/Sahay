package com.sahay.designsystem

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.sahay.core.contracts.ThemeMode

private const val GalleryHeight = 3400

@Composable
private fun GalleryPreview(themeMode: ThemeMode, emergency: Boolean = false) {
    SahayTheme(themeMode = themeMode, emergency = emergency) { DesignGalleryScreen() }
}

@Preview(name = "Light", widthDp = 380, heightDp = GalleryHeight, showBackground = true)
@Composable
private fun GalleryLightPreview() = GalleryPreview(ThemeMode.LIGHT)

@Preview(name = "Dark", widthDp = 380, heightDp = GalleryHeight, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GalleryDarkPreview() = GalleryPreview(ThemeMode.DARK)

@Preview(name = "Emergency", widthDp = 380, heightDp = GalleryHeight, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GalleryEmergencyPreview() = GalleryPreview(ThemeMode.SYSTEM, emergency = true)

@Preview(name = "Font scale 2.0", widthDp = 380, heightDp = 6000, showBackground = true, fontScale = 2f)
@Composable
private fun GalleryFontScalePreview() = GalleryPreview(ThemeMode.LIGHT)

@Preview(name = "Arabic RTL", widthDp = 380, heightDp = GalleryHeight, showBackground = true, locale = "ar")
@Composable
private fun GalleryArabicRtlPreview() = GalleryPreview(ThemeMode.LIGHT)
