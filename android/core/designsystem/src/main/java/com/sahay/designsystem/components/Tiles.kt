package com.sahay.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 2-column grid tile, at least 112 dp tall: icon in a tinted circle, label of up to 2 lines. */
@Composable
fun BigActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: StatusKind? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = kind?.palette()
    CardSurface(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            TintedIcon(
                icon = icon,
                tint = palette?.main ?: scheme.primary,
                circleSize = 48.dp,
                iconSize = 26.dp,
                background = palette?.container ?: scheme.primaryContainer,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(lineHeight = MaterialTheme.typography.bodyMedium.lineHeight),
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** Thin banner under the top bar, e.g. "Offline — using saved data from 10:42". */
@Composable
fun OfflineBanner(text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

/** Overline-style section title with an optional "See all" action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
