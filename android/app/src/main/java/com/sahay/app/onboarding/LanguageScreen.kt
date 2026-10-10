package com.sahay.app.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.designsystem.SahayShapes
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.SahayButton

@Composable
fun LanguageScreen(onContinue: () -> Unit, viewModel: LanguageViewModel = hiltViewModel()) {
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    LanguageContent(
        selected = selected,
        onSelect = viewModel::select,
        // Navigate first: applying the language can recreate the activity, and the nav state is restored.
        onContinue = { onContinue(); viewModel.confirm() },
    )
}

@Composable
private fun LanguageContent(selected: String, onSelect: (String) -> Unit, onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = SahaySpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.md),
    ) {
        Column(Modifier.padding(top = SahaySpacing.xl), verticalArrangement = Arrangement.spacedBy(SahaySpacing.xxs)) {
            Text(stringResource(com.sahay.R.string.language_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(com.sahay.R.string.language_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap),
        ) {
            items(SupportedLanguages, key = { it.code }) { language ->
                LanguageRow(
                    name = stringResource(language.nativeName),
                    selected = language.code == selected,
                    onClick = { onSelect(language.code) },
                )
            }
        }
        SahayButton(
            text = stringResource(com.sahay.R.string.action_continue),
            onClick = onContinue,
            icon = Icons.AutoMirrored.Rounded.ArrowForward,
            modifier = Modifier.padding(bottom = SahaySpacing.md),
        )
    }
}

@Composable
private fun LanguageRow(name: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        shape = SahayShapes.button,
        color = if (selected) scheme.primaryContainer else scheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outline),
    ) {
        Row(
            Modifier.padding(horizontal = SahaySpacing.md, vertical = SahaySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            // Checked and unchecked icons differ in shape, so state never relies on color alone.
            Icon(
                imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (selected) stringResource(com.sahay.R.string.language_selected) else null,
                tint = if (selected) scheme.primary else scheme.outline,
            )
        }
    }
}
