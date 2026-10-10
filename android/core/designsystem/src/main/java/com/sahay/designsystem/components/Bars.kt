package com.sahay.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sahay.designsystem.LocalEmergency
import com.sahay.designsystem.LocalReduceMotion
import com.sahay.designsystem.LocalSahayColors

/**
 * Title start-aligned, with trailing [actions] (normally a [ConnectivityChip]).
 * Pass [onBack] + [backContentDescription] for a back arrow.
 */
@Composable
fun SahayTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backContentDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 64.dp)
            .padding(start = if (onBack == null) 20.dp else 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = backContentDescription)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        actions()
    }
}

data class BottomBarItem(
    val label: String,
    val icon: ImageVector,
    /** Unread count; 0 hides the badge. */
    val badgeCount: Int = 0,
    /** Spoken text when a badge is shown, e.g. "Alerts, 3 unread". */
    val contentDescription: String? = null,
)

/** Four destinations: Home, Map, Alerts, Me. Labels always visible (icon + word). Hidden in Emergency Mode. */
@Composable
fun SahayBottomBar(
    items: List<BottomBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hideInEmergency: Boolean = true,
) {
    if (hideInEmergency && LocalEmergency.current) return
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSahayColors.current
    Column(modifier) {
        HorizontalDivider(color = scheme.outline)
        NavigationBar(containerColor = scheme.surface, tonalElevation = 0.dp) {
            items.forEachIndexed { index, item ->
                NavigationBarItem(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    alwaysShowLabel = true,
                    label = { Text(item.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (item.badgeCount > 0) {
                                    Badge(containerColor = colors.danger, contentColor = colors.onStatus) {
                                        Text(if (item.badgeCount > 99) "99+" else item.badgeCount.toString())
                                    }
                                }
                            },
                        ) { Icon(item.icon, contentDescription = null) }
                    },
                    modifier = item.contentDescription?.let { d -> Modifier.semantics { contentDescription = d } } ?: Modifier,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = scheme.onPrimaryContainer,
                        selectedTextColor = scheme.onSurface,
                        indicatorColor = scheme.primaryContainer,
                        unselectedIconColor = scheme.onSurfaceVariant,
                        unselectedTextColor = scheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

/**
 * Circular 88 dp danger button. A soft pulse ring draws outside its bounds (no layout shift);
 * the pulse and shadow are disabled in Emergency Mode and when animations are removed.
 * Pressing it starts the cancellable 5-second countdown (handled by the caller).
 */
@Composable
fun SosButton(
    label: String,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSahayColors.current
    val calm = LocalEmergency.current || LocalReduceMotion.current
    val pulse = if (calm) 0f else {
        val transition = rememberInfiniteTransition(label = "sosPulse")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
            label = "sosPulseValue",
        )
        value
    }
    Box(
        modifier = modifier
            .size(88.dp)
            .drawBehind {
                if (pulse > 0f) {
                    drawCircle(
                        color = colors.danger.copy(alpha = 0.35f * (1f - pulse)),
                        radius = size.minDimension / 2f * (1f + 0.35f * pulse),
                    )
                }
            }
            .then(if (LocalEmergency.current) Modifier else Modifier.shadow(8.dp, CircleShape, clip = false))
            .clip(CircleShape)
            .background(colors.danger)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = colors.onStatus)
    }
}
