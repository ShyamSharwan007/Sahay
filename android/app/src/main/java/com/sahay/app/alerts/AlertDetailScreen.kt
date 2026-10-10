package com.sahay.app.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.app.common.SubScreenHeader
import com.sahay.app.common.rememberNowSec
import com.sahay.app.common.timeAgoText
import com.sahay.core.contracts.SahayAlert
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.EmptyState
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.SahayCard
import com.sahay.designsystem.components.SimulationTag
import kotlinx.serialization.Serializable

@Serializable
data class AlertDetailRoute(val id: String)

/** Severity from which the alert offers "Go to safety". */
private const val GO_TO_SAFETY_MIN_SEVERITY = 2

@Composable
fun AlertDetailScreen(
    onBack: () -> Unit,
    onGoToSafety: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlertDetailViewModel = hiltViewModel(),
) {
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        SubScreenHeader(stringResource(R.string.nav_alerts), onBack)
        val current = alert
        if (current == null) {
            EmptyState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(R.string.alert_not_found_title),
                body = stringResource(R.string.alert_not_found_body),
                actionLabel = stringResource(R.string.action_back),
                onAction = onBack,
            )
        } else {
            AlertDetailContent(current, onGoToSafety)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertDetailContent(alert: SahayAlert, onGoToSafety: () -> Unit) {
    val now = rememberNowSec()
    var showOriginal by rememberSaveable(alert.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
            AlertSeverityBadge(alert)
            AlertVerificationBadge(alert)
            if (alert.isSimulation) SimulationTag(stringResource(R.string.home_simulation))
        }
        Text(alert.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(alert.body, style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.alert_received, timeAgoText(alert.receivedAtEpochSec, now)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The English line is only useful when the alert was translated.
        if (alert.titleEn != alert.title || alert.bodyEn != alert.body) {
            Column(verticalArrangement = Arrangement.spacedBy(SahaySpacing.xxs)) {
                Text(
                    stringResource(R.string.alert_in_english),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(alert.titleEn, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(alert.bodyEn, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        alert.originalText?.let { original ->
            SahayButton(
                text = stringResource(if (showOriginal) R.string.alert_hide_original else R.string.alert_show_original),
                onClick = { showOriginal = !showOriginal },
                icon = if (showOriginal) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                variant = ButtonVariant.Ghost,
            )
            if (showOriginal) {
                SahayCard(Modifier.fillMaxWidth()) {
                    Text(original, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(SahaySpacing.md))
                }
            }
        }

        if (alert.severity >= GO_TO_SAFETY_MIN_SEVERITY) {
            SahayButton(text = stringResource(R.string.action_go_safety), onClick = onGoToSafety, icon = Icons.Rounded.NearMe)
        }
    }
}
