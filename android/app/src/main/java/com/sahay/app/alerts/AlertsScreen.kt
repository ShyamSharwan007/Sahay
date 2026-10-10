package com.sahay.app.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.app.common.rememberNowSec
import com.sahay.app.common.timeAgoText
import com.sahay.core.contracts.SahayAlert
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.EmptyState
import com.sahay.designsystem.components.LoadingState
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.SahayCard
import com.sahay.designsystem.components.SimulationTag
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusKind
import com.sahay.designsystem.components.palette
import com.sahay.designsystem.components.statusKindForSeverity

@Composable
fun AlertsScreen(
    onOpenAlert: (String) -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlertsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        SahayButton(
            text = stringResource(R.string.alerts_paste),
            onClick = onPaste,
            icon = Icons.Rounded.ContentPaste,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.M,
            modifier = Modifier.padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
        )
        AlertsList(state, viewModel::refresh, onOpenAlert, Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertsList(state: AlertsUiState, onRefresh: () -> Unit, onOpenAlert: (String) -> Unit, modifier: Modifier) {
    val now = rememberNowSec()
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap),
        ) {
            if (state.refreshFailed) {
                item(key = "refresh_failed") {
                    StatusCard(
                        kind = StatusKind.Offline,
                        title = stringResource(R.string.alerts_refresh_failed_title),
                        body = stringResource(R.string.alerts_refresh_failed_body),
                    )
                }
            }
            when {
                state.loading -> item(key = "loading") { LoadingState(stringResource(R.string.loading)) }
                state.alerts.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.NotificationsNone,
                        title = stringResource(R.string.alerts_empty_title),
                        body = stringResource(R.string.alerts_empty_body),
                        actionLabel = stringResource(R.string.action_refresh),
                        onAction = onRefresh,
                    )
                }
                else -> items(state.alerts, key = { it.id }) { alert ->
                    AlertRow(alert, now, onClick = { onOpenAlert(alert.id) })
                }
            }
        }
    }
}

/** Severity stripe, title, time ago, source, badges and an unread dot. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertRow(alert: SahayAlert, nowSec: Long, onClick: () -> Unit) {
    val stripe = statusKindForSeverity(alert.severity).palette().main
    SahayCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(stripe))
            Column(Modifier.weight(1f).padding(SahaySpacing.md), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
                    Text(
                        alert.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (alert.read) FontWeight.Medium else FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!alert.read) UnreadDot()
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
                    Icon(alert.source.icon(), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(alert.source.labelRes()) + " · " + timeAgoText(alert.issuedAtEpochSec, nowSec),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
                    AlertSeverityBadge(alert)
                    AlertVerificationBadge(alert)
                    if (alert.isSimulation) SimulationTag(stringResource(R.string.home_simulation))
                }
            }
        }
    }
}

@Composable
private fun UnreadDot() {
    val description = stringResource(R.string.alert_unread)
    Box(
        Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .semantics { contentDescription = description },
    )
}
