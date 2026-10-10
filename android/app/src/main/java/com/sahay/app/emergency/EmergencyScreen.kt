package com.sahay.app.emergency

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Sos
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sahay.R
import com.sahay.app.main.connectivityLabel
import com.sahay.app.main.connectivityLevel
import com.sahay.core.contracts.ConnectivityState
import com.sahay.designsystem.LocalSahayColors
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.BigActionTile
import com.sahay.designsystem.components.ConnectivityChip
import com.sahay.designsystem.components.StatusKind

/** Below this the battery line turns red. */
private const val LOW_BATTERY_PERCENT = 15

/**
 * Full-screen Emergency Mode: the four things that matter, big. The theme is already black
 * (set by MainActivity). Leaving needs a 2-second hold so a pocket tap can't end it.
 */
@Composable
fun EmergencyScreen(
    connectivity: ConnectivityState,
    onConnectivityClick: () -> Unit,
    onGoToSafety: () -> Unit,
    onSos: () -> Unit,
    onShowLocal: () -> Unit,
    onFindPeople: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Leaving is a deliberate act: the system back button must not drop the user out of this screen.
    BackHandler {}

    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.md),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.md),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
        ) {
            ConnectivityChip(
                level = connectivityLevel(connectivity),
                label = connectivityLabel(connectivity),
                onClick = onConnectivityClick,
            )
            BatteryLine(Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.emergency_headline),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(vertical = SahaySpacing.xs).semantics { heading() },
        )
        TileRow(
            first = Tile(R.string.action_go_safety, Icons.Rounded.NearMe, null, onGoToSafety),
            second = Tile(R.string.action_sos, Icons.Rounded.Sos, StatusKind.Danger, onSos),
        )
        TileRow(
            first = Tile(R.string.action_show_local, Icons.Rounded.Translate, null, onShowLocal),
            second = Tile(R.string.action_find_people, Icons.Rounded.Group, null, onFindPeople),
        )
        HoldToConfirmButton(
            text = stringResource(R.string.emergency_exit),
            onConfirmed = onExit,
            modifier = Modifier.padding(top = SahaySpacing.md),
        )
        Text(
            stringResource(R.string.emergency_exit_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private class Tile(
    val label: Int,
    val icon: ImageVector,
    val kind: StatusKind?,
    val onClick: () -> Unit,
)

@Composable
private fun TileRow(first: Tile, second: Tile) {
    Row(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap)) {
        listOf(first, second).forEach { tile ->
            BigActionTile(
                label = stringResource(tile.label),
                icon = tile.icon,
                kind = tile.kind,
                onClick = tile.onClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "Battery 64%". Icon and word change when low, so it never relies on color alone. */
@Composable
private fun BatteryLine(modifier: Modifier = Modifier) {
    val percent = rememberBatteryPercent()
    val low = percent != null && percent <= LOW_BATTERY_PERCENT
    val tint = if (low) LocalSahayColors.current.danger else MaterialTheme.colorScheme.onSurface
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xxs, Alignment.End),
    ) {
        Icon(if (low) Icons.Rounded.BatteryAlert else Icons.Rounded.BatteryFull, contentDescription = null, tint = tint, modifier = Modifier.padding(end = 4.dp))
        Text(
            text = if (percent == null) stringResource(R.string.emergency_battery_unknown)
            else stringResource(if (low) R.string.emergency_battery_low else R.string.emergency_battery, percent),
            style = MaterialTheme.typography.labelLarge,
            color = tint,
        )
    }
}
