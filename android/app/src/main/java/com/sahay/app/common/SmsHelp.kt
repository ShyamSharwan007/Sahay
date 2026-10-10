package com.sahay.app.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.sahay.R
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.SahayButton

/**
 * How to switch on SMS when Android 13+ blocks it for apps installed outside the Play Store
 * ("restricted settings"). Shared by the SOS screen and the profile Permissions step.
 */
@Composable
fun SmsRestrictedSteps(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SahaySpacing.xxs)) {
        Text(stringResource(R.string.sms_steps_intro), style = MaterialTheme.typography.bodyMedium)
        listOf(R.string.sms_step_1, R.string.sms_step_2, R.string.sms_step_3).forEachIndexed { i, step ->
            Text("${i + 1}. ${stringResource(step)}", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** "Allow SMS for SOS": the steps, a button to the app's settings, and the no-permission fallback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllowSmsSheet(onDismiss: () -> Unit, onOpenSettings: () -> Unit, onMessagesApp: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = SahaySpacing.screenPadding).padding(bottom = SahaySpacing.lg),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.md),
        ) {
            Text(
                stringResource(R.string.sms_allow_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.sms_allow_body), style = MaterialTheme.typography.bodyMedium)
            SmsRestrictedSteps()
            SahayButton(text = stringResource(R.string.sms_open_app_settings), onClick = onOpenSettings, icon = Icons.Rounded.Settings)
            SahayButton(
                text = stringResource(R.string.sos_send_with_messages),
                onClick = onMessagesApp,
                variant = ButtonVariant.Secondary,
                icon = Icons.Rounded.Sms,
            )
        }
    }
}

/** Opens this app's page in Settings. False when the phone has no Settings app. */
fun Context.openAppSettings(): Boolean = try {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** Opens the messages app with [message] ready to send to every number. Needs no permission. False when there is no messages app. */
fun Context.openMessagesApp(phones: List<String>, message: String): Boolean = try {
    startActivity(
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phones.joinToString(";")))
            .putExtra("sms_body", message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}
