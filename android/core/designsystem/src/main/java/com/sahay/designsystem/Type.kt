package com.sahay.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private fun manrope(weight: FontWeight) = Font(
    resId = R.font.manrope_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Bundled Manrope (OFL, license in res/raw/manrope_ofl.txt). CJK/Arabic/Tamil fall back to system Noto. */
val ManropeFamily = FontFamily(
    manrope(FontWeight.Normal),
    manrope(FontWeight.Medium),
    manrope(FontWeight.SemiBold),
    manrope(FontWeight.Bold),
    manrope(FontWeight.ExtraBold),
)

private fun style(size: Int, line: Int, weight: FontWeight, spacing: TextUnit = 0.sp) = TextStyle(
    fontFamily = ManropeFamily,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = spacing,
)

private val Display = style(34, 40, FontWeight.ExtraBold)
private val Headline = style(26, 32, FontWeight.Bold)
private val Title = style(20, 26, FontWeight.Bold)
private val BodyLarge = style(17, 26, FontWeight.Medium)
private val Body = style(15, 22, FontWeight.Medium)
private val Label = style(14, 18, FontWeight.SemiBold)
private val Caption = style(12, 16, FontWeight.SemiBold, 0.2.sp)

/**
 * DESIGN §3 mapped onto Material roles:
 * display → display*, headline → headline*, title → title*, bodyLarge → bodyLarge,
 * body → bodyMedium/bodySmall, label → labelLarge/Medium, caption → labelSmall.
 */
internal val SahayTypography = Typography(
    displayLarge = Display, displayMedium = Display, displaySmall = Display,
    headlineLarge = Headline, headlineMedium = Headline, headlineSmall = Headline,
    titleLarge = Title, titleMedium = Title, titleSmall = Title,
    bodyLarge = BodyLarge, bodyMedium = Body, bodySmall = Body,
    labelLarge = Label, labelMedium = Label, labelSmall = Caption,
)
