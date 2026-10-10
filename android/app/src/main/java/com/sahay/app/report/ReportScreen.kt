package com.sahay.app.report

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.sahay.designsystem.components.ButtonSize
import com.sahay.designsystem.components.ButtonVariant
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahay.R
import com.sahay.app.common.SubScreenHeader
import com.sahay.app.common.icon
import com.sahay.app.common.labelRes
import com.sahay.core.contracts.HazardReport
import com.sahay.core.contracts.HazardType
import com.sahay.designsystem.SahayShapes
import com.sahay.designsystem.SahaySpacing
import com.sahay.designsystem.components.EmptyState
import com.sahay.designsystem.components.SahayButton
import com.sahay.designsystem.components.StatusCard
import com.sahay.designsystem.components.StatusKind
import com.sahay.designsystem.components.TrustChip
import kotlinx.serialization.Serializable

@Serializable
data object ReportRoute

@Composable
fun ReportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().imePadding()) {
        SubScreenHeader(stringResource(R.string.action_report_hazard), onBack)
        val result = state.result
        if (result != null) ResultContent(result, onBack) else FormContent(state, viewModel)
    }
}

@Composable
private fun FormContent(state: ReportUiState, viewModel: ReportViewModel) {
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = SahaySpacing.screenPadding, vertical = SahaySpacing.xs),
        verticalArrangement = Arrangement.spacedBy(SahaySpacing.sm),
    ) {
        Text(
            stringResource(R.string.report_what),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        // 2-column grid of hazard types (DESIGN §6).
        HazardType.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.cardGap)) {
                row.forEach { type ->
                    HazardTile(type, selected = state.type == type, onClick = { viewModel.selectType(type) }, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        LocationRow(state, onRetry = viewModel::locate)

        PhotoSection(state, viewModel)

        OutlinedTextField(
            value = state.note,
            onValueChange = viewModel::onNoteChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.report_note_label)) },
            supportingText = { Text(stringResource(R.string.report_note_counter, state.note.length, MAX_NOTE_CHARS)) },
            minLines = 2,
            maxLines = 4,
        )

        if (state.submitFailed) {
            StatusCard(kind = StatusKind.Danger, title = stringResource(R.string.report_failed))
        }
        SahayButton(
            text = stringResource(R.string.report_submit),
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.submit() },
            icon = Icons.AutoMirrored.Rounded.Send,
            enabled = state.canSubmit,
            loading = state.submitting,
        )
    }
}

/**
 * "Add photo" (system photo picker, no storage permission) and "Take photo" (camera app writes to a cache file we own,
 * so the app itself never needs the CAMERA permission), with a thumbnail and a remove button once there is a photo.
 */
@Composable
private fun PhotoSection(state: ReportUiState, viewModel: ReportViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onPhoto(uri)
    }
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = capturePath?.let(::File)
        if (taken && file != null) viewModel.onPhoto(Uri.fromFile(file)) // read right away; deleted afterwards by the cache cleanup
        capturePath = null
    }
    val photo = state.photo
    if (photo != null) {
        val thumb = remember(photo) { decodeThumb(photo) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = stringResource(R.string.report_photo_description),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
            SahayButton(
                text = stringResource(R.string.report_remove_photo),
                onClick = viewModel::removePhoto,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.M,
                icon = Icons.Rounded.Close,
                fullWidth = false,
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(SahaySpacing.sm)) {
            SahayButton(
                text = stringResource(R.string.report_add_photo),
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.M,
                icon = Icons.Rounded.AddPhotoAlternate,
                loading = state.photoBusy,
                modifier = Modifier.weight(1f),
            )
            SahayButton(
                text = stringResource(R.string.report_take_photo),
                onClick = {
                    val file = newCaptureFile(context)
                    if (file == null) {
                        viewModel.onPhotoProblem()
                    } else {
                        capturePath = file.path
                        try {
                            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
                        } catch (_: ActivityNotFoundException) {
                            capturePath = null
                            viewModel.onPhotoProblem()
                        }
                    }
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.M,
                icon = Icons.Rounded.PhotoCamera,
                enabled = !state.photoBusy,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (state.photoFailed) StatusCard(kind = StatusKind.Warning, title = stringResource(R.string.report_photo_failed))
}

private fun newCaptureFile(context: Context): File? = try {
    val dir = File(context.cacheDir, "report_capture").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // old captures are of no use any more
    File(dir, "capture_${System.currentTimeMillis()}.jpg")
} catch (_: Exception) {
    null
}

private fun decodeThumb(jpeg: ByteArray) = try {
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
} catch (_: Exception) {
    null
}

/** Selectable tile with icon + word; the selection also shows a check, never color alone. */
@Composable
private fun HazardTile(type: HazardType, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val selectedDescription = stringResource(R.string.report_selected)
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = SahayShapes.card,
        color = if (selected) scheme.primaryContainer else scheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outline),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(SahaySpacing.md),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Icon(type.icon(), contentDescription = null, tint = scheme.primary)
                if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = selectedDescription, tint = scheme.primary)
            }
            Text(stringResource(type.labelRes()), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
        }
    }
}

/** Reports always use the current fix: no map picker. */
@Composable
private fun LocationRow(state: ReportUiState, onRetry: () -> Unit) {
    when {
        state.fix != null -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs),
        ) {
            Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.report_location_label), style = MaterialTheme.typography.bodyLarge)
        }
        state.locating -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SahaySpacing.xs),
        ) {
            Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.report_locating), style = MaterialTheme.typography.bodyLarge)
        }
        else -> StatusCard(
            kind = StatusKind.Warning,
            title = stringResource(R.string.report_no_fix_title),
            body = stringResource(R.string.report_no_fix_body),
            actionLabel = stringResource(R.string.action_retry),
            onAction = onRetry,
        )
    }
}

/** "Sent" or "Saved — will send when connected", with a TrustChip once the score is known. */
@Composable
private fun ResultContent(report: HazardReport, onDone: () -> Unit) {
    val sent = !report.pendingSync
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(
            icon = Icons.Rounded.CheckCircle,
            title = stringResource(if (sent) R.string.report_sent_title else R.string.report_saved_title),
            body = stringResource(if (sent) R.string.report_sent_body else R.string.report_saved_body),
            actionLabel = stringResource(R.string.action_done),
            onAction = onDone,
        )
        if (report.photoPath != null || report.photoUrl != null) {
            ReportPhotoThumb(report, size = 120.dp)
            PhotoReviewChip(report.reviewStatus)
        }
        if (sent) {
            val label = stringResource(report.label.labelRes())
            TrustChip(
                trust = report.label,
                label = label,
                score = report.trustScore.toFloat(),
                description = stringResource(R.string.trust_description, label, (report.trustScore.coerceIn(0.0, 1.0) * 100).toInt()),
            )
        }
    }
}
