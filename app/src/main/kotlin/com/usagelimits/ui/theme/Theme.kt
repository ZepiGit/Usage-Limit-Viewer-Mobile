package com.usagelimits.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usagelimits.R
import com.usagelimits.core.model.FillState
import com.usagelimits.core.model.Severity
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.ui.LocalProviderIcons

/**
 * Maps a [Severity] onto the palette.
 *
 * Kept here rather than at each call site so the whole app colours a status identically.
 * Severity is a small signal: a dot, or the colour of one figure. It is never a bar fill.
 */
object SeverityPalette {

    /** The 8dp dot. */
    @Composable
    fun accent(severity: Severity) = LocalUsagePalette.current.accent(severity)

    @Composable
    fun figureColor(severity: Severity) = LocalUsagePalette.current.figureColor(severity)

    @Composable
    fun barInkFor(severity: Severity, fraction: Double?, mode: com.usagelimits.core.model.PercentDisplay): Color? =
        LocalUsagePalette.current.barInkFor(severity, fraction, mode)

    /** Glance projects the same fill state through its own per-widget palette. */
    @Composable
    fun fill(state: FillState): Color? = LocalUsagePalette.current.fill(state)

    /**
     * The English reference [labelRes] is held equal to under `en` by PercentResourcesEnglishTest;
     * nothing on screen reads it.
     */
    fun label(severity: Severity) = when (severity) {
        Severity.HEALTHY -> "Healthy"
        Severity.MEDIUM -> "Moderate"
        Severity.LOW -> "Low"
        Severity.LIMITED -> "Limited"
        Severity.UNKNOWN -> "Unknown"
        Severity.EXHAUSTED -> "Exhausted"
        Severity.STALE -> "Stale"
        Severity.ERROR -> "Error"
    }

    /** [label] as a string resource, so a screen shows it in the user's language. */
    @StringRes
    fun labelRes(severity: Severity): Int = when (severity) {
        Severity.HEALTHY -> R.string.severity_healthy
        Severity.MEDIUM -> R.string.severity_medium
        Severity.LOW -> R.string.severity_low
        Severity.LIMITED -> R.string.severity_limited
        Severity.UNKNOWN -> R.string.severity_unknown
        Severity.EXHAUSTED -> R.string.severity_exhausted
        Severity.STALE -> R.string.severity_stale
        Severity.ERROR -> R.string.severity_error
    }

}

private fun UsagePalette.materialScheme(light: Boolean, ground: Color): ColorScheme =
    (if (light) lightColorScheme() else darkColorScheme()).copy(
        primary = Ink,
        onPrimary = ground,
        secondary = Muted,
        onSecondary = ground,
        secondaryContainer = Raised,
        onSecondaryContainer = Ink,
        background = ground,
        onBackground = Ink,
        surface = Surface,
        onSurface = Ink,
        surfaceVariant = Raised,
        onSurfaceVariant = Muted,
        surfaceContainerLowest = ground,
        surfaceContainerLow = Surface,
        surfaceContainer = Surface,
        surfaceContainerHigh = Raised,
        surfaceContainerHighest = Raised,
        outline = Contour,
        outlineVariant = Rule,
        error = NegativeText,
    )

/** Card 8, control 6, dialog and sheet 12: nothing softer. */
private val UsageShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

private fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, family: FontFamily = FontFamily.SansSerif) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

/**
 * Six sizes (12, 14, 16, 20, 24, 30) in one text face and one figure face (monospace, so money
 * and counts read as ledger columns). Bold is for money only. A number inside a sentence stays
 * in the text face with tabular digits ([bodySmall]).
 */
private val UsageTypography = Typography(
    headlineLarge = text(24, 30, FontWeight.Medium).copy(letterSpacing = (-0.24).sp),
    headlineMedium = text(30, 36, FontWeight.Medium, FontFamily.Monospace),
    titleLarge = text(20, 26, FontWeight.Bold, FontFamily.Monospace),
    titleMedium = text(16, 24, FontWeight.Medium),
    bodyLarge = text(16, 24),
    bodyMedium = text(14, 20),
    bodySmall = text(12, 16).copy(fontFeatureSettings = "tnum"),
    labelLarge = text(14, 20, FontWeight.Medium),
    labelMedium = text(12, 16, FontWeight.Medium),
    labelSmall = text(11, 14, FontWeight.Medium),
)

/** Row figures: a number standing alone in its slot (%, count, clock time). */
object UsageType {
    val Figure = text(14, 20, FontWeight.Medium, FontFamily.Monospace)
    /** A figure that leads a small group: the lead strip's stats, a count on a detail screen. */
    val FigureLarge = text(16, 24, FontWeight.Medium, FontFamily.Monospace)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun UsageLimitsTheme(
    appTheme: AppTheme = AppTheme.PURE_BLACK,
    providerIcons: Map<String, String> = emptyMap(),
    content: @Composable () -> Unit,
) {
    val palette = appTheme.colors
    val ground = palette.Ground
    val scheme = remember(appTheme, ground) { palette.materialScheme(appTheme.isLight, ground) }
    CompositionLocalProvider(
        LocalUsagePalette provides palette,
        LocalAppTheme provides appTheme,
        LocalMotionEnabled provides rememberMotionEnabled(),
        LocalProviderIcons provides providerIcons,
        LocalRippleConfiguration provides remember(palette) {
            RippleConfiguration(palette.Ink, RippleAlpha(0.1f, 0.1f, 0.1f, 0.1f))
        },
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = UsageShapes,
            typography = UsageTypography,
            content = content,
        )
    }
}
