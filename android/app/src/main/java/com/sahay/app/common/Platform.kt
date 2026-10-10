package com.sahay.app.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CancellationException

/** The Activity behind a Compose context (which is often wrapped), or null. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Runs [block]; any failure other than cancellation becomes null, so the screen can show a fallback. */
suspend fun <T> safely(block: suspend () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
