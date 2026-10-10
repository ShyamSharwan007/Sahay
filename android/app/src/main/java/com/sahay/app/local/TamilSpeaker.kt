package com.sahay.app.local

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

private val TAMIL_INDIA: Locale = Locale.Builder().setLanguage("ta").setRegion("IN").build()
private const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"

/** Speaks Tamil with the phone's text-to-speech. Create with [rememberTamilSpeaker]. */
class TamilSpeaker internal constructor(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    /** True once we know the phone can't speak Tamil (no engine, or no Tamil voice). */
    var voiceMissing by mutableStateOf(false)
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            voiceMissing = true
            return
        }
        val result = engine.setLanguage(TAMIL_INDIA)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            voiceMissing = true
            pending = null
            return
        }
        ready = true
        pending?.let { speak(it) }
        pending = null
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        when {
            voiceMissing -> Unit // the notice is already showing
            !ready -> pending = text // engine still starting; say it as soon as it is up
            else -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sahay-ta")
        }
    }

    /** Opens the system voice settings; false when this phone has no such screen. */
    fun openVoiceSettings(context: Context): Boolean = try {
        context.startActivity(Intent(TTS_SETTINGS_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    internal fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}

@Composable
fun rememberTamilSpeaker(): TamilSpeaker {
    val context = LocalContext.current
    val speaker = remember { TamilSpeaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}
