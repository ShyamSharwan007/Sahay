package com.sahay.app.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.sahay.R
import com.sahay.app.common.GroupStatusChip
import com.sahay.app.common.ShelterStatusChip
import com.sahay.app.common.canGoTo
import com.sahay.app.common.dial
import com.sahay.app.common.distanceText
import com.sahay.app.common.groupPeopleText
import com.sahay.app.common.icon
import com.sahay.app.common.isShelter
import com.sahay.app.common.labelRes
import com.sahay.app.common.rememberNowSec
import com.sahay.app.common.timeAgoText
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.PeopleGroup
import com.sahay.core.contracts.Poi
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.TrustChip

/** Bottom sheet shown after tapping something on the map. [distanceM] is null when there is no location fix. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSelectionSheet(
    selection: MapSelection,
    distanceM: Double?,
    onDismiss: () -> Unit,
    onGoToPoi: (Poi) -> Unit,
    onGoToGroup: (PeopleGroup) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = SahaySpacing.screenPadding)
                .padding(bottom = SahaySpacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
        ) {
            when (selection) {
                is MapSelection.PoiSelection -> PoiSheetContent(selection.poi, distanceM) { onGoToPoi(selection.poi) }
                is MapSelection.ReportSelection -> ReportSheetContent(selection.report)
                is MapSelection.GroupSelection -> GroupSheetContent(selection.group, distanceM) { onGoToGroup(selection.group) }
            }
        }
    }
}

@Composable
private fun DistanceLine(distanceM: Double?) {
    Text(
        text = distanceM?.let { stringResource(R.string.distance_away, distanceText(it)) } ?: stringResource(R.string.distance_unknown),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun PoiSheetContent(poi: Poi, distanceM: Double?, onGo: () -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
        Icon(poi.type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(poi.type.labelRes()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(poi.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    poi.nameTa?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (poi.type.isShelter()) ShelterStatusChip(poi.status)
    DistanceLine(distanceM)
    poi.phone?.let { phone ->
        SahayButton(
            text = stringResource(R.string.action_call_number, phone),
            onClick = { context.dial(phone) },
            icon = Icons.Rounded.Call,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.M,
        )
    }
    SahayButton(text = stringResource(R.string.action_go_here), onClick = onGo, icon = Icons.Rounded.NearMe)
}

@Composable
private fun ReportSheetContent(report: HazardReport) {
    val now = rememberNowSec()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
        Icon(report.type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(report.type.labelRes()),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
    }
    val trustLabel = stringResource(report.label.labelRes())
    val percent = (report.trustScore.coerceIn(0.0, 1.0) * 100).toInt()
    TrustChip(
        trust = report.label,
        label = trustLabel,
        score = report.trustScore.toFloat(),
        description = stringResource(R.string.trust_description, trustLabel, percent),
    )
    if (report.photoPath != null || report.photoUrl != null) {
        com.sahay.app.report.ReportPhotoThumb(report)
        com.sahay.app.report.PhotoReviewChip(report.reviewStatus)
    }
    Text(stringResource(R.string.report_age, timeAgoText(report.createdAtEpochSec, now)), style = MaterialTheme.typography.bodyLarge)
    Text(
        report.note ?: stringResource(R.string.report_no_note),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    if (report.pendingSync) {
        Text(stringResource(R.string.report_pending_send), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun GroupSheetContent(group: PeopleGroup, distanceM: Double?, onGo: () -> Unit) {
    Text(groupPeopleText(group.size), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    GroupStatusChip(group.status)
    DistanceLine(distanceM)
    if (group.canGoTo()) {
        SahayButton(text = stringResource(R.string.action_go), onClick = onGo, icon = Icons.Rounded.NearMe)
    } else {
        Text(
            stringResource(R.string.group_dont_go),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
