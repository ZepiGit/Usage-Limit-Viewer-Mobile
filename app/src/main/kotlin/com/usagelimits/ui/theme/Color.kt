package com.usagelimits.ui.theme

import androidx.annotation.ColorRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.usagelimits.R
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.core.model.FillState
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ReadingValidity
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.fillState

@Immutable
internal class UsagePalette(
    @ColorRes val groundRes: Int,
    val Surface: Color,
    val Raised: Color,
    val Rule: Color,
    val Contour: Color,
    val Ink: Color,
    val Muted: Color,
    val Faint: Color,
    val Data: Color = Color(0xFFD97757),
    val Positive: Color = Color(0xFF6FBF73),
    val Warning: Color = Color(0xFFE0A33E),
    val Negative: Color = Color(0xFFD9584F),
    val NegativeText: Color = Color(0xFFE8756B),
    val DataText: Color = Data,
) {
    val Ground: Color @Composable get() = colorResource(groundRes)
    val StaleMark: Color get() = Faint

    fun accent(severity: Severity): Color = when (severity) {
        Severity.HEALTHY -> Positive
        Severity.MEDIUM, Severity.LOW, Severity.LIMITED -> Warning
        Severity.EXHAUSTED, Severity.ERROR -> Negative
        Severity.STALE, Severity.UNKNOWN -> StaleMark
    }

    fun figureColor(severity: Severity): Color = when (severity) {
        Severity.HEALTHY, Severity.MEDIUM -> Ink
        Severity.LOW, Severity.LIMITED -> Warning
        Severity.EXHAUSTED, Severity.ERROR -> NegativeText
        Severity.STALE, Severity.UNKNOWN -> Faint
    }

    fun barInkFor(severity: Severity, fraction: Double?, mode: PercentDisplay): Color? =
        fill(fillState(ReadingValidity.CURRENT, severity, fraction, mode))

    fun fill(state: FillState): Color? = when (state) {
        FillState.DATA -> Data
        FillState.STALE -> StaleMark
        FillState.NO_FILL -> null
    }
}

private val warmDark = UsagePalette(
    R.color.appearance_warm_dark_ground, Color(0xFF1A1918), Color(0xFF242322),
    Color(0xFF2E2D2B), Color(0xFF4A4844), Color(0xFFF0EEE6), Color(0xFFB4B2A9), Color(0xFF8A887F),
)
private val pureBlack = UsagePalette(
    R.color.appearance_pure_black_ground, Color(0xFF111111), Color(0xFF1D1D1D),
    Color(0xFF2A2A2A), Color(0xFF474747), Color(0xFFF2F2F2), Color(0xFFB3B3B3), Color(0xFF8C8C8C),
)
private val coolGraphite = UsagePalette(
    R.color.appearance_cool_graphite_ground, Color(0xFF171A1F), Color(0xFF21252B),
    Color(0xFF2B3038), Color(0xFF464C56), Color(0xFFECEFF3), Color(0xFFAEB4BD), Color(0xFF878E98),
)
private val paperInk = Color(0xFF1A1918)
private val paperNegative = Color(0xFFB03A31)
private val lightPaper = UsagePalette(
    R.color.appearance_light_paper_ground, Color.White, Color(0xFFE8E5DC),
    Color(0xFFE3E0D7), Color(0xFFBDB9AE), paperInk, Color(0xFF5C5A53), Color(0xFF6B6960),
    Data = Color(0xFFB8654A), Positive = Color(0xFF437345), Warning = Color(0xFF8A5A0A),
    Negative = paperNegative, NegativeText = paperNegative, DataText = paperInk,
)

internal val AppTheme.colors: UsagePalette get() = when (this) {
    AppTheme.WARM_DARK -> warmDark
    AppTheme.PURE_BLACK -> pureBlack
    AppTheme.COOL_GRAPHITE -> coolGraphite
    AppTheme.LIGHT_PAPER -> lightPaper
}

internal val LocalUsagePalette = compositionLocalOf { pureBlack }
internal val LocalAppTheme = compositionLocalOf { AppTheme.PURE_BLACK }

/** App chrome is ink, terracotta is data, and severity is a mark or a figure, never a fill. */
object UsageColors {
    val Ground: Color @Composable get() = LocalUsagePalette.current.Ground
    val Surface: Color @Composable get() = LocalUsagePalette.current.Surface
    val Raised: Color @Composable get() = LocalUsagePalette.current.Raised
    val Rule: Color @Composable get() = LocalUsagePalette.current.Rule
    val Control: Color @Composable get() = LocalUsagePalette.current.Contour
    val Ink: Color @Composable get() = LocalUsagePalette.current.Ink
    val OnInk: Color @Composable get() = Ground
    val Muted: Color @Composable get() = LocalUsagePalette.current.Muted
    val Faint: Color @Composable get() = LocalUsagePalette.current.Faint
    val Data: Color @Composable get() = LocalUsagePalette.current.Data
    val DataText: Color @Composable get() = LocalUsagePalette.current.DataText
    val Positive: Color @Composable get() = LocalUsagePalette.current.Positive
    val Warning: Color @Composable get() = LocalUsagePalette.current.Warning
    val Negative: Color @Composable get() = LocalUsagePalette.current.Negative
    val NegativeText: Color @Composable get() = LocalUsagePalette.current.NegativeText
}
