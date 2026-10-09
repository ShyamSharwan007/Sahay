package com.sahay

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.app.SahayNavHost
import com.sahay.core.contracts.EmergencyModeController
import com.sahay.core.contracts.UiPreferences
import com.sahay.designsystem.SahayTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var uiPreferences: UiPreferences
    @Inject lateinit var emergencyMode: EmergencyModeController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // must run before super.onCreate
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by uiPreferences.themeMode.collectAsStateWithLifecycle()
            val emergency by emergencyMode.isActive.collectAsStateWithLifecycle()
            SahayTheme(themeMode = themeMode, emergency = emergency) {
                SahayNavHost()
            }
        }
    }
}
