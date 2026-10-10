package com.sahay.app.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sahay.R
import com.sahay.core.contracts.Precaution
import com.sahay.core.contracts.pick
import com.sahay.designsystem.LocalEmergency
import com.sahay.designsystem.LocalSahayDark
import com.sahay.designsystem.SahayShapes
import com.sahay.designsystem.components.defaultIcon
import com.sahay.designsystem.components.palette
import com.sahay.designsystem.components.statusKindForSeverity

private const val COLLAPSED_LINES = 2

/**
 * Precautions as compact cards, most severe first. Text falls back to English via [pick].
 * [limit] shows only the first few (Home); null shows all.
 */
@Composable
fun PrecautionCards(precautions: List<Precaution>, language: String, modifier: Modifier = Modifier, limit: Int? = null) {
    val sorted = precautions.sortedByDescending { it.severity }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        (if (limit != null) sorted.take(limit) else sorted).forEach { p ->
            PrecautionCard(p.id, p.severity, p.title.pick(language), p.body.pick(language))
        }
    }
}

/**
 * One precaution: severity icon in a small tinted circle, title and body (2 lines each, body expands with "More").
 * Severity color appears only on the icon and the 4 dp start border (DESIGN §2: no large colored blocks).
 */
@Composable
fun PrecautionCard(id: String, severity: Int, title: String, body: String, modifier: Modifier = Modifier) {
    val palette = statusKindForSeverity(severity).palette()
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    // Only a body that really gets cut off needs the toggle.
    var overflows by rememberSaveable(id) { mutableStateOf(false) }
    val outline = if (!LocalSahayDark.current || LocalEmergency.current) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SahayShapes.card,
        color = MaterialTheme.colorScheme.surface,
        border = outline,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(palette.main))
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(palette.main.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(statusKindForSeverity(severity).defaultIcon(), contentDescription = null, tint = palette.main, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = COLLAPSED_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (body.isNotBlank()) {
                        Text(
                            body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!expanded && it.hasVisualOverflow) overflows = true },
                        )
                        if (overflows) {
                            Text(
                                stringResource(if (expanded) R.string.precaution_less else R.string.precaution_more),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .heightIn(min = 48.dp) // touch target
                                    .clickable(role = Role.Button) { expanded = !expanded }
                                    .padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
