package com.usagelimits.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.graphics.Color
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.Severity
import com.usagelimits.core.settings.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * instead; only the figure tones and the greys are text. Faint is not text ink on Raised:
 * that surface uses Muted. Grounds are resolved from the palette's actual colour resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `text roles and status marks meet their floors on every palette surface`() {
        for (theme in AppTheme.entries) {
            val palette = theme.colors
            val ground = Color(resources.getColor(palette.groundRes, null))
            assertTrue("$theme Faint is not AA text ink on Raised",
                contrast(palette.Faint, palette.Raised) < AA_NORMAL_TEXT)
            val surfaces = mapOf("ground" to ground, "surface" to palette.Surface, "raised" to palette.Raised)
            for ((surface, background) in surfaces) {
                val quiet = if (surface == "raised") palette.Muted else palette.Faint
                for ((label, tone) in mapOf(
                    "ink" to palette.Ink, "muted" to palette.Muted, "quiet" to quiet,
                    "warning" to palette.Warning, "negative" to palette.NegativeText, "data countdown" to palette.DataText,
                )) {
                    val ratio = contrast(tone, background)
                    assertTrue("$theme $label text on $surface: %.2f:1".format(ratio), ratio >= AA_NORMAL_TEXT)
                }
                for (severity in Severity.entries) {
                    val mark = contrast(palette.accent(severity), background)
                    assertTrue("$theme $severity mark on $surface: %.2f:1".format(mark), mark >= AA_GRAPHIC)
                    // Stale and unknown figures use faint on cards, and quiet ink on raised surfaces.
                    val figure = if (surface == "raised" && severity in setOf(Severity.STALE, Severity.UNKNOWN)) quiet
                        else palette.figureColor(severity)
                    val ratio = contrast(figure, background)
                    assertTrue("$theme $severity figure on $surface: %.2f:1".format(ratio), ratio >= AA_NORMAL_TEXT)
                }
                assertTrue("$theme data mark on $surface", contrast(palette.Data, background) >= AA_GRAPHIC)
            }
            assertTrue("$theme data bar on its track", contrast(palette.Data, palette.Rule) >= AA_GRAPHIC)
            assertTrue("$theme inverse ink", contrast(ground, palette.Ink) >= AA_NORMAL_TEXT)
        }
    }

    @Test
    fun `paper uses the prescribed amber red and green status marks`() {
        val paper = AppTheme.LIGHT_PAPER.colors
        assertEquals(Color(0xFF8A5A0A), paper.accent(Severity.LOW))
        assertEquals(Color(0xFFB03A31), paper.accent(Severity.EXHAUSTED))
        assertEquals(Color(0xFF437345), paper.accent(Severity.HEALTHY))
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
                assertTrue("${choice.id}: $severity text measures %.2f:1".format(ratio), ratio >= AA_NORMAL_TEXT)
            }
            val secondary = contrast(style.textSecondary, panel)
            assertTrue("${choice.id}: secondary ink measures %.2f:1".format(secondary), secondary >= AA_NORMAL_TEXT)
            val data = contrast(style.data, panel)
            assertTrue("${choice.id}: data hue measures %.2f:1".format(data), data >= AA_GRAPHIC)
        }
    }

    @Test
    fun `the figure rule keeps full-looking blockers and unknowns off the data fill`() {
        for (theme in AppTheme.entries) {
            val palette = theme.colors
            for (severity in listOf(Severity.EXHAUSTED, Severity.UNKNOWN, Severity.ERROR)) {
                assertNull(palette.barInkFor(severity, 0.15, PercentDisplay.LEFT))
            }
            assertEquals(palette.StaleMark, palette.barInkFor(Severity.STALE, 0.15, PercentDisplay.LEFT))
            assertEquals(palette.Data, palette.barInkFor(Severity.HEALTHY, 0.70, PercentDisplay.LEFT))
            assertEquals(palette.Ink, palette.figureColor(Severity.MEDIUM))
            assertEquals(palette.Warning, palette.figureColor(Severity.LOW))
            assertEquals(palette.NegativeText, palette.figureColor(Severity.EXHAUSTED))
        }
    }
}
