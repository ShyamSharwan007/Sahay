package com.sahay.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Flat themed card (20 dp corners, outline in light mode). Pass [onClick] to make it a button. */
@Composable
fun SahayCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    CardSurface(modifier = modifier, onClick = onClick, content = content)
}
