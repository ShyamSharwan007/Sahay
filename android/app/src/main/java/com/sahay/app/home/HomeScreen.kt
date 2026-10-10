package com.sahay.app.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import com.sahay.app.common.formatForecastNumber
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Emergency
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Sos
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.sahay.app.update.APK_DOWNLOAD_URL
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
import com.sahay.designsystem.SahayShapes
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.palette
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

/** Home shows this many precautions; the rest are behind "See all". */
private const val HOME_PRECAUTION_LIMIT = 3

@Composable
fun HomeScreen(
    onOpenAction: (titleRes: Int) -> Unit,
    onDownloadPack: () -> Unit,
    onSeeAllPrecautions: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (state.loading) {
        LoadingState(stringResource(R.string.loading), modifier.padding(SahaySpacing.screenPadding))
        return
    }
    HomeContent(
        state, onOpenAction, onDownloadPack, onSeeAllPrecautions,
        onUpdate = { context.openInBrowser(APK_DOWNLOAD_URL) },
        onDismissUpdate = viewModel::dismissUpdate,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onOpenAction: (Int) -> Unit,
    onDownloadPack: () -> Unit,
    onSeeAllPrecautions: () -> Unit,
    onUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap),
    ) {
        StatusSection(state.status) { onOpenAction(R.string.action_go_safety) }
        if (state.updateAvailable) UpdateBanner(onUpdate, onDismissUpdate)

        if (!state.hasPack) {
            NoPackCard(onDownloadPack)
        } else {
            WeatherSection(state.todayForecast, state.upcomingForecast, state.forecastNote)
        }

        SahayButton(
            text = stringResource(R.string.action_go_safety),
            onClick = { onOpenAction(R.string.action_go_safety) },
            icon = Icons.Rounded.NearMe,
        )
        homeActions.filter { it.label != R.string.action_go_safety }.chunked(2).forEach { row ->
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
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        if (state.precautions.isNotEmpty()) {
            SectionHeader(
                stringResource(R.string.home_precautions),
                actionLabel = if (state.precautions.size > HOME_PRECAUTION_LIMIT) stringResource(R.string.home_see_all) else null,
                onAction = onSeeAllPrecautions,
            )
            PrecautionCards(state.precautions, state.language, limit = HOME_PRECAUTION_LIMIT)
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

/** Today's weather in one line (icon, temperature, rain, wind, risk), then the next days as small pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeatherSection(today: ForecastDay?, upcoming: List<ForecastDay>, note: ForecastNote) {
    val locale = LocalConfiguration.current.locales[0]
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE", locale) }
    Column(verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
        if (today == null) {
            val text = if (note == ForecastNote.BEYOND_RANGE) R.string.trip_forecast_empty else R.string.home_weather_none
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
                verticalArrangement = Arrangement.spacedBy(SahaySpacing.xxs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(weatherIcon(today), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.weather_temp, formatForecastNumber(today.maxTempC)), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.trip_forecast_rain, formatForecastNumber(today.rainMm)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.trip_forecast_wind, formatForecastNumber(today.windKmh)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val (kind, label) = riskPresentation(today.riskLevel)
                StatusChip(kind, stringResource(label))
            }
            if (upcoming.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
                    upcoming.forEach { day ->
                        val (kind, _) = riskPresentation(day.riskLevel)
                        StatusChip(kind, day.date.format(dayFormat) + " · " + stringResource(R.string.trip_forecast_rain, formatForecastNumber(day.rainMm)))
                    }
                }
            }
            ForecastCaption()
        }
    }
}

/** Simple line icon: umbrella for real rain, cloud for a little, sun otherwise. */
private fun weatherIcon(day: ForecastDay): ImageVector = when {
    day.rainMm >= RAIN_HEAVY_MM -> Icons.Outlined.Umbrella
    day.rainMm >= RAIN_LIGHT_MM -> Icons.Outlined.Cloud
    else -> Icons.Outlined.WbSunny
}

private const val RAIN_HEAVY_MM = 10.0
private const val RAIN_LIGHT_MM = 1.0

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

/** Opens [url] in the browser. Does nothing when the phone has no browser. */
private fun Context.openInBrowser(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // no browser: nothing more we can do
    }
}

/** "New version available" with Update and a dismiss cross. */
@Composable
private fun UpdateBanner(onUpdate: () -> Unit, onDismiss: () -> Unit) {
    val palette = StatusKind.Info.palette()
    Surface(shape = SahayShapes.card, color = palette.container, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Download, contentDescription = null, tint = palette.main)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.update_available), style = MaterialTheme.typography.titleMedium)
                SahayButton(
                    text = stringResource(R.string.update_action),
                    onClick = onUpdate,
                    size = ButtonSize.M,
                    fullWidth = false,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.update_dismiss))
            }
        }
    }
}
