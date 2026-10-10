package com.sahay.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sahay.designsystem.LocalEmergency
import com.sahay.designsystem.LocalReduceMotion

/** Never a dead end: icon, title, one sentence, one action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) = StateMessage(icon, MaterialTheme.colorScheme.primary, title, body, modifier, actionLabel, onAction)

/** Something went wrong. Say what happened in plain words and offer a retry. */
@Composable
fun ErrorState(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.ErrorOutline,
) = StateMessage(icon, MaterialTheme.colorScheme.error, title, body, modifier, actionLabel, onAction, announce = true)

@Composable
private fun StateMessage(
    icon: ImageVector,
    tint: Color,
    title: String,
    body: String,
    modifier: Modifier,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    announce: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(64.dp))
        Column(
            modifier = if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            SahayButton(text = actionLabel, onClick = onAction, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * Skeleton list with a soft shimmer. In Emergency Mode, or when animations are removed,
 * the skeleton is static (no shimmer).
 */
@Composable
fun LoadingState(
    loadingDescription: String,
    modifier: Modifier = Modifier,
    rows: Int = 3,
) {
    val brush = skeletonBrush()
    Column(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = loadingDescription },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(rows) {
            CardSurface {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box40(brush)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonBar(brush, 0.6f, 14)
                        SkeletonBar(brush, 0.9f, 12)
                    }
                }
            }
        }
    }
}

@Composable
private fun Box40(brush: Brush) {
    androidx.compose.foundation.layout.Box(Modifier.size(40.dp).clip(CircleShape).background(brush))
}

@Composable
private fun SkeletonBar(brush: Brush, fraction: Float, heightDp: Int) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(heightDp.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(brush),
    )
}

@Composable
private fun skeletonBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    if (LocalEmergency.current || LocalReduceMotion.current) return Brush.linearGradient(listOf(base, base))
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerOffset",
    )
    return Brush.linearGradient(listOf(base, highlight, base), start = androidx.compose.ui.geometry.Offset(offset, 0f), end = androidx.compose.ui.geometry.Offset(offset + 400f, 0f))
}
