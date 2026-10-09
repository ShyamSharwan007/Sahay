package com.sahay

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.sahay.core.contracts.ThemeMode
import com.sahay.designsystem.DesignGalleryScreen
import com.sahay.designsystem.SahayTheme
import dagger.hilt.android.AndroidEntryPoint

/** Temporary theme choices for the design gallery. */
private enum class GalleryTheme(val label: String, val mode: ThemeMode, val emergency: Boolean) {
    System("System", ThemeMode.SYSTEM, false),
    Light("Light", ThemeMode.LIGHT, false),
    Dark("Dark", ThemeMode.DARK, false),
    Emergency("Emergency", ThemeMode.DARK, true),
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // must run before super.onCreate
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Temporary: shows the design gallery until the real navigation graph lands.
            var choice by rememberSaveable { mutableStateOf(GalleryTheme.System) }
            SahayTheme(themeMode = choice.mode, emergency = choice.emergency) {
                Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GalleryTheme.entries.forEach { option ->
                            FilterChip(
                                selected = option == choice,
                                onClick = { choice = option },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    DesignGalleryScreen()
                }
            }
        }
    }
}
