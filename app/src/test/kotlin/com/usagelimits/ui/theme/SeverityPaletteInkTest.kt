package com.usagelimits.ui.theme

import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.Severity
import com.usagelimits.core.settings.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bar ink decision in both modes (D4).
 *
 * LEFT is pinned to the historical bar fill for every severity and fraction, so the historical
 * rendering — severity decides over the percentage, a blocker stays an empty track — cannot drift
 * when the USED fallback changes. USED differs in exactly one place: a nil severity ink with a
 * positive displayed fraction, which only an exhausted window reaches.
 */
class SeverityPaletteInkTest {
    private val palette = AppTheme.PURE_BLACK.colors

    @Test
    fun `LEFT mode ink is exactly the historical bar fill for every severity and fraction`() {
        val fractions = listOf(null, 0.0, 0.15, 0.6, 1.0)
        Severity.entries.forEach { severity ->
            fractions.forEach { fraction ->
                assertEquals(
                    "LEFT ink for $severity at fraction $fraction",
                    when (severity) {
                        Severity.STALE -> palette.StaleMark
                        Severity.UNKNOWN, Severity.ERROR, Severity.EXHAUSTED -> null
                        else -> palette.Data
                    },
                    palette.barInkFor(severity, fraction, PercentDisplay.LEFT),
                )
            }
        }
    }

    @Test
    fun `LEFT mode keeps a blocked window with a positive remainder an empty track`() {
        // The conflict case: exhausted although 15% remains. Today it draws no fill, and still must.
        assertNull(palette.barInkFor(Severity.EXHAUSTED, 0.15, PercentDisplay.LEFT))
    }

    @Test
    fun `USED mode fills an exhausted window in the data hue`() {
        assertEquals(palette.Data, palette.barInkFor(Severity.EXHAUSTED, 1.0, PercentDisplay.USED))
        // Also when the displayed fraction is a partial one (exhausted with a positive remainder
        // is forced to 1.0 upstream, but the ink rule itself only needs a positive fraction).
        assertEquals(palette.Data, palette.barInkFor(Severity.EXHAUSTED, 0.85, PercentDisplay.USED))
    }

    @Test
    fun `USED mode draws nothing when there is no figure to fill`() {
        assertNull(palette.barInkFor(Severity.EXHAUSTED, null, PercentDisplay.USED))
        assertNull(palette.barInkFor(Severity.EXHAUSTED, 0.0, PercentDisplay.USED))
        assertNull(palette.barInkFor(Severity.UNKNOWN, null, PercentDisplay.USED))
        assertNull(palette.barInkFor(Severity.ERROR, null, PercentDisplay.USED))
    }

    @Test
    fun `USED mode keeps a stale reading grey rather than promoting it to the data hue`() {
        assertEquals(palette.StaleMark, palette.barInkFor(Severity.STALE, 1.0, PercentDisplay.USED))
    }

    @Test
    fun `USED mode leaves ordinary severities on the data hue`() {
        listOf(Severity.HEALTHY, Severity.MEDIUM, Severity.LOW, Severity.LIMITED).forEach {
            assertEquals(palette.Data, palette.barInkFor(it, 0.4, PercentDisplay.USED))
        }
    }
}
