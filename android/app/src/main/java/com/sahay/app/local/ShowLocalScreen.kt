package com.sahay.app.local

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.app.common.SubScreenHeader
import com.sahay.app.common.findActivity
import com.sahay.core.contracts.ThemeMode
import com.sahay.designsystem.SahayTheme
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.LoadingState
import com.sahay.designsystem.components.SectionHeader
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusKind
import kotlinx.serialization.Serializable

/** [target] is an encoded NavTarget; null means "the nearest shelter". */
@Serializable
data class ShowLocalRoute(val target: String? = null)

/**
 * A card to hold up to a local person: Tamil first. Always white (readable in sun) and at full
 * brightness while open, whatever the app theme.
 */
@Composable
fun ShowLocalScreen(
    onBack: () -> Unit,
    onDownloadPack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShowLocalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val translation by viewModel.translation.collectAsStateWithLifecycle()
    FullBrightnessWhileOpen()

    // Re-theme this screen only: light colors on white, even in dark or Emergency Mode.
    SahayTheme(themeMode = ThemeMode.LIGHT, emergency = false) {
        Column(modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
            SubScreenHeader(stringResource(R.string.action_show_local), onBack)
            if (state.loading) {
                LoadingState(stringResource(R.string.loading), Modifier.padding(horizontal = SahaySpacing.screenPadding))
            } else {
                LocalTabs(state, translation, viewModel::translate, onDownloadPack)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalTabs(
    state: LocalCardUi,
    translation: TranslateUi,
    onTranslate: (String) -> Unit,
    onDownloadPack: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.White) {
        Tab(
            selected = tab == 0, onClick = { tab = 0 },
            text = { Text(stringResource(R.string.local_tab_card)) },
            icon = { Icon(Icons.Rounded.Style, contentDescription = null) },
        )
        Tab(
            selected = tab == 1, onClick = { tab = 1 },
            text = { Text(stringResource(R.string.local_tab_phrasebook)) },
            icon = { Icon(Icons.Rounded.Translate, contentDescription = null) },
        )
    }
    if (tab == 0) CardTab(state, onDownloadPack) else PhrasebookTab(state.phrases, translation, onTranslate, onDownloadPack)
}

// ---------------------------------------------------------------- tab 1: the card

@Composable
private fun CardTab(state: LocalCardUi, onDownloadPack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.md),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.md),
    ) {
        if (state.tamilHeadline == null) {
            StatusCard(
                kind = StatusKind.Warning,
                title = stringResource(R.string.local_no_tamil_title),
                body = stringResource(R.string.local_no_tamil_body),
                actionLabel = stringResource(R.string.home_no_pack_action),
                onAction = onDownloadPack,
            )
        } else {
            Text(
                state.tamilHeadline,
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(state.ownHeadline ?: stringResource(R.string.local_headline_fallback), style = MaterialTheme.typography.bodyLarge)

        state.destination?.let { poi ->
            HorizontalDivider()
            SectionHeader(stringResource(R.string.local_destination))
            poi.nameTa?.let { Text(it, style = MaterialTheme.typography.headlineMedium) }
            Text(poi.name, style = MaterialTheme.typography.bodyLarge)
        }

        if (!state.hotelName.isNullOrBlank() || !state.hotelAddress.isNullOrBlank()) {
            HorizontalDivider()
            SectionHeader(stringResource(R.string.local_hotel))
            state.hotelName?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            state.hotelAddress?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }

        if (!state.bloodGroup.isNullOrBlank() || !state.allergies.isNullOrBlank()) {
            HorizontalDivider()
            SectionHeader(stringResource(R.string.local_medical))
            state.bloodGroup?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.local_blood_group, it), style = MaterialTheme.typography.bodyLarge)
            }
            state.allergies?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.local_allergies, it), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ---------------------------------------------------------------- brightness

/** Sets the window to full brightness while this screen is open and puts the old setting back after. */
@Composable
private fun FullBrightnessWhileOpen() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        val previous = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
        onDispose {
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = previous } }
        }
    }
}
