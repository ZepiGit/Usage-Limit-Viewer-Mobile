package com.usagelimits.ui.theme

import androidx.compose.ui.graphics.Color
import com.usagelimits.core.model.FillState
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.model.fillFraction
import com.usagelimits.core.model.isExhausted
import com.usagelimits.core.model.presentation
import com.usagelimits.core.model.readingValidity
import com.usagelimits.core.model.usedFillOverridesMissingInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app bar inks and draws what the bar's own rule gives.
 *
 * The app rows read the presentation module, not `barInkFor`, so the ink and the drawn length they
 * take are compared with independent copies of the bar's rule. A mistake in the module's rule then
 * cannot also hide in its oracle.
 */
class ComposeBarEquivalenceTest {

    private val modes = listOf(PercentDisplay.LEFT, PercentDisplay.USED)

    /** An independent copy of the palette's fill per severity. */
    private fun referenceBarFill(severity: Severity): Color? = when (severity) {
        Severity.STALE -> UsageColors.StaleMark
        Severity.UNKNOWN, Severity.ERROR, Severity.EXHAUSTED -> null
        else -> UsageColors.Data
    }

    /** An independent copy of the palette's ink for a severity and fraction. */
    private fun referenceInk(severity: Severity, fraction: Double?, mode: PercentDisplay): Color? {
        referenceBarFill(severity)?.let { return it }
        if (usedFillOverridesMissingInk(mode, fraction)) return UsageColors.Data
        return null
    }

    /** An independent copy of the length the Compose `UsageBar` draws, with its animation settled. */
    private fun referenceDrawnLength(window: UsageWindow, displaySeverity: Severity, mode: PercentDisplay): Float {
        val fractionValue = fillFraction(window.remainingPercent, window.isExhausted, mode)
        val target = (fractionValue ?: 0.0).coerceIn(0.0, 1.0).toFloat()
        val fill = referenceInk(displaySeverity, fractionValue, mode)
        return if (target > 0f && fill != null) target else 0f
    }

    private val resetAt = 10_000L

    private fun window(usedPercent: Double?, exhausted: Boolean = false) = UsageWindow(
        id = "w",
        label = "5h limit",
        category = WindowCategory.FIVE_HOUR,
        usedPercent = usedPercent,
        periodSeconds = null,
        resetAt = resetAt,
        exhausted = exhausted,
    )

    private val windows = mapOf(
        "healthy 73" to window(usedPercent = 27.0),
        "low 5" to window(usedPercent = 95.0),
        "exhausted 15" to window(usedPercent = 85.0, exhausted = true),
        "exhausted, no figure" to window(usedPercent = null, exhausted = true),
        "exhausted 0" to window(usedPercent = 100.0),
        "unknown" to window(usedPercent = null),
    )

    @Test
    fun `the app palette paints its data hue, its stale mark, or nothing`() {
        assertEquals(UsageColors.Data, SeverityPalette.fill(FillState.DATA))
        assertEquals(UsageColors.StaleMark, SeverityPalette.fill(FillState.STALE))
        assertNull(SeverityPalette.fill(FillState.NO_FILL))
    }

    @Test
    fun `an app row inks and draws what the reference rule gives, for every window and validity`() {
        val nowMs = 15_000L
        val inks = mutableSetOf<Color?>()
        var cases = 0
        windows.forEach { (name, window) ->
            listOf(false, true).forEach { stale ->
                listOf(null, 5_000L, 12_000L).forEach { fetchedAt ->
                    modes.forEach { mode ->
                        val at = "$name stale=$stale fetchedAt=$fetchedAt $mode"
                        val referenceDisplay =
                            if (stale || (fetchedAt != null && window.resetElapsedAt(fetchedAt, nowMs))) Severity.STALE
                            else window.severity
                        val referenceFraction = fillFraction(window.remainingPercent, window.isExhausted, mode)
                        val expectedInk = referenceInk(referenceDisplay, referenceFraction, mode)

                        val p = window.presentation(window.readingValidity(stale, fetchedAt, nowMs), mode)
                        val ink = SeverityPalette.fill(p.fillState)
                        val target = (p.fill ?: 0.0).coerceIn(0.0, 1.0).toFloat()
                        val drawn = if (target > 0f && ink != null) target else 0f

                        assertEquals("$at ink", expectedInk, ink)
                        assertEquals("$at length", referenceDrawnLength(window, referenceDisplay, mode), drawn, 0f)
                        inks += ink
                        cases++
                    }
                }
            }
        }
        assertEquals(6 * 2 * 3 * 2, cases)
        assertEquals("the sweep must reach every ink", setOf<Color?>(UsageColors.Data, UsageColors.StaleMark, null), inks)
    }

    /**
     * The one case where the module's ink differs from the reference: ERROR or UNKNOWN with a
     * positive fraction in USED is no fill, where the reference gives the data hue. No app screen can
     * ask for it: their display severity is STALE or the window's own, and a raw UNKNOWN has no
     * fraction unless exhausted.
     */
    private fun isDeliberateChange(severity: Severity, fraction: Double?, mode: PercentDisplay) =
        (severity == Severity.ERROR || severity == Severity.UNKNOWN) &&
            mode == PercentDisplay.USED && fraction != null && fraction > 0.0

    @Test
    fun `a failed or unknown severity with a positive fraction in USED is the one ink that differs from the reference`() {
        listOf(Severity.ERROR, Severity.UNKNOWN).forEach { severity ->
            listOf(0.15, 0.6, 1.0).forEach { fraction ->
                val at = "$severity $fraction"
                assertTrue(at, isDeliberateChange(severity, fraction, PercentDisplay.USED))
                assertEquals("$at reference", UsageColors.Data, referenceInk(severity, fraction, PercentDisplay.USED))
                assertNull("$at module", SeverityPalette.barInkFor(severity, fraction, PercentDisplay.USED))
            }
        }
    }

    @Test
    fun `the palette wrapper returns the reference ink wherever the two agree`() {
        val fractions = listOf(null, 0.0, 0.15, 0.6, 1.0)
        var compared = 0
        Severity.entries.forEach { severity ->
            fractions.forEach { fraction ->
                modes.forEach { mode ->
                    if (isDeliberateChange(severity, fraction, mode)) return@forEach
                    assertEquals(
                        "$severity $fraction $mode",
                        referenceInk(severity, fraction, mode),
                        SeverityPalette.barInkFor(severity, fraction, mode),
                    )
                    compared++
                }
            }
        }
        assertTrue(compared > 0)
    }
}
