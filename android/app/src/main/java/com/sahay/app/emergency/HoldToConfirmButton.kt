package com.sahay.app.emergency

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sahay.designsystem.SahayShapes
import kotlinx.coroutines.launch

const val HOLD_TO_CONFIRM_MS = 2_000

/**
 * XL button that only fires after being held for [holdMillis], with a progress ring around its icon.
 * Letting go early resets the ring. For TalkBack users the long-click action confirms directly.
 */
@Composable
fun HoldToConfirmButton(
    text: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = HOLD_TO_CONFIRM_MS,
    icon: ImageVector = Icons.AutoMirrored.Rounded.ExitToApp,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latestConfirm by rememberUpdatedState(onConfirmed)
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(SahayShapes.button)
            .border(2.dp, scheme.outline, SahayShapes.button)
            .pointerInput(holdMillis) {
                detectTapGestures(onPress = {
                    val hold = scope.launch {
                        progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                        latestConfirm()
                    }
                    tryAwaitRelease()
                    hold.cancel()
                    scope.launch { progress.snapTo(0f) }
                })
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onLongClick(label = text) { latestConfirm(); true }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp,
                color = scheme.primary,
                trackColor = scheme.outline,
            )
            Icon(icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(24.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, modifier = Modifier.weight(1f))
    }
}
