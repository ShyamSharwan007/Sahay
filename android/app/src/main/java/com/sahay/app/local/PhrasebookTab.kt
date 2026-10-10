package com.sahay.app.local

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sahay.R
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.ButtonVariant
import com.sahay.designsystem.components.EmptyState
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusKind
import java.util.Locale

private const val COLLAPSED_TAMIL_LINES = 2
private const val COLLAPSED_OWN_LINES = 1

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhrasebookTab(
    phrases: List<PhraseRow>,
    translation: TranslateUi,
    onTranslate: (String) -> Unit,
    onDownloadPack: () -> Unit,
) {
    val speaker = rememberTamilSpeaker()
    val categories = remember(phrases) { phrases.map { it.category }.distinct() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val current = selected?.takeIf { it in categories } ?: categories.firstOrNull()
    val visible = remember(phrases, current) { phrases.filter { it.category == current } }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
    ) {
        if (speaker.voiceMissing) item(key = "voice-missing") { VoiceMissingNotice(speaker) }
        item(key = "translate") { TranslateCard(translation, onTranslate, speaker) }
        if (phrases.isEmpty()) {
            // The translate box still works without a pack; only the phrase list needs it.
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Rounded.Translate,
                    title = stringResource(R.string.local_phrasebook_empty_title),
                    body = stringResource(R.string.local_phrasebook_empty_body),
                    actionLabel = stringResource(R.string.home_no_pack_action),
                    onAction = onDownloadPack,
                )
            }
        } else {
            item(key = "categories") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs)) {
                    categories.forEach { category ->
                        FilterChip(
                            selected = category == current,
                            onClick = { selected = category },
                            label = { Text(categoryName(category)) },
                        )
                    }
                }
            }
            items(visible, key = { it.id }) { PhraseItem(it, speaker) }
        }
    }
}

@Composable
private fun categoryName(category: String): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return categoryLabel(category)?.let { stringResource(it) } ?: category.replaceFirstChar { it.titlecase(locale) }
}

/** Shown when the phone has no Tamil voice. Offers the system voice settings. */
@Composable
private fun VoiceMissingNotice(speaker: TamilSpeaker) {
    val context = LocalContext.current
    var settingsFailed by remember { mutableStateOf(false) }
    StatusCard(
        kind = StatusKind.Warning,
        title = stringResource(R.string.local_voice_missing_title),
        body = stringResource(if (settingsFailed) R.string.local_voice_settings_failed else R.string.local_voice_missing_body),
        actionLabel = stringResource(R.string.local_voice_open_settings),
        onAction = { settingsFailed = !speaker.openVoiceSettings(context) },
    )
}

/** "Say something else": type in your language, get Tamil to show or play. Needs internet. */
@Composable
private fun TranslateCard(translation: TranslateUi, onTranslate: (String) -> Unit, speaker: TamilSpeaker) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = { if (text.isNotBlank() && !translation.loading) onTranslate(text) }
    Column(verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
        Text(stringResource(R.string.local_translate_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_TRANSLATE_CHARS) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.local_translate_hint)) },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        SahayButton(
            text = stringResource(R.string.local_translate_action),
            onClick = submit,
            icon = Icons.Rounded.Translate,
            size = ButtonSize.M,
            loading = translation.loading,
            enabled = text.isNotBlank(),
        )
        if (translation.failed) {
            Text(
                stringResource(R.string.local_translate_error),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        translation.tamil?.let { tamil ->
            Text(tamil, style = MaterialTheme.typography.displaySmall)
            SpeakButton(tamil, speaker)
        }
        HorizontalDivider()
    }
}

@Composable
private fun SpeakButton(tamil: String, speaker: TamilSpeaker) {
    SahayButton(
        text = stringResource(R.string.local_speak),
        onClick = { speaker.speak(tamil) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.M,
        icon = Icons.Rounded.VolumeUp,
        fullWidth = false,
    )
}

/**
 * Icon, then the Tamil text large (to show) and the user's own language small (to read).
 * Tap to read the whole phrase; tap again to fold it back.
 */
@Composable
private fun PhraseItem(phrase: PhraseRow, speaker: TamilSpeaker) {
    var expanded by rememberSaveable(phrase.id) { mutableStateOf(false) }
    val clickLabel = stringResource(if (expanded) R.string.local_phrase_collapse else R.string.local_phrase_expand)
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = clickLabel) { expanded = !expanded }
            .animateContentSize()
            .padding(vertical = SahaySpacing.xxs),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(phraseIcon(phrase.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(
                phrase.tamil,
                style = if (expanded) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_TAMIL_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            if (phrase.own.isNotBlank()) {
                Text(
                    phrase.own,
                    style = if (expanded) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_OWN_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SpeakButton(phrase.tamil, speaker)
        }
    }
    HorizontalDivider()
}
