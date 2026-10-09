package com.sahay.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sahay.core.contracts.TrustLabel
import com.sahay.core.contracts.Verification
import com.sahay.designsystem.components.BigActionTile
import com.sahay.designsystem.components.BottomBarItem
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.ConnectivityChip
import com.sahay.designsystem.components.ConnectivityLevel
import com.sahay.designsystem.components.EmptyState
import com.sahay.designsystem.components.ErrorState
import com.sahay.designsystem.components.LoadingState
import com.sahay.designsystem.components.OfflineBanner
import com.sahay.designsystem.components.SahayBottomBar
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.SahayTopBar
import com.sahay.designsystem.components.SectionHeader
import com.sahay.designsystem.components.SeverityBadge
import com.sahay.designsystem.components.SosButton
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusKind
import com.sahay.designsystem.components.StepItem
import com.sahay.designsystem.components.StepProgress
import com.sahay.designsystem.components.StepState
import com.sahay.designsystem.components.TrustChip
import com.sahay.designsystem.components.VerificationBadge

/*
 * Developer-only showcase of every component in every state. Sample strings are deliberately
 * inline English: this screen is removed from the app once real navigation lands.
 */

@Composable
fun DesignGalleryScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(bottom = SahaySpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.xl),
    ) {
        GalleryTopBars()
        Column(
            Modifier.padding(horizontal = SahaySpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.xl),
        ) {
            Section("Typography") { TypographySamples() }
            Section("Buttons") { ButtonSamples() }
            Section("Status cards") { StatusCardSamples() }
            Section("Badges and chips") { BadgeSamples() }
            Section("Action tiles") { TileSamples() }
            Section("Emergency button") { SosSamples() }
            Section("Step progress") { StepSamples() }
        }
        OfflineBanner("Offline — using saved data from 10:42")
        Column(
            Modifier.padding(horizontal = SahaySpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.xl),
        ) {
            Section("Empty state") {
                EmptyState(Icons.Rounded.Inbox, "No alerts yet", "You will see official warnings here.", actionLabel = "Check for alerts", onAction = {})
            }
            Section("Error state") {
                ErrorState("Couldn't load alerts", "Check your connection and try again.", "Try again", {})
            }
            Section("Loading state") { LoadingState("Loading alerts") }
        }
        GalleryBottomBar()
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
        SectionHeader(title, actionLabel = null)
        content()
    }
}

@Composable
private fun GalleryTopBars() {
    Column {
        SahayTopBar(title = "Home") {
            ConnectivityChip(ConnectivityLevel.Online, "Online", onClick = {})
        }
        SahayTopBar(title = "Alert details", onBack = {}, backContentDescription = "Back") {
            ConnectivityChip(ConnectivityLevel.SmsOnly, "SMS only", onClick = {})
        }
        SahayTopBar(title = "Map") {
            ConnectivityChip(ConnectivityLevel.Offline, "Offline · 3 phones nearby", onClick = {})
        }
    }
}

@Composable
private fun TypographySamples() {
    val t = MaterialTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
        Text("Stay calm.", style = t.displayLarge)
        Text("Go to safety", style = t.headlineLarge)
        Text("Cyclone warning", style = t.titleLarge)
        Text("Move to the nearest shelter now.", style = t.bodyLarge)
        Text("Walk north on the main road for 650 m.", style = t.bodyMedium)
        Text("Button label", style = t.labelLarge)
        Text("Updated 4 min ago", style = t.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ButtonSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
        SahayButton("Go to safety", {}, icon = Icons.Rounded.Explore)
        SahayButton("Show to a local", {}, variant = ButtonVariant.Secondary, icon = Icons.Rounded.Translate)
        SahayButton("Send SOS", {}, variant = ButtonVariant.Danger, icon = Icons.AutoMirrored.Rounded.Send)
        SahayButton("Skip for now", {}, variant = ButtonVariant.Ghost)
        SahayButton("Saving…", {}, loading = true)
        SahayButton("Not available", {}, enabled = false)
        SahayButton("Medium button", {}, size = ButtonSize.M, fullWidth = false, icon = Icons.Rounded.Flag)
        SahayButton("Cancel", {}, variant = ButtonVariant.Secondary, size = ButtonSize.XL, icon = Icons.Rounded.Close)
        SahayButton("Call 112", {}, variant = ButtonVariant.Danger, size = ButtonSize.XL)
    }
}

@Composable
private fun StatusCardSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap)) {
        StatusCard(StatusKind.Safe, "You're safe", "No warnings near you right now.")
        StatusCard(StatusKind.Watch, "Heavy rain expected", "Keep your phone charged.")
        StatusCard(StatusKind.Warning, "Flooding likely", "Avoid low roads and underpasses.", actionLabel = "See details", onAction = {})
        StatusCard(StatusKind.Danger, "Go to a shelter now", "Flooding in your area. Nearest shelter: 650 m.", actionLabel = "Go to safety", onAction = {})
        StatusCard(StatusKind.Info, "Tip", "Save your hotel address offline.")
        StatusCard(StatusKind.Offline, "You're offline", "Saved maps and shelters still work.")
    }
}

@Composable
private fun BadgeSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SeverityBadge(3, "Emergency")
            SeverityBadge(2, "Warning")
            SeverityBadge(1, "Watch")
            SeverityBadge(0, "Info")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VerificationBadge(Verification.VERIFIED_OFFICIAL, "Verified official")
            VerificationBadge(Verification.MATCHED_OFFICIAL_SMS, "Looks official — not verified")
            VerificationBadge(Verification.UNVERIFIED, "Unverified")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TrustChip(TrustLabel.VERIFIED, "Verified", 0.82f)
            TrustChip(TrustLabel.LIKELY, "Likely", 0.55f)
            TrustChip(TrustLabel.UNCONFIRMED, "Unconfirmed", 0.2f)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnectivityChip(ConnectivityLevel.Online, "Online", {})
            ConnectivityChip(ConnectivityLevel.SmsOnly, "SMS only", {})
            ConnectivityChip(ConnectivityLevel.Offline, "Offline · 3 phones nearby", {})
        }
    }
}

@Composable
private fun TileSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap)) {
            BigActionTile("Go to safety", Icons.Rounded.Explore, {}, Modifier.weight(1f), kind = StatusKind.Safe)
            BigActionTile("Show to a local", Icons.Rounded.Translate, {}, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap)) {
            BigActionTile("Report a hazard", Icons.Rounded.Flag, {}, Modifier.weight(1f), kind = StatusKind.Warning)
            BigActionTile("Find people nearby and stay together", Icons.Rounded.Groups, {}, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SosSamples() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = SahaySpacing.md),
        horizontalArrangement = Arrangement.Center,
    ) {
        SosButton(label = "SOS", onClick = {}, contentDescription = "Send SOS")
    }
}

@Composable
private fun StepSamples() {
    StepProgress(
        listOf(
            StepItem("Choose your region", StepState.Done, "Mahabalipuram", "Done"),
            StepItem("Download map", StepState.Done, "18 MB", "Done"),
            StepItem("Download shelters and roads", StepState.Active, "Almost there…", "In progress"),
            StepItem("Get safety tips", StepState.Pending, null, "Waiting"),
        ),
    )
}

@Composable
private fun GalleryBottomBar() {
    var selected by remember { mutableIntStateOf(0) }
    SahayBottomBar(
        items = listOf(
            BottomBarItem("Home", Icons.Rounded.Home),
            BottomBarItem("Map", Icons.Rounded.Map),
            BottomBarItem("Alerts", Icons.Rounded.Notifications, badgeCount = 3, contentDescription = "Alerts, 3 unread"),
            BottomBarItem("Me", Icons.Rounded.Person),
        ),
        selectedIndex = selected,
        onSelect = { selected = it },
    )
}
