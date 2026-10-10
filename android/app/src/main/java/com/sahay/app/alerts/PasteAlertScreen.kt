package com.sahay.app.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.app.common.SubScreenHeader
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.SahayButton
import kotlinx.serialization.Serializable

@Serializable
data object PasteAlertRoute

@Composable
fun PasteAlertScreen(
    onBack: () -> Unit,
    onOpenAlert: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PasteAlertViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.opened.collect(onOpenAlert) }

    Column(modifier.fillMaxSize().imePadding()) {
        SubScreenHeader(stringResource(R.string.alerts_paste), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
            verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
        ) {
            Text(stringResource(R.string.paste_body), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::onTextChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.paste_hint)) },
                minLines = 5,
                maxLines = 10,
                isError = state.showEmptyError,
                supportingText = {
                    Text(
                        if (state.showEmptyError) stringResource(R.string.paste_empty_error)
                        else stringResource(R.string.report_note_counter, state.text.length, MAX_PASTE_CHARS),
                    )
                },
                enabled = !state.translating,
            )
            SahayButton(
                text = stringResource(R.string.paste_action),
                onClick = viewModel::submit,
                icon = Icons.Rounded.Translate,
                loading = state.translating,
            )
        }
    }
}
