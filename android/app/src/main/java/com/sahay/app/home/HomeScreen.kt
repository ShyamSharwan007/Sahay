package com.sahay.app.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Emergency
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Sos
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.app.common.PrecautionCards
import com.sahay.core.contracts.ForecastDay
import androidx.compose.ui.Alignment
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.remember
import com.sahay.designsystem.components.StatusChip
import com.sahay.app.common.riskPresentation
import com.sahay.app.common.forecastLine
import com.sahay.app.common.ForecastNote
import com.sahay.app.common.ForecastCaption
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.BigActionTile
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.LoadingState
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.SahayCard
import com.sahay.designsystem.components.SectionHeader
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusKind
import java.util.Locale

/** Quick actions on Home. Their screens are built in later tasks; for now they open a placeholder. */
private data class HomeAction(@StringRes val label: Int, val icon: ImageVector, val kind: StatusKind? = null)

private val homeActions = listOf(
    HomeAction(R.string.action_go_safety, Icons.Rounded.NearMe),
    HomeAction(R.string.action_sos, Icons.Rounded.Sos, StatusKind.Danger),
    HomeAction(R.string.action_show_local, Icons.Rounded.Translate),
    HomeAction(R.string.action_report_hazard, Icons.Rounded.Flag),
    HomeAction(R.string.action_find_people, Icons.Rounded.Group),
    HomeAction(R.string.action_emergency_mode, Icons.Rounded.Emergency, StatusKind.Danger),
)

@Composable
fun HomeScreen(
    onOpenAction: (titleRes: Int) -> Unit,
    onDownloadPack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.loading) {
        LoadingState(stringResource(R.string.loading), modifier.padding(SahaySpacing.screenPadding))
        return
    }
    HomeContent(state, onOpenAction, onDownloadPack, modifier)
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onOpenAction: (Int) -> Unit,
    onDownloadPack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap),
    ) {
        StatusSection(state.status) { onOpenAction(R.string.action_go_safety) }

        if (!state.hasPack) {
            NoPackCard(onDownloadPack)
        } else {
            WeatherLine(state.todayForecast, state.forecastNote)
        }

        homeActions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap)) {
                row.forEach { action ->
                    BigActionTile(
                        label = stringResource(action.label),
                        icon = action.icon,
                        kind = action.kind,
                        onClick = { onOpenAction(action.label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (state.precautions.isNotEmpty()) {
            SectionHeader(stringResource(R.string.home_precautions))
            PrecautionCards(state.precautions, state.language)
        }
    }
}

/** The "Am I safe?" card, plus a Simulation tag when the alert behind it is a drill. */
@Composable
private fun StatusSection(status: HomeStatus, onGoToSafety: () -> Unit) {
    val goToSafety = stringResource(R.string.action_go_safety)
    when (status) {
        is HomeStatus.Danger -> {
            StatusCard(
                kind = StatusKind.Danger,
                title = status.alert.title,
                body = status.alert.body,
                actionLabel = goToSafety,
                onAction = onGoToSafety,
                tag = if (status.alert.isSimulation) stringResource(R.string.home_simulation) else null,
            )
        }
        is HomeStatus.Warning -> {
            val alert = status.alert
            StatusCard(
                kind = StatusKind.Warning,
                title = if (status.inFloodZone) stringResource(R.string.home_zone_title) else alert?.title.orEmpty(),
                body = when {
                    !status.inFloodZone -> alert?.body ?: stringResource(R.string.home_warning_generic_body)
                    alert != null -> alert.title
                    else -> stringResource(R.string.home_zone_body)
                },
                actionLabel = if (status.inFloodZone) goToSafety else null,
                onAction = if (status.inFloodZone) onGoToSafety else null,
                tag = if (alert?.isSimulation == true) stringResource(R.string.home_simulation) else null,
            )
        }
        HomeStatus.Safe -> StatusCard(
            kind = StatusKind.Safe,
            title = stringResource(R.string.home_safe_title),
            body = stringResource(R.string.home_safe_body),
        )
    }
}

@Composable
private fun WeatherLine(day: ForecastDay?, note: ForecastNote) {
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(SahaySpacing.xxs)) {
        if (day == null) {
            val text = if (note == ForecastNote.BEYOND_RANGE) R.string.trip_forecast_empty else R.string.home_weather_none
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
                Text(
                    forecastLine(day, dayFormat),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val (kind, label) = riskPresentation(day.riskLevel)
                StatusChip(kind, stringResource(label))
            }
            ForecastCaption()
        }
    }
}

@Composable
private fun NoPackCard(onDownload: () -> Unit) {
    SahayCard {
        Column(Modifier.padding(SahaySpacing.md), verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
            Text(
                stringResource(R.string.home_no_pack_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.home_no_pack_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SahayButton(
                text = stringResource(R.string.home_no_pack_action),
                onClick = onDownload,
                icon = Icons.Rounded.Download,
                variant = ButtonVariant.Primary,
            )
        }
    }
}
