package com.sahay.app.me

import android.content.Context
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.app.common.SubScreenHeader
import com.sahay.app.profile.Relation
import com.sahay.app.profile.label
import com.sahay.core.contracts.EmergencyContact
import com.sahay.core.contracts.PackInfo
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.EmptyState
import com.sahay.designsystem.components.LoadingState
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.SahayCard
import com.sahay.designsystem.components.SectionHeader
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusKind
import kotlinx.serialization.Serializable
import java.text.DateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

@Serializable data class EditProfileRoute(val step: Int = 0)
@Serializable data object MedicalCardRoute
@Serializable data object TripPackRoute
@Serializable data object PrivacyRoute
@Serializable data object AboutRoute
@Serializable data object GalleryRoute

// ---------------------------------------------------------------- medical card (read only)

@Composable
fun MedicalCardScreen(
    onBack: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile = state.profile
    Column(modifier.fillMaxSize()) {
        SubScreenHeader(stringResource(R.string.me_medical_card), onBack)
        if (profile == null) {
            LoadingState(stringResource(R.string.loading), Modifier.padding(horizontal = SahaySpacing.screenPadding))
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap),
        ) {
            StatusCard(StatusKind.Info, stringResource(R.string.privacy_medical_note))
            SahayCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(SahaySpacing.md), verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
                    Fact(stringResource(R.string.medical_blood_group), profile.bloodGroup)
                    Fact(stringResource(R.string.medical_allergies), profile.allergies)
                    Fact(stringResource(R.string.medical_medications), profile.medications)
                    Fact(stringResource(R.string.medical_conditions), profile.conditions)
                }
            }
            SectionHeader(stringResource(R.string.contacts_title))
            if (profile.contacts.isEmpty()) {
                Text(stringResource(R.string.medcard_no_contacts), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            profile.contacts.forEach { ContactCard(it) }
            SahayButton(
                text = stringResource(R.string.me_edit_profile),
                onClick = onEdit,
                variant = ButtonVariant.Secondary,
                icon = Icons.Rounded.Edit,
            )
        }
    }
}

/** A label with its value, or "Not added" when empty. */
@Composable
private fun Fact(label: String, value: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.isNullOrBlank()) {
            Text(stringResource(R.string.medcard_not_added), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ContactCard(contact: EmergencyContact) {
    val relation = Relation.entries.firstOrNull { it.key == contact.relation }?.let { stringResource(it.label()) } ?: contact.relation
    SahayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(SahaySpacing.md)) {
            Text(contact.name, style = MaterialTheme.typography.titleMedium)
            Text(
                listOf(relation, contact.phone).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- trip pack

@Composable
fun TripPackScreen(
    onBack: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val pack = state.pack

    Column(modifier.fillMaxSize()) {
        SubScreenHeader(stringResource(R.string.me_trip_pack), onBack)
        if (pack == null) {
            EmptyState(
                icon = Icons.Rounded.Download,
                title = stringResource(R.string.home_no_pack_title),
                body = stringResource(R.string.home_no_pack_body),
                actionLabel = stringResource(R.string.home_no_pack_action),
                onAction = onUpdate,
            )
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
                verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap),
            ) {
                if (state.actionFailed) {
                    StatusCard(StatusKind.Warning, stringResource(R.string.me_action_failed))
                }
                PackDetails(pack)
                SahayButton(
                    text = stringResource(R.string.trip_pack_update),
                    onClick = onUpdate,
                    icon = Icons.Rounded.Refresh,
                )
                SahayButton(
                    text = stringResource(R.string.trip_pack_delete),
                    onClick = { confirmDelete = true },
                    variant = ButtonVariant.Secondary,
                    icon = Icons.Rounded.Delete,
                )
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.trip_pack_delete_title),
            body = stringResource(R.string.trip_pack_delete_body),
            confirmLabel = stringResource(R.string.trip_pack_delete),
            onConfirm = { confirmDelete = false; viewModel.deletePack() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun PackDetails(pack: PackInfo) {
    val context = LocalContext.current
    val downloaded = remember(pack.downloadedAtEpochSec) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(pack.downloadedAtEpochSec * 1000))
    }
    val dates = remember(pack.tripStart, pack.tripEnd) {
        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        "${pack.tripStart.format(format)} – ${pack.tripEnd.format(format)}"
    }
    SahayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(SahaySpacing.md), verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
            Fact(stringResource(R.string.trip_pack_region), pack.regionName)
            Fact(stringResource(R.string.trip_pack_dates), dates)
            Fact(stringResource(R.string.trip_pack_version), pack.packVersion)
            Fact(stringResource(R.string.trip_pack_size), Formatter.formatFileSize(context, pack.sizeBytes))
            Fact(stringResource(R.string.trip_pack_downloaded), downloaded)
        }
    }
}

// ---------------------------------------------------------------- privacy

@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile = state.profile
    Column(modifier.fillMaxSize()) {
        SubScreenHeader(stringResource(R.string.me_privacy), onBack)
        if (profile == null) {
            LoadingState(stringResource(R.string.loading), Modifier.padding(horizontal = SahaySpacing.screenPadding))
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap),
        ) {
            if (state.actionFailed) StatusCard(StatusKind.Warning, stringResource(R.string.me_action_failed))
            SahayCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(SahaySpacing.md), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(value = profile.groupFinderOptIn, role = Role.Switch, onValueChange = viewModel::setGroupFinder),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SahaySpacing.md),
                    ) {
                        Text(stringResource(R.string.privacy_group_finder), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(checked = profile.groupFinderOptIn, onCheckedChange = null)
                    }
                    Text(
                        stringResource(R.string.privacy_group_finder_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatusCard(StatusKind.Safe, stringResource(R.string.privacy_stays_title), stringResource(R.string.privacy_stays_body))
            StatusCard(StatusKind.Info, stringResource(R.string.privacy_shared_title), stringResource(R.string.privacy_shared_body))
        }
    }
}

// ---------------------------------------------------------------- about & licenses

private class License(val name: String, val descriptionRes: Int, val url: String)

private val licenses = listOf(
    License("OpenStreetMap", R.string.about_osm, "https://www.openstreetmap.org/copyright"),
    License("OpenFreeMap", R.string.about_openfreemap, "https://openfreemap.org"),
    License("Protomaps", R.string.about_protomaps, "https://protomaps.com"),
    License("MapLibre", R.string.about_maplibre, "https://maplibre.org"),
    License("Manrope", R.string.about_manrope, "https://github.com/sharanda/manrope"),
    License("Open-Meteo", R.string.about_openmeteo, "https://open-meteo.com"),
)

private const val GALLERY_TAPS = 7

@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier, onOpenGallery: () -> Unit = {}) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    // Hidden developer screen: tap the version line 7 times.
    var versionTaps by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        SubScreenHeader(stringResource(R.string.me_about), onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap),
        ) {
            Text(
                stringResource(R.string.about_app_line, version),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    versionTaps += 1
                    if (versionTaps >= GALLERY_TAPS) {
                        versionTaps = 0
                        onOpenGallery()
                    }
                },
            )
            SectionHeader(stringResource(R.string.about_licenses))
            licenses.forEach { LicenseCard(it) }
        }
    }
}

@Composable
private fun LicenseCard(license: License) {
    val uriHandler = LocalUriHandler.current
    SahayCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            // No browser installed is not worth a crash.
            try { uriHandler.openUri(license.url) } catch (_: Exception) { }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(SahaySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                Text(license.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(license.descriptionRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.about_open_link), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
} catch (_: PackageManager.NameNotFoundException) {
    ""
}
