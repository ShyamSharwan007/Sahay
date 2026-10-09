package com.sahay.designsystem.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Science
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** Small pill in a status color with icon + word (e.g. forecast risk "Low", "High"). */
@Composable
fun StatusChip(
    kind: StatusKind,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = kind.defaultIcon(),
) {
    val palette = kind.palette()
    Pill(icon = icon, label = label, contentColor = palette.main, containerColor = palette.container, modifier = modifier)
}

/** DESIGN §1.6: simulated data is always labelled. The caller supplies the translated word. */
@Composable
fun SimulationTag(label: String, modifier: Modifier = Modifier) {
    StatusChip(kind = StatusKind.Watch, label = label, modifier = modifier, icon = Icons.Rounded.Science)
}
