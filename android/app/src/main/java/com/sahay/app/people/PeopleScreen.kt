package com.sahay.app.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.GroupOff
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.sahay.R
import com.sahay.app.common.GroupStatusChip
import com.sahay.app.common.SubScreenHeader
import com.sahay.app.common.canGoTo
import com.sahay.app.common.groupSummaryText
import com.sahay.app.common.rememberNowSec
import com.sahay.app.common.timeAgoText
import com.sahay.core.contracts.PeopleGroup
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.EmptyState
import com.sahay.designsystem.components.LoadingState
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.SahayCard
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusKind
import kotlinx.serialization.Serializable

@Serializable
data object PeopleRoute

@Composable
fun PeopleScreen(
    onBack: () -> Unit,
    onGoToGroup: (PeopleGroup) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PeopleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Poll every 2 min, only while the screen is visible and the user has opted in.
    LaunchedEffect(state.optedIn, lifecycleOwner) {
        if (state.optedIn) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollWhileVisible() }
        }
    }

    Column(modifier.fillMaxSize()) {
        SubScreenHeader(stringResource(R.string.action_find_people), onBack)
        if (state.optedIn) {
            GroupList(state, viewModel::refreshNow, onGoToGroup)
        } else {
            OptInExplainer(state, viewModel::turnOn)
        }
    }
}

@Composable
private fun PrivacyLine(minGroupSize: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
        Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.people_privacy, minGroupSize), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OptInExplainer(state: PeopleUiState, onTurnOn: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = SahaySpacing.screenPadding), verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
        PrivacyLine(state.minGroupSize)
        EmptyState(
            icon = Icons.Rounded.GroupOff,
            title = stringResource(R.string.people_optin_title),
            body = stringResource(R.string.people_optin_body),
            actionLabel = stringResource(R.string.action_turn_on),
            onAction = onTurnOn,
        )
        if (state.enableFailed) {
            StatusCard(kind = StatusKind.Danger, title = stringResource(R.string.people_optin_failed))
        }
    }
}

@Composable
private fun GroupList(state: PeopleUiState, onRefresh: () -> Unit, onGoToGroup: (PeopleGroup) -> Unit) {
    val now = rememberNowSec()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap),
    ) {
        item(key = "privacy") { PrivacyLine(state.minGroupSize) }
        state.updatedAtEpochSec?.let { updated ->
            item(key = "updated") {
                Text(
                    stringResource(R.string.updated_ago, timeAgoText(updated, now)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.nearbyPhones > 0) {
            item(key = "phones") { Text(stringResource(R.string.people_nearby_phones, state.nearbyPhones), style = MaterialTheme.typography.bodyMedium) }
        }
        if (state.isStale) {
            item(key = "stale") { StatusCard(kind = StatusKind.Watch, title = stringResource(R.string.people_stale)) }
        }
        if (state.refreshFailed) {
            item(key = "failed") {
                StatusCard(
                    kind = StatusKind.Offline,
                    title = stringResource(R.string.people_refresh_failed),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRefresh,
                )
            }
        }
        when {
            state.noFix && state.rows.isEmpty() -> item(key = "nofix") {
                EmptyState(
                    icon = Icons.Rounded.LocationOff,
                    title = stringResource(R.string.people_no_fix_title),
                    body = stringResource(R.string.people_no_fix_body),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRefresh,
                )
            }
            state.refreshing && state.rows.isEmpty() && state.updatedAtEpochSec == null ->
                item(key = "loading") { LoadingState(stringResource(R.string.loading)) }
            state.rows.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = Icons.Rounded.Group,
                    title = stringResource(R.string.people_empty_title),
                    body = stringResource(R.string.people_empty_body),
                    actionLabel = stringResource(R.string.action_refresh),
                    onAction = onRefresh,
                )
            }
            else -> items(state.rows, key = { it.group.id }) { row -> GroupCard(row, onGoToGroup) }
        }
    }
}

/** "7 people · at a shelter · 400 m" with a status chip and "Go" (never for flood-risk groups). */
@Composable
private fun GroupCard(row: GroupRow, onGo: (PeopleGroup) -> Unit) {
    val group = row.group
    SahayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(SahaySpacing.md), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
            Text(
                groupSummaryText(group, row.distanceM),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            GroupStatusChip(group.status)
            if (group.canGoTo()) {
                SahayButton(
                    text = stringResource(R.string.action_go),
                    onClick = { onGo(group) },
                    icon = Icons.Rounded.NearMe,
                    size = ButtonSize.M,
                    fullWidth = false,
                )
            } else {
                Text(
                    stringResource(R.string.group_dont_go),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
