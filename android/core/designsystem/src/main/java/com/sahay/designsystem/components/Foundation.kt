package com.sahay.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sahay.designsystem.LocalEmergency
import com.sahay.designsystem.LocalSahayColors
import com.sahay.designsystem.LocalSahayDark
import com.sahay.designsystem.SahayShapes

/** Meaning of a colored component. Always paired with an icon and a word, never color alone. */
enum class StatusKind { Safe, Watch, Warning, Danger, Info, Offline }

/** [main] = strong color (icons, borders, text), [container] = soft tinted background, [onMain] = text on a filled [main]. */
@Immutable
class StatusPalette(val main: Color, val container: Color, val onMain: Color)

@Composable
@ReadOnlyComposable
fun StatusKind.palette(): StatusPalette {
    val c = LocalSahayColors.current
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        StatusKind.Safe -> StatusPalette(c.safe, c.safeContainer, c.onStatus)
        StatusKind.Watch -> StatusPalette(c.watch, c.watchContainer, c.onStatus)
        StatusKind.Warning -> StatusPalette(c.warning, c.warningContainer, c.onStatus)
        StatusKind.Danger -> StatusPalette(c.danger, c.dangerContainer, c.onStatus)
        StatusKind.Info -> StatusPalette(c.info, c.infoContainer, c.onStatus)
        StatusKind.Offline -> StatusPalette(scheme.onSurfaceVariant, scheme.surfaceVariant, scheme.surface)
    }
}

fun StatusKind.defaultIcon(): ImageVector = when (this) {
    StatusKind.Safe -> Icons.Rounded.CheckCircle
    StatusKind.Watch -> Icons.Rounded.Visibility
    StatusKind.Warning -> Icons.Rounded.Warning
    StatusKind.Danger -> Icons.Rounded.Report
    StatusKind.Info -> Icons.Rounded.Info
    StatusKind.Offline -> Icons.Rounded.CloudOff
}

/** Severity 0 info, 1 watch, 2 warning, 3 emergency (CONTRACTS §4). Out-of-range values are clamped. */
fun statusKindForSeverity(severity: Int): StatusKind = when {
    severity <= 0 -> StatusKind.Info
    severity == 1 -> StatusKind.Watch
    severity == 2 -> StatusKind.Warning
    else -> StatusKind.Danger
}

/** 1 dp outline in light mode; dark mode relies on layered surfaces instead (emergency keeps the outline on pure black). */
@Composable
@ReadOnlyComposable
internal fun cardBorder(): BorderStroke? =
    if (!LocalSahayDark.current || LocalEmergency.current) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null

/** Flat card surface shared by all card-like components. Pass [onClick] to make it tappable. */
@Composable
internal fun CardSurface(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    shape: Shape = SahayShapes.card,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.semantics { role = Role.Button },
            shape = shape,
            color = color,
            border = cardBorder(),
            content = content,
        )
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = cardBorder(), content = content)
    }
}

/** Icon inside a softly tinted circle. */
@Composable
internal fun TintedIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    circleSize: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    background: Color = tint.copy(alpha = 0.16f),
) {
    Box(
        modifier = modifier.size(circleSize).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Small pill with icon + word. Used by severity, verification and similar badges. */
@Composable
internal fun Pill(
    icon: ImageVector,
    label: String,
    contentColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
) {
    Surface(modifier = modifier, shape = SahayShapes.pill, color = containerColor, border = border) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = contentColor)
        }
    }
}
