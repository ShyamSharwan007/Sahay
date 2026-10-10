package com.sahay.app.trip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.app.common.PrecautionCards
import com.sahay.core.contracts.ForecastDay
import com.sahay.app.common.riskPresentation
import com.sahay.app.common.forecastNote
import com.sahay.app.common.forecastLine
import com.sahay.app.common.ForecastNote
import com.sahay.app.common.ForecastCaption
import com.sahay.core.contracts.Incident
import com.sahay.core.contracts.PackDownloadState
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.Region
import com.sahay.core.contracts.pick
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.EmptyState
import com.sahay.designsystem.components.ErrorState
import com.sahay.designsystem.components.LoadingState
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.SahayCard
import com.sahay.designsystem.components.SahayTopBar
import com.sahay.designsystem.components.SectionHeader
import com.sahay.designsystem.components.StatusChip
import com.sahay.designsystem.components.StatusKind
import com.sahay.designsystem.components.StepItem
import com.sahay.designsystem.components.StepProgress
import com.sahay.designsystem.components.StepState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Region → Dates → Download → Ready. [fromOnboarding] adds a "Skip for now" so nobody is stuck offline
 * before the first download. [onDone] runs from the Ready step (and Back on it); [onExit] leaves from the first step.
 */
@Composable
fun TripSetupScreen(
    fromOnboarding: Boolean,
    onDone: () -> Unit,
    onExit: () -> Unit,
    viewModel: TripSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val step = state.step

    // Back goes one step back; on Ready it finishes, on the first step the system handles it.
    BackHandler(enabled = step != TripStep.REGION) { if (!viewModel.back()) onDone() }

    val title = when (step) {
        TripStep.REGION -> R.string.trip_region_title
        TripStep.DATES -> R.string.trip_dates_title
        TripStep.DOWNLOAD -> R.string.trip_download_title
        TripStep.READY -> R.string.trip_ready_title
    }
    val onBack: (() -> Unit)? = when (step) {
        TripStep.DATES, TripStep.DOWNLOAD -> { { viewModel.back() } }
        TripStep.REGION -> if (fromOnboarding) null else onExit
        TripStep.READY -> null
    }

    Column(Modifier.fillMaxSize()) {
        SahayTopBar(
            title = stringResource(title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.action_back),
        )
        when (step) {
            TripStep.REGION -> RegionStep(state.regions, fromOnboarding, viewModel::selectRegion, viewModel::loadRegions, onSkip = onDone)
            TripStep.DATES -> DatesStep(state.region, state.start, state.end, viewModel::confirmDates)
            TripStep.DOWNLOAD -> DownloadStep(state.download, viewModel::startDownload, viewModel::chooseAnotherRegion)
            TripStep.READY -> state.pack?.let { ReadyStep(it, state.language, onDone) }
        }
    }
}

// ---------------------------------------------------------------- 1. Region

@Composable
private fun RegionStep(
    regions: RegionsUi,
    fromOnboarding: Boolean,
    onSelect: (Region) -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (regions) {
                RegionsUi.Loading -> LoadingState(stringResource(R.string.loading), Modifier.padding(SahaySpacing.screenPadding))
                RegionsUi.Error -> CenteredState {
                    ErrorState(
                        title = stringResource(R.string.trip_regions_error_title),
                        body = stringResource(R.string.trip_regions_error_body),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = onRetry,
                    )
                }
                is RegionsUi.Loaded ->
                    if (regions.regions.isEmpty()) {
                        CenteredState {
                            EmptyState(
                                icon = Icons.Rounded.Place,
                                title = stringResource(R.string.trip_regions_empty_title),
                                body = stringResource(R.string.trip_regions_empty_body),
                                actionLabel = stringResource(R.string.action_retry),
                                onAction = onRetry,
                            )
                        }
                    } else {
                        RegionList(regions.regions, onSelect)
                    }
            }
        }
        if (fromOnboarding) {
            SahayButton(
                text = stringResource(R.string.trip_skip),
                onClick = onSkip,
                variant = ButtonVariant.Ghost,
                modifier = Modifier.padding(horizontal = SahaySpacing.screenPadding).navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        content()
    }
}

@Composable
private fun RegionList(regions: List<Region>, onSelect: (Region) -> Unit) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap),
    ) {
        item {
            Text(
                stringResource(R.string.trip_region_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(regions, key = { it.id }) { region ->
            SahayCard(Modifier.fillMaxWidth(), onClick = { onSelect(region) }) {
                Row(
                    Modifier.padding(SahaySpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
                ) {
                    Icon(Icons.Rounded.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(region.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 2. Dates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatesStep(
    region: Region?,
    initialStart: LocalDate?,
    initialEnd: LocalDate?,
    onConfirm: (LocalDate?, LocalDate?) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart?.let(::dateToUtcMillis),
        initialSelectedEndDateMillis = initialEnd?.let(::dateToUtcMillis),
        selectableDates = remember(today) { FutureDates(today) },
    )
    val start = pickerState.selectedStartDateMillis?.let(::utcMillisToDate)
    val end = pickerState.selectedEndDateMillis?.let(::utcMillisToDate)
    val error = validateTripDates(start, end, today)

    // The picker scrolls by itself, so it must not sit inside another scrollable.
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = SahaySpacing.screenPadding), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xxs)) {
            region?.let { Text(it.name, style = MaterialTheme.typography.titleMedium) }
            Text(
                stringResource(R.string.trip_dates_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DateRangePicker(state = pickerState, modifier = Modifier.weight(1f), showModeToggle = false, title = null)
        Column(
            Modifier.padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.sm).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs),
        ) {
            Text(
                stringResource(R.string.trip_dates_forecast_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Don't nag before the user has picked anything.
            if (error != null && error != TripDateError.MISSING) {
                Text(
                    stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SahayButton(
                text = stringResource(R.string.trip_download_action),
                onClick = { onConfirm(start, end) },
                icon = Icons.Rounded.Download,
                enabled = error == null,
            )
        }
    }
}

/** Only today and later can be picked. */
@OptIn(ExperimentalMaterial3Api::class)
private class FutureDates(private val today: LocalDate) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long) = !utcMillisToDate(utcTimeMillis).isBefore(today)
    override fun isSelectableYear(year: Int) = year >= today.year
}

private fun TripDateError.messageRes(): Int = when (this) {
    TripDateError.MISSING -> R.string.trip_dates_error_missing
    TripDateError.IN_THE_PAST -> R.string.trip_dates_error_past
    TripDateError.END_BEFORE_START -> R.string.trip_dates_error_order
    TripDateError.TOO_LONG -> R.string.trip_dates_error_long
}

// ---------------------------------------------------------------- 3. Download

private val stepTitles = listOf(
    R.string.trip_step_manifest,
    R.string.trip_step_data,
    R.string.trip_step_map,
    R.string.trip_step_assets,
    R.string.trip_step_verify,
)

@Composable
private fun DownloadStep(download: PackDownloadState, onRetry: () -> Unit, onChooseAnother: () -> Unit) {
    if (download is PackDownloadState.Failed) {
        CenteredState {
            if (download.retryable) {
                ErrorState(
                    title = stringResource(R.string.trip_failed_title),
                    body = stringResource(R.string.trip_failed_body),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRetry,
                )
            } else {
                ErrorState(
                    title = stringResource(R.string.trip_failed_title),
                    body = stringResource(R.string.trip_unavailable_body),
                    actionLabel = stringResource(R.string.action_back),
                    onAction = onChooseAnother,
                )
            }
        }
        return
    }

    val percent = downloadPercent(download)
    val done = stringResource(R.string.trip_step_done)
    val active = stringResource(R.string.trip_step_active)
    val pending = stringResource(R.string.trip_step_pending)
    val steps = downloadStepStatuses(download).mapIndexed { i, status ->
        StepItem(
            title = stringResource(stepTitles[i]),
            state = when (status) {
                StepStatus.DONE -> StepState.Done
                StepStatus.ACTIVE -> StepState.Active
                StepStatus.PENDING -> StepState.Pending
            },
            stateDescription = when (status) {
                StepStatus.DONE -> done
                StepStatus.ACTIVE -> active
                StepStatus.PENDING -> pending
            },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(SahaySpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.md),
    ) {
        Text(stringResource(R.string.trip_download_percent, percent), style = MaterialTheme.typography.displaySmall)
        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        StepProgress(steps)
        Text(
            stringResource(R.string.trip_download_wifi),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- 4. Ready

@Composable
private fun ReadyStep(pack: PackInfo, language: String, onDone: () -> Unit) {
    val locale = remember(language) { Locale.current.platformLocale }
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.sm),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap),
        ) {
            item {
                Text(
                    stringResource(R.string.trip_ready_summary, pack.regionName, pack.tripStart.format(dateFormat), pack.tripEnd.format(dateFormat)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SectionHeader(stringResource(R.string.trip_forecast)) }
            when (forecastNote(pack.forecast, pack.tripStart, LocalDate.now())) {
                ForecastNote.SHOWN -> {
                    items(pack.forecast, key = { it.date.toString() }) { day -> ForecastRow(day, dayFormat) }
                    item { ForecastCaption() }
                }
                ForecastNote.UNAVAILABLE_PAST_PATTERNS -> item {
                    Text(stringResource(R.string.home_weather_none), style = MaterialTheme.typography.bodyMedium)
                }
                ForecastNote.BEYOND_RANGE -> item {
                    Text(stringResource(R.string.trip_forecast_empty), style = MaterialTheme.typography.bodyMedium)
                }
            }

            val history = pack.historySummary.pick(language)
            if (history.isNotBlank()) {
                item { SectionHeader(stringResource(R.string.trip_history)) }
                item { Text(history, style = MaterialTheme.typography.bodyLarge) }
            }

            if (pack.incidents.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.trip_incidents)) }
                items(pack.incidents, key = { it.date.toString() + it.type + it.title.pick("en") }) { incident ->
                    IncidentCard(incident, language, dateFormat)
                }
            }

            if (pack.precautions.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.trip_precautions)) }
                item { PrecautionCards(pack.precautions, language) }
            }
        }
        SahayButton(
            text = stringResource(R.string.action_continue),
            onClick = onDone,
            icon = Icons.Rounded.Check,
            modifier = Modifier.padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.sm).navigationBarsPadding(),
        )
    }
}

@Composable
private fun ForecastRow(day: ForecastDay, dayFormat: DateTimeFormatter) {
    SahayCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(SahaySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
        ) {
            Icon(day.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(forecastLine(day, dayFormat), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            val (kind, label) = riskPresentation(day.riskLevel)
            StatusChip(kind, stringResource(label))
        }
    }
}

@Composable
private fun IncidentCard(incident: Incident, language: String, dateFormat: DateTimeFormatter) {
    val uriHandler = LocalUriHandler.current
    SahayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(SahaySpacing.md), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xxs)) {
            Text(incident.date.format(dateFormat), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(incident.title.pick(language), style = MaterialTheme.typography.titleMedium)
            Text(incident.summary.pick(language), style = MaterialTheme.typography.bodyMedium)
            incident.sourceUrl?.let { url ->
                SahayButton(
                    text = stringResource(R.string.trip_incident_source),
                    // No browser installed or a malformed link must not crash the app.
                    onClick = { runCatching { uriHandler.openUri(url) } },
                    variant = ButtonVariant.Ghost,
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    fullWidth = false,
                )
            }
        }
    }
}

private fun ForecastDay.icon(): ImageVector = when {
    rainMm >= 64.5 -> Icons.Rounded.Thunderstorm      // IMD "heavy rain" threshold (CONTRACTS §8.3)
    rainMm >= 1.0 -> Icons.Rounded.WaterDrop
    else -> Icons.Rounded.WbSunny
}

/** Risk level text from the pack → status color + word. Unknown values show a neutral "Unknown". */
