package com.usagelimits.ui.theme

import androidx.annotation.FontRes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.usagelimits.R
import com.usagelimits.core.settings.FigureFont
import com.usagelimits.core.settings.TextFont

internal val LocalTextFont = compositionLocalOf { TextFont.ROBOTO }
internal val LocalFigureFont = compositionLocalOf { FigureFont.ROBOTO_MONO }

private val Regular = FontWeight.Normal
private val Medium = FontWeight.Medium
private val Bold = FontWeight.Bold
private val VariableRegular = FontWeight(450)
private val VariableMedium = FontWeight(560)

private fun variableFamily(@FontRes res: Int, vararg weights: FontWeight) = FontFamily(weights.map { weight ->
    Font(res, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
})

internal class TextFace(val family: FontFamily, val regular: FontWeight = Regular, val medium: FontWeight = Medium) {
    fun style(size: Int, line: Int, emphasised: Boolean = false) =
        type(family, if (emphasised) medium else regular, size, line)

    val typography = Typography(
        displayLarge = style(57, 64),
        displayMedium = style(45, 52),
        displaySmall = style(36, 44),
        headlineLarge = style(24, 30, emphasised = true).copy(letterSpacing = (-0.24).sp),
        headlineMedium = style(28, 36),
        headlineSmall = style(24, 32),
        titleLarge = style(22, 28),
        titleMedium = style(16, 24, emphasised = true),
        titleSmall = style(14, 20, emphasised = true),
        bodyLarge = style(16, 24),
        bodyMedium = style(14, 20),
        bodySmall = style(12, 16).copy(fontFeatureSettings = "tnum"),
        labelLarge = style(14, 20, emphasised = true),
        labelMedium = style(12, 16, emphasised = true),
        labelSmall = style(11, 14, emphasised = true),
    )
}

private val textFaces = mapOf(
    TextFont.ROBOTO to TextFace(FontFamily(listOf(VariableRegular, VariableMedium).map { weight ->
        Font(DeviceFontFamilyName("sans-serif"), weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    }), VariableRegular, VariableMedium),
    TextFont.ROBOTO_FLEX to TextFace(variableFamily(R.font.roboto_flex, VariableRegular, VariableMedium), VariableRegular, VariableMedium),
    TextFont.IBM_PLEX_SANS to TextFace(FontFamily(Font(R.font.ibm_plex_sans_regular, Regular), Font(R.font.ibm_plex_sans_medium, Medium))),
    TextFont.FIGTREE to TextFace(variableFamily(R.font.figtree, VariableRegular, VariableMedium), VariableRegular, VariableMedium),
)

internal val TextFont.face: TextFace get() = textFaces.getValue(this)

internal class FigureFace(family: FontFamily) {
    val Regular = type(family, FontWeight.Normal, 14, 20)
    val Figure = type(family, Medium, 14, 20)
    val FigureLarge = type(family, Medium, 16, 24)
    val PreviewFigure = type(family, Medium, 18, 22)
    val GridFigure = type(family, Medium, 13, 18)
    val Stamp = type(family, FontWeight.Normal, 12, 16)
    val Money = type(family, Bold, 20, 26)
    val Code = type(family, Medium, 30, 36)
}

private val figureFaces = mapOf(
    FigureFont.ROBOTO_MONO to FigureFace(variableFamily(R.font.roboto_mono, Regular, Medium, Bold)),
    FigureFont.JETBRAINS_MONO to FigureFace(variableFamily(R.font.jetbrains_mono, Regular, Medium, Bold)),
    FigureFont.IBM_PLEX_MONO to FigureFace(FontFamily(
        Font(R.font.ibm_plex_mono_regular, Regular), Font(R.font.ibm_plex_mono_medium, Medium),
        Font(R.font.ibm_plex_mono_bold, Bold),
    )),
)

internal val FigureFont.face: FigureFace get() = figureFaces.getValue(this)

private fun type(family: FontFamily, weight: FontWeight, size: Int, line: Int) = TextStyle(
    fontFamily = family, fontWeight = weight, fontSynthesis = FontSynthesis.None,
    fontSize = size.sp, lineHeight = line.sp,
)

/** Figures standing alone, including stamps, money and codes. Numbers in prose use the text face. */
object UsageType {
    private val current: FigureFace @Composable get() = LocalFigureFont.current.face
    val Regular: TextStyle @Composable get() = current.Regular
    val Figure: TextStyle @Composable get() = current.Figure
    val FigureLarge: TextStyle @Composable get() = current.FigureLarge
    val PreviewFigure: TextStyle @Composable get() = current.PreviewFigure
    val GridFigure: TextStyle @Composable get() = current.GridFigure
    val Stamp: TextStyle @Composable get() = current.Stamp
    val Money: TextStyle @Composable get() = current.Money
    val Code: TextStyle @Composable get() = current.Code
}
