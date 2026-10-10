package com.sahay.app.profile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sahay.R
import com.sahay.app.common.SmsRestrictedSteps
import com.sahay.app.common.openAppSettings
import com.sahay.designsystem.LocalSahayColors
import com.sahay.designsystem.SahayShapes
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.SahayButton

/** What each permission card needs: which Android permissions to ask for and which strings to show. */
private class PermissionSpec(
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val why: Int,
    @StringRes val deniedHint: Int,
    val permissions: List<String>,
    /** Granted when this permission (one of [permissions]) is granted. Location: only the precise one counts. */
    val required: List<String> = permissions,
    /** Show the "restricted settings" steps when denied (sideloaded apps on Android 13+). */
    val restrictedSteps: Boolean = false,
)

private fun permissionSpecs(): List<PermissionSpec> = buildList {
    add(
        PermissionSpec(
            Icons.Rounded.LocationOn, R.string.permission_location_title, R.string.permission_location_why,
            R.string.permission_location_denied,
            permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            required = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
        ),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(
            PermissionSpec(
                Icons.Rounded.Notifications, R.string.permission_notifications_title, R.string.permission_notifications_why,
                R.string.permission_notifications_denied,
                permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
            ),
        )
    }
    add(
        PermissionSpec(
            Icons.Rounded.Sms, R.string.permission_sms_title, R.string.permission_sms_why,
            R.string.permission_sms_denied,
            permissions = listOf(Manifest.permission.SEND_SMS),
            restrictedSteps = true,
        ),
    )
}

@Composable
internal fun PermissionsStep() {
    StepHeader(stringResource(R.string.permissions_title), stringResource(R.string.permissions_body))
    permissionSpecs().forEach { spec -> PermissionCard(spec) }
}

@Composable
private fun PermissionCard(spec: PermissionSpec) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(spec.isGranted(context)) }
    // True after the user said no once; the system may not ask again, so the button then opens Settings.
    var denied by rememberSaveable { mutableStateOf(false) }

    // Re-check on resume: the user may have changed it in Settings.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = spec.isGranted(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = spec.isGranted(context)
        denied = !granted
    }

    OutlinedCard(shape = SahayShapes.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(SahaySpacing.md), verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
                Icon(spec.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(spec.title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (granted) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.permission_granted), tint = LocalSahayColors.current.safe)
                }
            }
            Text(stringResource(spec.why), style = MaterialTheme.typography.bodyMedium)
            when {
                granted -> Unit
                denied -> {
                    Text(stringResource(spec.deniedHint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (spec.restrictedSteps) SmsRestrictedSteps()
                    SahayButton(
                        text = stringResource(R.string.permission_open_settings),
                        onClick = { context.openAppSettings() },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.M,
                        icon = Icons.Rounded.Settings,
                    )
                }
                else -> SahayButton(
                    text = stringResource(R.string.permission_allow),
                    onClick = { launcher.launch(spec.permissions.toTypedArray()) },
                    size = ButtonSize.M,
                    icon = Icons.Rounded.TouchApp,
                )
            }
        }
    }
}

private fun PermissionSpec.isGranted(context: Context) =
    required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
