package com.sahay.app.me

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.MainActivity
import com.sahay.R
import com.sahay.app.common.findActivity
import com.sahay.app.onboarding.SupportedLanguages
import com.sahay.core.contracts.PackInfo
import com.sahay.core.contracts.ThemeMode
import com.sahay.core.contracts.UserProfile
import com.sahay.designsystem.SahayShapes
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.LoadingState
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.SahayCard
import com.sahay.designsystem.components.SectionHeader
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusChip
import com.sahay.designsystem.components.StatusKind
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The Me tab: who you are, your trip pack, and the app's settings. */
@Composable
fun MeScreen(
    onEditProfile: () -> Unit,
    onMedicalCard: () -> Unit,
    onTripPack: () -> Unit,
    onPrivacy: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RestartAfterSignOut(state.signedOut)

    val profile = state.profile
    if (profile == null) {
        LoadingState(stringResource(R.string.loading), modifier.padding(SahaySpacing.screenPadding))
        return
    }
    var showLanguages by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap),
    ) {
        ProfileHeader(profile)
        if (state.actionFailed) {
            StatusCard(
                kind = StatusKind.Warning,
                title = stringResource(R.string.me_action_failed),
                actionLabel = stringResource(R.string.action_done),
                onAction = viewModel::dismissFailure,
            )
        }

        SectionHeader(stringResource(R.string.me_section_details))
        MeRow(Icons.Rounded.Edit, stringResource(R.string.me_edit_profile), onEditProfile)
        MeRow(Icons.Rounded.MedicalServices, stringResource(R.string.me_medical_card), onMedicalCard)

        SectionHeader(stringResource(R.string.me_section_trip))
        MeRow(
            icon = Icons.Rounded.Download,
            title = stringResource(R.string.me_trip_pack),
            subtitle = state.pack?.let { tripSummary(it) } ?: stringResource(R.string.me_trip_pack_none),
            onClick = onTripPack,
        )

        SectionHeader(stringResource(R.string.me_section_settings))
        ThemePicker(state.themeMode, viewModel::setTheme)
        MeRow(
            icon = Icons.Rounded.Language,
            title = stringResource(R.string.me_language),
            subtitle = SupportedLanguages.firstOrNull { it.code == profile.language }?.let { stringResource(it.nativeName) },
            onClick = { showLanguages = true },
        )
        MeRow(Icons.Rounded.PrivacyTip, stringResource(R.string.me_privacy), onPrivacy)
        MeRow(Icons.Rounded.Info, stringResource(R.string.me_about), onAbout)

        SahayButton(
            text = stringResource(R.string.me_sign_out),
            onClick = { confirmSignOut = true },
            variant = ButtonVariant.Secondary,
            icon = Icons.AutoMirrored.Rounded.Logout,
            modifier = Modifier.padding(top = SahaySpacing.sm),
        )
    }

    if (showLanguages) {
        LanguageSheet(
            selected = profile.language,
            onSelect = { code ->
                showLanguages = false
                if (code != profile.language) viewModel.setLanguage(code)
            },
            onDismiss = { showLanguages = false },
        )
    }
    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.me_sign_out_title),
            body = stringResource(R.string.me_sign_out_body),
            confirmLabel = stringResource(R.string.me_sign_out),
            onConfirm = { confirmSignOut = false; viewModel.signOut() },
            onDismiss = { confirmSignOut = false },
        )
    }
}

/** After sign-out the whole task restarts, so every screen and ViewModel starts fresh at the language picker. */
@Composable
private fun RestartAfterSignOut(signedOut: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(signedOut) {
        if (signedOut) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            context.findActivity()?.finish()
        }
    }
}

@Composable
private fun ProfileHeader(profile: UserProfile) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = SahaySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SahaySpacing.md),
    ) {
        ProfileAvatar(profile.photoUrl, profile.displayName)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xxs)) {
            Text(
                profile.displayName.ifBlank { stringResource(R.string.me_name_missing) },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (profile.isGuest) {
                StatusChip(StatusKind.Info, stringResource(R.string.me_guest), icon = Icons.Rounded.PersonOutline)
            } else {
                profile.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** "Mahabalipuram · 10 Oct 2026 – 14 Oct 2026". */
@Composable
internal fun tripSummary(pack: PackInfo): String {
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    return stringResource(R.string.trip_ready_summary, pack.regionName, pack.tripStart.format(format), pack.tripEnd.format(format))
}

// ---------------------------------------------------------------- building blocks

/** A tappable settings row: icon, title, optional second line, chevron. At least 56 dp tall. */
@Composable
internal fun MeRow(icon: ImageVector, title: String, onClick: () -> Unit, subtitle: String? = null) {
    SahayCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = SahaySpacing.md, vertical = SahaySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SahayCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(SahaySpacing.md), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
            Text(stringResource(R.string.me_theme), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = mode == selected,
                        onClick = { onSelect(mode) },
                        label = { Text(stringResource(mode.labelRes())) },
                        // A check mark as well as the fill: selection never relies on color alone.
                        leadingIcon = if (mode == selected) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                    )
                }
            }
        }
    }
}

private fun ThemeMode.labelRes() = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSheet(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = SahaySpacing.screenPadding).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.xxs),
        ) {
            Text(
                stringResource(R.string.language_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = SahaySpacing.xs).semantics { heading() },
            )
            SupportedLanguages.forEach { language ->
                val isSelected = language.code == selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(language.code) })
                        .padding(horizontal = SahaySpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(language.nativeName), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (isSelected) {
                        Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.language_selected), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** Two-button confirmation for actions that can't be undone. */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = SahayShapes.card,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
