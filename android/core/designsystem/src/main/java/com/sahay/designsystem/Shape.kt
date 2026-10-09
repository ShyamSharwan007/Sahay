package com.sahay.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** DESIGN §4 corner radii. */
object SahayShapes {
    val card = RoundedCornerShape(20.dp)
    val button = RoundedCornerShape(16.dp)
    val chip = RoundedCornerShape(12.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val pill = CircleShape

    internal val material = Shapes(
        extraSmall = chip,
        small = chip,
        medium = button,
        large = card,
        extraLarge = RoundedCornerShape(28.dp),
    )
}

/** DESIGN §4 spacing scale: 4, 8, 12, 16, 20, 24, 32, 40 dp. */
object SahaySpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 40.dp

    val screenPadding = lg
    val cardGap = sm
}
