package com.sahay.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.ContactPhone
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.app.common.SubScreenHeader
import com.sahay.app.me.MeRow
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusKind
import kotlinx.serialization.Serializable

/** The sections of the profile that can be edited one by one (Permissions are not part of the profile). */
val EditableSections: List<WizardStep> = listOf(
    WizardStep.ESSENTIALS, WizardStep.MEDICAL, WizardStep.CONTACTS, WizardStep.STAY, WizardStep.PRIVACY,
)

private fun WizardStep.sectionTitle() = when (this) {
    WizardStep.ESSENTIALS -> R.string.essentials_title
    WizardStep.MEDICAL -> R.string.medical_title
    WizardStep.CONTACTS -> R.string.contacts_title
    WizardStep.STAY -> R.string.stay_title
    WizardStep.PRIVACY -> R.string.privacy_title
    WizardStep.PERMISSIONS -> R.string.permissions_title
}

private fun WizardStep.sectionIcon() = when (this) {
    WizardStep.ESSENTIALS -> Icons.Rounded.Badge
    WizardStep.MEDICAL -> Icons.Rounded.MedicalServices
    WizardStep.CONTACTS -> Icons.Rounded.ContactPhone
    WizardStep.STAY -> Icons.Rounded.Hotel
    WizardStep.PRIVACY -> Icons.Rounded.PrivacyTip
    WizardStep.PERMISSIONS -> Icons.Rounded.Check
}

/** Me → Edit profile: pick one section to change. */
@Serializable
data object EditProfileRoute

/** One section of the profile, prefilled with the saved data. [step] is the ordinal of a [WizardStep]. */
@Serializable
data class EditSectionRoute(val step: Int)

@Composable
fun EditProfileScreen(onBack: () -> Unit, onOpenSection: (WizardStep) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        SubScreenHeader(stringResource(R.string.me_edit_profile), onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap),
        ) {
            EditableSections.forEach { step ->
                MeRow(step.sectionIcon(), stringResource(step.sectionTitle()), onClick = { onOpenSection(step) })
            }
        }
    }
}

/**
 * Edits one section. Reuses the wizard's step content and rules, but Save writes only this section
 * and returns to the caller, which shows "Saved".
 */
@Composable
fun EditSectionScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onSaved() }

    Column(modifier.fillMaxSize().imePadding()) {
        SubScreenHeader(stringResource(R.string.me_edit_profile), onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.md),
        ) {
            StepContent(state.step, state, viewModel)
            if (state.saveFailed) {
                StatusCard(
                    kind = StatusKind.Warning,
                    title = stringResource(R.string.wizard_save_failed_title),
                    body = stringResource(R.string.wizard_save_failed_body),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = viewModel::saveSection,
                )
            }
        }
        SahayButton(
            text = stringResource(R.string.action_save),
            onClick = viewModel::saveSection,
            icon = Icons.Rounded.Check,
            loading = state.saving,
            enabled = state.canProceed,
            modifier = Modifier.padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.sm),
        )
    }
}
