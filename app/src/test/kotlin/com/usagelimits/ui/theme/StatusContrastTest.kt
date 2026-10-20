package com.usagelimits.ui.theme

import androidx.compose.ui.graphics.Color
import com.usagelimits.core.model.Severity
import com.usagelimits.widget.WidgetStyle
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Every word and figure must clear WCAG 1.4.3 AA on the ground it is drawn on.
 *
 * The StatusMark word, the row figure and the stamp are 12-14sp: normal text, so 4.5:1, not the
 * 3:1 large-text allowance. Severity hues are dots now, so they are held to the 3:1 graphic floor
 * instead; only the figure tones and the greys are text. The maths is pure Kotlin, so this stays
 * a plain JVM test.
 */
class StatusContrastTest {

    private companion object {
        const val AA_NORMAL_TEXT = 4.5
        const val AA_GRAPHIC = 3.0
    }

    /** WCAG 2.x relative luminance. */
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    @Test
    fun `every row figure tone clears AA on the card and on the ground`() {
        for (ground in listOf(UsageColors.Surface, UsageColors.Ground)) {
            for (severity in Severity.entries) {
                val ratio = contrast(SeverityPalette.figureColor(severity), ground)
                assertTrue("$severity figure measures %.2f:1 over $ground".format(ratio), ratio >= AA_NORMAL_TEXT)
            }
        }
    }

    @Test
    fun `the greys that carry words clear AA where they are used`() {
        for (ground in listOf(UsageColors.Surface, UsageColors.Ground)) {
            for ((name, tone) in mapOf("Muted" to UsageColors.Muted, "Faint" to UsageColors.Faint,
                "NegativeText" to UsageColors.NegativeText)) {
                val ratio = contrast(tone, ground)
                assertTrue("$name measures %.2f:1 over $ground".format(ratio), ratio >= AA_NORMAL_TEXT)
            }
        }
        // Faint is for the card and the ground only: on Raised it falls under AA, which is why
        // nothing is stamped on a selected segment or a dialog in it.
        assertTrue(contrast(UsageColors.Faint, UsageColors.Raised) < AA_NORMAL_TEXT)
        assertTrue(contrast(UsageColors.Muted, UsageColors.Raised) >= AA_NORMAL_TEXT)
        assertTrue(contrast(UsageColors.OnInk, UsageColors.Ink) >= AA_NORMAL_TEXT)
    }

    @Test
    fun `severity dots and the data hue are visible marks on the card`() {
        for (severity in Severity.entries) {
            val ratio = contrast(SeverityPalette.accent(severity), UsageColors.Surface)
            assertTrue("$severity dot measures %.2f:1 on Surface".format(ratio), ratio >= AA_GRAPHIC)
        }
        assertTrue(contrast(UsageColors.Data, UsageColors.Surface) >= AA_GRAPHIC)
        // Terracotta on the track is the weakest pairing of a bar; it must stay a visible edge.
        assertTrue(contrast(UsageColors.Data, UsageColors.Rule) >= AA_GRAPHIC)
    }

    /**
     * The widget palette is a deliberate copy of the app's (Glance cannot read the app theme),
     * so it has to be held to the same floor on every panel the user can choose.
     */
    @Test
    fun `widget words clear AA and the data hue clears 3 to 1 on every panel`() {
        for (choice in WidgetStyle.CHOICES) {
            val style = WidgetStyle(com.usagelimits.widget.WidgetBackground(choice.argb, 100))
            val panel = Color(choice.argb)
            for (severity in Severity.entries) {
                val ratio = contrast(style.textColor(severity), panel)
                assertTrue("${choice.label}: $severity text measures %.2f:1".format(ratio), ratio >= AA_NORMAL_TEXT)
            }
            val secondary = contrast(style.textSecondary, panel)
            assertTrue("${choice.label}: secondary ink measures %.2f:1".format(secondary), secondary >= AA_NORMAL_TEXT)
            val data = contrast(style.data, panel)
            assertTrue("${choice.label}: data hue measures %.2f:1".format(data), data >= AA_GRAPHIC)
        }
    }

    @Test
    fun `the figure rule keeps full-looking blockers and unknowns off the data fill`() {
        org.junit.Assert.assertNull(SeverityPalette.barFill(Severity.EXHAUSTED))
        org.junit.Assert.assertNull(SeverityPalette.barFill(Severity.UNKNOWN))
        org.junit.Assert.assertNull(SeverityPalette.barFill(Severity.ERROR))
        org.junit.Assert.assertEquals(UsageColors.StaleMark, SeverityPalette.barFill(Severity.STALE))
        org.junit.Assert.assertEquals(UsageColors.Data, SeverityPalette.barFill(Severity.HEALTHY))
        // Healthy and moderate figures are ink; only the last fifth and a real zero take a hue.
        org.junit.Assert.assertEquals(UsageColors.Ink, SeverityPalette.figureColor(Severity.MEDIUM))
        org.junit.Assert.assertEquals(UsageColors.Warning, SeverityPalette.figureColor(Severity.LOW))
        org.junit.Assert.assertEquals(UsageColors.NegativeText, SeverityPalette.figureColor(Severity.EXHAUSTED))
    }
}
