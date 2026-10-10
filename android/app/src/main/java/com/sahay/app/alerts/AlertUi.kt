package com.sahay.app.alerts

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.sahay.R
import com.sahay.core.contracts.AlertSource
import com.sahay.core.contracts.SahayAlert
import com.sahay.core.contracts.Verification
import com.sahay.designsystem.components.SeverityBadge
import com.sahay.designsystem.components.VerificationBadge

@StringRes
fun severityLabelRes(severity: Int): Int = when {
    severity >= 3 -> R.string.severity_emergency
    severity == 2 -> R.string.severity_warning
    severity == 1 -> R.string.severity_watch
    else -> R.string.severity_info
}

@StringRes
fun Verification.labelRes(): Int = when (this) {
    Verification.VERIFIED_OFFICIAL -> R.string.verif_official
    Verification.MATCHED_OFFICIAL_SMS -> R.string.verif_looks_official
    Verification.UNVERIFIED -> R.string.verif_unverified
}

@StringRes
fun AlertSource.labelRes(): Int = when (this) {
    AlertSource.INTERNET -> R.string.source_internet
    AlertSource.SMS_SERVER -> R.string.source_sms_server
    AlertSource.SMS_OFFICIAL -> R.string.source_sms_official
    AlertSource.MESH -> R.string.source_mesh
    AlertSource.PASTED -> R.string.source_pasted
}

fun AlertSource.icon(): ImageVector = when (this) {
    AlertSource.INTERNET -> Icons.Rounded.Cloud
    AlertSource.SMS_SERVER, AlertSource.SMS_OFFICIAL -> Icons.Rounded.Sms
    AlertSource.MESH -> Icons.Rounded.Sensors
    AlertSource.PASTED -> Icons.Rounded.ContentPaste
}

@Composable
fun AlertSeverityBadge(alert: SahayAlert, modifier: Modifier = Modifier) =
    SeverityBadge(alert.severity, stringResource(severityLabelRes(alert.severity)), modifier)

@Composable
fun AlertVerificationBadge(alert: SahayAlert, modifier: Modifier = Modifier) =
    VerificationBadge(alert.verification, stringResource(alert.verification.labelRes()), modifier)
