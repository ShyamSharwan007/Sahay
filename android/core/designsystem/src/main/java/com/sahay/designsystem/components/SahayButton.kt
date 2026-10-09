package com.sahay.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sahay.designsystem.LocalSahayColors
import com.sahay.designsystem.SahayShapes

enum class ButtonVariant { Primary, Secondary, Danger, Ghost }

/** Heights from DESIGN §5: M 48, L 56 (primary), XL 72 (emergency). Heights are minimums so large fonts never clip. */
enum class ButtonSize(val height: Dp) { M(48.dp), L(56.dp), XL(72.dp) }

@Composable
fun SahayButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    size: ButtonSize = ButtonSize.L,
    icon: ImageVector? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val status = LocalSahayColors.current
    val (container, content) = when (variant) {
        ButtonVariant.Primary -> scheme.primary to scheme.onPrimary
        ButtonVariant.Secondary -> scheme.surfaceVariant to scheme.onSurface
        ButtonVariant.Danger -> status.danger to status.onStatus
        ButtonVariant.Ghost -> Color.Transparent to scheme.primary
    }
    val colors = ButtonDefaults.buttonColors(
        containerColor = container,
        contentColor = content,
        disabledContainerColor = if (variant == ButtonVariant.Ghost) Color.Transparent else scheme.onSurface.copy(alpha = 0.12f),
        disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
    )
    val textStyle = if (size == ButtonSize.XL) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge

    Button(
        // Stays "enabled" while loading so the colors don't flash grey; taps are ignored instead.
        onClick = { if (!loading) onClick() },
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = size.height),
        enabled = enabled,
        shape = SahayShapes.button,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = LocalContentColor.current,
                )
                icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(if (size == ButtonSize.XL) 28.dp else 22.dp))
            }
            Text(text, style = textStyle, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
        }
    }
}
