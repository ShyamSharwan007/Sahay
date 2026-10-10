package com.sahay.app.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusKind

/** Six-step profile wizard. One ViewModel for all steps, so rotation keeps everything typed so far. */
@Composable
fun ProfileWizardScreen(
    onExit: () -> Unit,
    onFinished: () -> Unit,
    viewModel: ProfileWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    // System back goes one step back; on the first step it leaves the wizard.
    BackHandler { if (!viewModel.back()) leave(viewModel, onExit) }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        WizardHeader(state.stepIndex, state.stepCount)
        AnimatedContent(
            targetState = state.step,
            modifier = Modifier.weight(1f),
            label = "wizardStep",
        ) { step ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.md),
                verticalArrangement = Arrangement.spacedBy(SahaySpacing.md),
            ) {
                StepContent(step, state, viewModel)
                if (state.saveFailed) {
                    StatusCard(
                        kind = StatusKind.Warning,
                        title = stringResource(R.string.wizard_save_failed_title),
                        body = stringResource(R.string.wizard_save_failed_body),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = viewModel::retrySave,
                    )
                }
            }
        }
        WizardButtons(
            state = state,
            onBack = { if (!viewModel.back()) leave(viewModel, onExit) },
            onNext = viewModel::next,
            onSkip = viewModel::skipMedical,
        )
    }
}

private fun leave(viewModel: ProfileWizardViewModel, onExit: () -> Unit) {
    viewModel.leave()
    onExit()
}

@Composable
private fun StepContent(step: WizardStep, state: WizardState, vm: ProfileWizardViewModel) {
    when (step) {
        WizardStep.ESSENTIALS -> EssentialsStep(state, vm::setName, vm::setNationality, vm::setPhoneValue)
        WizardStep.MEDICAL -> MedicalStep(
            state, vm::setBloodGroup, vm::setAllergies, vm::setMedications, vm::setConditions, vm::addAllergy, vm::addCondition,
        )
        WizardStep.CONTACTS -> ContactsStep(state, vm::updateContact, vm::addContact, vm::removeContact)
        WizardStep.STAY -> StayStep(state, vm::setHotelName, vm::setHotelAddress)
        WizardStep.PRIVACY -> PrivacyStep(state, vm::setGroupFinder)
        WizardStep.PERMISSIONS -> PermissionsStep()
    }
}

@Composable
private fun WizardHeader(stepIndex: Int, stepCount: Int) {
    Column(
        Modifier.padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.md),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs),
    ) {
        Text(
            stringResource(R.string.wizard_step_of, stepIndex + 1, stepCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { (stepIndex + 1f) / stepCount },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WizardButtons(state: WizardState, onBack: () -> Unit, onNext: () -> Unit, onSkip: () -> Unit) {
    val last = state.step == WizardStep.PERMISSIONS
    Column(
        Modifier.padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.xs),
    ) {
        if (state.step == WizardStep.MEDICAL) {
            SahayButton(
                text = stringResource(R.string.action_skip),
                onClick = onSkip,
                variant = ButtonVariant.Ghost,
                enabled = !state.saving,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
            SahayButton(
                text = stringResource(R.string.action_back),
                onClick = onBack,
                variant = ButtonVariant.Secondary,
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                enabled = !state.saving,
                modifier = Modifier.weight(1f),
            )
            SahayButton(
                text = stringResource(if (last) R.string.action_finish else R.string.action_next),
                onClick = onNext,
                icon = if (last) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward,
                loading = state.saving,
                enabled = state.canProceed,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Marks a required field's label. */
internal fun requiredLabel(label: String) = "$label *"

/** Screen title + one explanatory line + the "* = required" note, shared by every step. */
@Composable
internal fun StepHeader(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(SahaySpacing.xxs)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.wizard_required_note),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
