package com.sahay.app.sos

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContactPhone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.app.common.SubScreenHeader
import com.sahay.app.common.dial
import com.sahay.core.contracts.EmergencyContact
import com.sahay.core.contracts.SahayConfig
import com.sahay.core.contracts.SosResult
import com.sahay.designsystem.LocalSahayColors
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.EmptyState
import com.sahay.designsystem.components.LoadingState
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.SahayCard
import com.sahay.designsystem.components.SectionHeader
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusChip
import com.sahay.designsystem.components.StatusKind
import kotlinx.serialization.Serializable

@Serializable
data object SosRoute

/** 5-second cancellable countdown, then the SOS text. Works with no internet (it is an SMS). */
@Composable
fun SosScreen(
    onBack: () -> Unit,
    onEditContacts: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SosViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.send() else viewModel.smsPermissionDenied()
    }
    LaunchedEffect(state === SosUiState.NeedsSmsPermission) {
        if (state === SosUiState.NeedsSmsPermission) smsPermission.launch(Manifest.permission.SEND_SMS)
    }
    val smsApp = state as? SosUiState.SmsApp
    LaunchedEffect(smsApp?.autoOpen) {
        if (smsApp != null && smsApp.autoOpen) {
            context.openSmsApp(smsApp.phones, smsApp.message)
            viewModel.smsAppOpened()
        }
    }

    Column(modifier.fillMaxSize()) {
        SubScreenHeader(stringResource(R.string.action_sos), onBack = { viewModel.cancelCountdown(); onBack() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.md),
        ) {
            when (val s = state) {
                SosUiState.Loading -> LoadingState(stringResource(R.string.loading), rows = 1)
                SosUiState.NoContacts -> EmptyState(
                    icon = Icons.Rounded.ContactPhone,
                    title = stringResource(R.string.sos_no_contacts_title),
                    body = stringResource(R.string.sos_no_contacts_body),
                    actionLabel = stringResource(R.string.sos_add_contact),
                    onAction = onEditContacts,
                )
                is SosUiState.Countdown -> CountdownContent(s.secondsLeft, contacts, preview)
                SosUiState.NeedsSmsPermission -> SendingContent(stringResource(R.string.sos_permission_title))
                SosUiState.Sending -> SendingContent(stringResource(R.string.sos_sending))
                is SosUiState.Done -> DoneContent(s.result, contacts, onRetry = viewModel::send)
                is SosUiState.SmsApp -> SmsAppContent(s) { context.openSmsApp(s.phones, s.message) }
            }
        }
        BottomActions(
            countdown = state is SosUiState.Countdown,
            onCancel = { viewModel.cancelCountdown(); onBack() },
        )
    }
}

// ---------------------------------------------------------------- countdown

@Composable
private fun CountdownContent(secondsLeft: Int, contacts: List<EmergencyContact>, preview: String?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.sos_sending_in), style = MaterialTheme.typography.titleLarge)
        Text(
            secondsLeft.toString(),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp, lineHeight = 128.sp),
            color = LocalSahayColors.current.danger,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Text(
        stringResource(R.string.sos_to, contacts.joinToString { it.name }),
        style = MaterialTheme.typography.bodyLarge,
    )
    if (!preview.isNullOrBlank()) MessageCard(stringResource(R.string.sos_message_preview), preview)
}

@Composable
private fun SendingContent(label: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = SahaySpacing.xl).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.md),
    ) {
        CircularProgressIndicator()
        Text(label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------- result

@Composable
private fun DoneContent(result: SosResult, contacts: List<EmergencyContact>, onRetry: () -> Unit) {
    val statuses = contacts.map { it to contactStatus(it, result) }
    val failedCount = statuses.count { it.second == ContactSendStatus.FAILED }
    // StatusCard announces the outcome (polite live region) when this state appears.
    when {
        failedCount == 0 -> StatusCard(StatusKind.Safe, stringResource(R.string.sos_sent_title), stringResource(R.string.sos_sent_body))
        failedCount == statuses.size -> StatusCard(StatusKind.Danger, stringResource(R.string.sos_failed_title), stringResource(R.string.sos_failed_body))
        else -> StatusCard(StatusKind.Warning, stringResource(R.string.sos_partial_title), stringResource(R.string.sos_partial_body))
    }
    statuses.forEach { (contact, status) -> ContactResultRow(contact, status) }
    if (failedCount > 0) {
        SahayButton(
            text = stringResource(R.string.sos_retry),
            onClick = onRetry,
            variant = ButtonVariant.Secondary,
            icon = Icons.Rounded.Refresh,
        )
    }
    Text(
        stringResource(if (result.includedLocation != null) R.string.sos_location_included else R.string.sos_location_missing),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (result.message.isNotBlank()) MessageCard(stringResource(R.string.sos_message_sent), result.message)
}

@Composable
private fun ContactResultRow(contact: EmergencyContact, status: ContactSendStatus) {
    SahayCard {
        Row(
            Modifier.fillMaxWidth().padding(SahaySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                Text(contact.name, style = MaterialTheme.typography.titleMedium)
                Text(contact.phone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status == ContactSendStatus.SENT) {
                StatusChip(StatusKind.Safe, stringResource(R.string.sos_contact_sent), icon = Icons.Rounded.Check)
            } else {
                StatusChip(StatusKind.Danger, stringResource(R.string.sos_contact_failed), icon = Icons.Rounded.Cancel)
            }
        }
    }
}

@Composable
private fun SmsAppContent(state: SosUiState.SmsApp, onOpen: () -> Unit) {
    StatusCard(StatusKind.Warning, stringResource(R.string.sos_sms_app_title), stringResource(R.string.sos_sms_app_body))
    SahayButton(
        text = stringResource(R.string.sos_open_sms),
        onClick = onOpen,
        icon = Icons.Rounded.Sms,
        enabled = state.phones.isNotEmpty(),
    )
    if (state.message.isNotBlank()) MessageCard(stringResource(R.string.sos_message_preview), state.message)
}

/** Shows the SMS text word for word, so nothing is sent in secret. */
@Composable
private fun MessageCard(title: String, message: String) {
    SahayCard {
        Column(Modifier.fillMaxWidth().padding(SahaySpacing.md), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
            SectionHeader(title)
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// ---------------------------------------------------------------- bottom buttons

/** During the countdown: Cancel. Otherwise: Call 112, which is always one tap away. */
@Composable
private fun BottomActions(countdown: Boolean, onCancel: () -> Unit) {
    val context = LocalContext.current
    var dialFailed by rememberSaveable { mutableStateOf(false) }
    Column(
        // The bottom bar or MainScaffold already keeps this clear of the navigation bar.
        Modifier.padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs),
    ) {
        if (dialFailed) StatusCard(StatusKind.Warning, stringResource(R.string.sos_dial_failed))
        if (countdown) {
            SahayButton(
                text = stringResource(R.string.sos_cancel),
                onClick = onCancel,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.XL,
                icon = Icons.Rounded.Cancel,
            )
        } else {
            SahayButton(
                text = stringResource(R.string.action_call_number, SahayConfig.EMERGENCY_NUMBER),
                onClick = { dialFailed = !context.dial(SahayConfig.EMERGENCY_NUMBER) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.XL,
                icon = Icons.Rounded.Call,
            )
        }
    }
}

/** Opens the messages app with the SOS text ready to send. False when there is no messages app. */
private fun Context.openSmsApp(phones: List<String>, message: String): Boolean = try {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phones.joinToString(";")))
        .putExtra("sms_body", message)
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}
