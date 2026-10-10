package com.sahay.designsystem

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically

/** Screen transitions: a 200 ms fade plus a small slide. Direction-neutral, so it also suits RTL. */
object SahayMotion {
    const val DURATION_MS = 200
    private const val SLIDE_DIVISOR = 16

    /** Pass [LocalReduceMotion] `.current`: with reduced motion screens just switch. */
    fun enter(reduceMotion: Boolean): EnterTransition =
        if (reduceMotion) EnterTransition.None
        else fadeIn(tween(DURATION_MS)) + slideInVertically(tween(DURATION_MS)) { it / SLIDE_DIVISOR }

    fun exit(reduceMotion: Boolean): ExitTransition =
        if (reduceMotion) ExitTransition.None
        else fadeOut(tween(DURATION_MS)) + slideOutVertically(tween(DURATION_MS)) { it / SLIDE_DIVISOR }
}
