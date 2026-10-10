package com.sahay.app.onboarding

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.sahay.R
import com.sahay.core.contracts.SahayConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A language the app UI supports. [nativeName] resources are `translatable="false"`: each language shows its own name. */
data class AppLanguage(val code: String, @StringRes val nativeName: Int)

/** Same order as [SahayConfig.USER_LANGUAGES] (CONTRACTS §0). */
val SupportedLanguages: List<AppLanguage> = listOf(
    AppLanguage("en", R.string.lang_en),
    AppLanguage("de", R.string.lang_de),
    AppLanguage("fr", R.string.lang_fr),
    AppLanguage("es", R.string.lang_es),
    AppLanguage("ru", R.string.lang_ru),
    AppLanguage("ja", R.string.lang_ja),
    AppLanguage("ko", R.string.lang_ko),
    AppLanguage("zh", R.string.lang_zh),
    AppLanguage("ar", R.string.lang_ar),
)

/** Maps any device language code to a supported one, falling back to English. */
fun supportedLanguageOrEnglish(code: String?): String =
    code?.lowercase(Locale.ROOT)?.takeIf { it in SahayConfig.USER_LANGUAGES } ?: "en"

/** Reads and changes the per-app language. An interface so ViewModels stay testable without Android. */
interface AppLocaleController {
    /** The app's chosen language, or the device language on first launch. Always one of the 9 supported codes. */
    fun currentLanguage(): String
    /** Applies [code] to the whole app. May recreate the activity. */
    fun apply(code: String)
}

@Singleton
class AndroidAppLocaleController @Inject constructor(
    @Suppress("unused") @ApplicationContext private val context: Context,
) : AppLocaleController {

    override fun currentLanguage(): String {
        val chosen = AppCompatDelegate.getApplicationLocales()
        val language = if (chosen.isEmpty) Locale.getDefault().language else chosen[0]?.language
        return supportedLanguageOrEnglish(language)
    }

    override fun apply(code: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(supportedLanguageOrEnglish(code)))
    }
}
