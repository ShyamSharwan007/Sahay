package com.sahay.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.sahay.designsystem.LocalReduceMotion
import com.sahay.designsystem.LocalSahayColors

enum class StepState { Done, Active, Pending }

/** [stateDescription] is the spoken status ("Done", "In progress", "Waiting"), supplied by the caller in the user's language. */
data class StepItem(
    val title: String,
    val state: StepState,
    val subtitle: String? = null,
    val stateDescription: String? = null,
)

/** Vertical checklist with done / active / pending icons (pack download, onboarding). */
@Composable
fun StepProgress(steps: List<StepItem>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            StepRow(step, isLast = index == steps.lastIndex)
        }
    }
}

@Composable
private fun StepRow(step: StepItem, isLast: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val safe = LocalSahayColors.current.safe
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .semantics(mergeDescendants = true) { step.stateDescription?.let { stateDescription = it } },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.fillMaxHeight().width(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StepIcon(step.state)
            if (!isLast) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .clip(CircleShape)
                        .background(if (step.state == StepState.Done) safe else scheme.outline),
                )
            }
        }
        Column(Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 20.dp, top = 2.dp)) {
            Text(
                step.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (step.state == StepState.Pending) scheme.onSurfaceVariant else scheme.onSurface,
            )
            if (step.subtitle != null) {
                Text(step.subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StepIcon(state: StepState) {
    val scheme = MaterialTheme.colorScheme
    val size = Modifier.size(28.dp)
    when (state) {
        StepState.Done -> Icon(Icons.Rounded.CheckCircle, null, size, tint = LocalSahayColors.current.safe)
        StepState.Pending -> Icon(Icons.Rounded.RadioButtonUnchecked, null, size, tint = scheme.outline)
        StepState.Active ->
            if (LocalReduceMotion.current) {
                Icon(Icons.Rounded.RadioButtonChecked, null, size, tint = scheme.primary)
            } else {
                Box(size.padding(3.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp, color = scheme.primary)
                }
            }
    }
}
