package com.sahay.comms.alerts

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/** String resource in English, whatever the app language is (alerts always keep an English line, DESIGN.md §3). */
internal fun Context.getEnglishString(@StringRes id: Int, vararg args: Any): String {
    val config = Configuration(resources.configuration).apply { setLocale(Locale.ENGLISH) }
    return createConfigurationContext(config).getString(id, *args)
}
