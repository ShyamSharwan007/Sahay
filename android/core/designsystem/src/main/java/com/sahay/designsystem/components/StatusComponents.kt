package com.sahay.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sahay.core.contracts.TrustLabel
import com.sahay.core.contracts.Verification
import com.sahay.designsystem.LocalSahayColors
import com.sahay.designsystem.SahayShapes

/**
 * Full-width card with a 6 dp start border in the status color (DESIGN §5).
 * Title and body are announced politely by TalkBack when they change.
 */
@Composable
fun StatusCard(
    kind: StatusKind,
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector = kind.defaultIcon(),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = kind.palette()
    CardSurface(modifier = modifier.fillMaxWidth(), color = palette.container) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(palette.main))
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TintedIcon(icon, palette.main)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        if (body != null) {
                            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (actionLabel != null && onAction != null) {
                        SahayButton(
                            text = actionLabel,
                            onClick = onAction,
                            variant = ButtonVariant.Secondary,
                            size = ButtonSize.M,
                            fullWidth = false,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Pill with icon + word, e.g. "Emergency". [severity] is 0..3; [label] is the localized word. */
@Composable
fun SeverityBadge(severity: Int, label: String, modifier: Modifier = Modifier) {
    val kind = statusKindForSeverity(severity)
    val palette = kind.palette()
    Pill(kind.defaultIcon(), label, palette.main, palette.container, modifier)
}

/** Official-ness of an alert. Never implies verification for [Verification.MATCHED_OFFICIAL_SMS]. */
@Composable
fun VerificationBadge(verification: Verification, label: String, modifier: Modifier = Modifier) {
    when (verification) {
        Verification.VERIFIED_OFFICIAL -> {
            val p = StatusKind.Safe.palette()
            Pill(Icons.Rounded.VerifiedUser, label, p.main, p.container, modifier)
        }
        Verification.MATCHED_OFFICIAL_SMS -> {
            val p = StatusKind.Watch.palette()
            Pill(Icons.Rounded.Shield, label, p.main, p.container, modifier)
        }
        Verification.UNVERIFIED -> Pill(
            icon = Icons.Rounded.QuestionMark,
            label = label,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            containerColor = Color.Transparent,
            modifier = modifier,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        )
    }
}

/** "Verified" / "Likely" / "Unconfirmed" plus a small 0..1 score bar. [description] is spoken instead of the bare label. */
@Composable
fun TrustChip(
    trust: TrustLabel,
    label: String,
    score: Float,
    modifier: Modifier = Modifier,
    description: String = label,
) {
    val colors = LocalSahayColors.current
    val scheme = MaterialTheme.colorScheme
    val (main, container) = when (trust) {
        TrustLabel.VERIFIED -> colors.safe to colors.safeContainer
        TrustLabel.LIKELY -> colors.warning to colors.warningContainer
        TrustLabel.UNCONFIRMED -> scheme.onSurfaceVariant to scheme.surfaceVariant
    }
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        shape = SahayShapes.pill,
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = main)
            Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(main.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(score.coerceIn(0f, 1f)).background(main))
            }
        }
    }
}

enum class ConnectivityLevel { Online, SmsOnly, Offline }

/**
 * Top-bar pill: "Online" (safe dot) / "SMS only" (watch dot) / "Offline · 3 phones nearby" (outline dot).
 * Tapping opens a sheet explaining what still works (the caller shows it).
 */
@Composable
fun ConnectivityChip(
    level: ConnectivityLevel,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Smaller padding so the chip leaves room for a title and other actions. */
    compact: Boolean = false,
) {
    val colors = LocalSahayColors.current
    val scheme = MaterialTheme.colorScheme
    // 48 dp touch target around a smaller visual pill.
    Box(modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = SahayShapes.pill,
            color = scheme.surfaceVariant,
            border = BorderStroke(1.dp, scheme.outline),
        ) {
            Row(
                modifier = Modifier.padding(
                    start = if (compact) 8.dp else 12.dp, end = if (compact) 10.dp else 14.dp,
                    top = if (compact) 5.dp else 7.dp, bottom = if (compact) 5.dp else 7.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
            ) {
                ConnectivityDot(level)
                Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, maxLines = 1)
            }
        }
    }
}

/** Filled dot for Online / SMS only, hollow ring for Offline (never color alone: the label says it too). */
@Composable
private fun ConnectivityDot(level: ConnectivityLevel) {
    val colors = LocalSahayColors.current
    val dot = Modifier.size(10.dp).clip(CircleShape)
    when (level) {
        ConnectivityLevel.Online -> Box(dot.background(colors.safe))
        ConnectivityLevel.SmsOnly -> Box(dot.background(colors.watch))
        ConnectivityLevel.Offline -> Box(dot.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
    }
}
