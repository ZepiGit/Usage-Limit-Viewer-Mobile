package com.usagelimits.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usagelimits.core.model.Severity

/**
 * Maps a [Severity] onto the palette.
 *
 * Kept here rather than at each call site so the whole app colours a status identically.
 * Severity is a small signal: a dot, or the colour of one figure. It is never a bar fill.
 */
object SeverityPalette {

    /** The 8dp dot. */
    fun accent(severity: Severity) = when (severity) {
        Severity.HEALTHY -> UsageColors.Positive
        Severity.MEDIUM, Severity.LOW, Severity.LIMITED -> UsageColors.Warning
        Severity.EXHAUSTED, Severity.ERROR -> UsageColors.Negative
        Severity.STALE, Severity.UNKNOWN -> UsageColors.StaleMark
    }

    /**
     * The colour of a row's remaining-percent figure.
     *
     * Ink until the window is nearly spent, amber at 20% or less, red only for a real zero, and
     * faint when the figure is stale or unknown.
     */
    fun figureColor(severity: Severity) = when (severity) {
        Severity.HEALTHY, Severity.MEDIUM -> UsageColors.Ink
        Severity.LOW, Severity.LIMITED -> UsageColors.Warning
        Severity.EXHAUSTED, Severity.ERROR -> UsageColors.NegativeText
        Severity.STALE, Severity.UNKNOWN -> UsageColors.Faint
    }

    /**
     * Bar fill for a single window: the data hue at the true ratio, a grey when stale, and no
     * fill at all (track only) when the figure is unknown, failed or exhausted. Severity
     * decides over the percentage, so a blocker with a full-looking percentage stays empty.
     */
    fun barFill(severity: Severity): Color? = when (severity) {
        Severity.STALE -> UsageColors.StaleMark
        Severity.UNKNOWN, Severity.ERROR, Severity.EXHAUSTED -> null
        else -> UsageColors.Data
    }

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

}

private val UsageColorScheme = darkColorScheme(
    primary = UsageColors.Ink,
    onPrimary = UsageColors.OnInk,
    secondary = UsageColors.Muted,
    onSecondary = UsageColors.OnInk,
    secondaryContainer = UsageColors.Raised,
    onSecondaryContainer = UsageColors.Ink,
    background = UsageColors.Ground,
    onBackground = UsageColors.Ink,
    surface = UsageColors.Surface,
    onSurface = UsageColors.Ink,
    surfaceVariant = UsageColors.Raised,
    onSurfaceVariant = UsageColors.Muted,
    surfaceContainerLowest = UsageColors.Ground,
    surfaceContainerLow = UsageColors.Surface,
    surfaceContainer = UsageColors.Surface,
    surfaceContainerHigh = UsageColors.Raised,
    surfaceContainerHighest = UsageColors.Raised,
    outline = UsageColors.Control,
    outlineVariant = UsageColors.Rule,
    error = UsageColors.NegativeText,
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

/**
 * The app is dark-only by design.
 *
 * [darkTheme] is accepted so a future light palette can be dropped in without touching call
 * sites, but the AMOLED palette is currently used in both cases — a half-finished light mode
 * would look worse than a deliberate dark one.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun UsageLimitsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    providerIcons: Map<String, String> = emptyMap(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalMotionEnabled provides rememberMotionEnabled(),
        com.usagelimits.ui.LocalProviderIcons provides providerIcons,
        // Pressed state: an ink ripple at 10%, not the stock tinted one.
        LocalRippleConfiguration provides RippleConfiguration(UsageColors.Ink, RippleAlpha(0.1f, 0.1f, 0.1f, 0.1f)),
    ) {
        MaterialTheme(
            colorScheme = UsageColorScheme,
            shapes = UsageShapes,
            typography = UsageTypography,
            content = content,
        )
    }
}
