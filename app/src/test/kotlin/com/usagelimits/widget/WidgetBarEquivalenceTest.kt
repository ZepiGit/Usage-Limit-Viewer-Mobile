package com.usagelimits.widget

import androidx.compose.ui.graphics.Color
import com.usagelimits.core.model.FillState
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ReadingValidity
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.model.WindowPresentation
import com.usagelimits.core.model.fillFraction
import com.usagelimits.core.model.usedFillOverridesMissingInk
import com.usagelimits.core.model.windowPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget bar after M3b is the bar before it.
 *
 * `WidgetStyle.bar` and the Glance fraction lose their production callers once the widgets read the
 * presentation module, so the existing style tests alone would no longer prove the path a widget
 * takes. The private `inkBeforeM3b` and `paintedBeforeM3b` are verbatim copies of f9075fb; the sweep
 * compares the module against them, so a wrong mapping fails here and not on a home screen.
 */
class WidgetBarEquivalenceTest {

    private fun row(
        remaining: Double?,
        severity: Severity,
        resetElapsed: Boolean = false,
    ) = WidgetRow("5h limit", WindowCategory.FIVE_HOUR, remaining, null, severity, resetElapsed = resetElapsed)

    /** f9075fb `WidgetStyle.bar`, verbatim except that `stale` and `data` are read off [style]. */
    private fun inkBeforeM3b(style: WidgetStyle, row: WidgetRow, validity: Severity?, mode: PercentDisplay): Color? {
        when ((validity ?: row.validity) ?: row.severity) {
            Severity.STALE -> return style.stale
            Severity.ERROR, Severity.UNKNOWN -> return null
            Severity.EXHAUSTED -> {}
            else -> return style.data
        }
        val fraction = fillFraction(row.remainingPercent, row.isExhausted, mode)
        return if (usedFillOverridesMissingInk(mode, fraction)) style.data else null
    }

    /** f9075fb Glance `UsageBar`: the fraction handed to the progress indicator. */
    private fun paintedBeforeM3b(style: WidgetStyle, row: WidgetRow, validity: Severity?, mode: PercentDisplay): Float {
        val bar = inkBeforeM3b(style, row, validity, mode)
        return if (bar == null) 0f
        else (fillFraction(row.remainingPercent, row.isExhausted, mode) ?: 0.0).coerceIn(0.0, 1.0).toFloat()
    }

    private val styles = listOf(WidgetStyle(), WidgetStyle(WidgetBackground(0xFFF3F0E8.toInt(), 100)))
    private val modes = listOf(PercentDisplay.LEFT, PercentDisplay.USED)
    private val remainings = listOf(null, 0.0, 15.0, 73.0, 100.0)
    private val accountValidities = listOf(null, Severity.STALE, Severity.ERROR)

    private fun sweep(check: (WidgetStyle, WidgetRow, Severity?, PercentDisplay, String) -> Unit): Int {
        var cases = 0
        styles.forEach { style ->
            Severity.entries.forEach { severity ->
                listOf(false, true).forEach { elapsed ->
                    accountValidities.forEach { validity ->
                        modes.forEach { mode ->
                            remainings.forEach { remaining ->
                                val row = row(remaining, severity, elapsed)
                                check(style, row, validity, mode, "$severity elapsed=$elapsed validity=$validity $mode remaining=$remaining")
                                cases++
                            }
                        }
                    }
                }
            }
        }
        return cases
    }

    @Test
    fun `the module paints the ink the bar painted before it, for every row and validity`() {
        var paints = 0
        val cases = sweep { style, row, validity, mode, at ->
            val expected = inkBeforeM3b(style, row, validity, mode)
            assertEquals(at, expected, style.fill(row.presentation(validity, mode).fillState))
            if (expected != null) paints++
        }
        assertEquals(2 * 8 * 2 * 3 * 2 * 5, cases)
        assertTrue("the sweep never reached a painted bar", paints > 0 && paints < cases)
    }

    @Test
    fun `the module fills the length the Glance bar filled before it, for every row and validity`() {
        val lengths = mutableSetOf<Float>()
        sweep { style, row, validity, mode, at ->
            val p = row.presentation(validity, mode)
            val painted = if (p.fillState == FillState.NO_FILL) 0f else (p.fill ?: 0.0).toFloat()
            val expected = paintedBeforeM3b(style, row, validity, mode)
            assertEquals(at, expected, painted, 0f)
            lengths += painted
        }
        assertTrue("the sweep never reached a partial or full bar", lengths.any { it > 0f && it < 1f } && 1f in lengths)
    }

    @Test
    fun `the bar the style returns is still the ink it returned before`() {
        sweep { style, row, validity, mode, at ->
            assertEquals(at, inkBeforeM3b(style, row, validity, mode), style.bar(row, validity, mode))
        }
    }

    @Test
    fun `a row's reading validity follows the account first and its own reset second`() {
        // Production passes only STALE, ERROR or null; the rest pins what any other severity means.
        listOf(false, true).forEach { elapsed ->
            val at = "resetElapsed=$elapsed"
            assertEquals(at, ReadingValidity.STALE, row(40.0, Severity.HEALTHY, elapsed).readingValidity(Severity.STALE))
            assertEquals(at, ReadingValidity.ERROR, row(40.0, Severity.HEALTHY, elapsed).readingValidity(Severity.ERROR))
            assertEquals(at, ReadingValidity.ERROR, row(40.0, Severity.HEALTHY, elapsed).readingValidity(Severity.UNKNOWN))
            val expectedWithoutAccount = if (elapsed) ReadingValidity.RESET_DUE else ReadingValidity.CURRENT
            assertEquals(at, expectedWithoutAccount, row(40.0, Severity.HEALTHY, elapsed).readingValidity(null))
            Severity.entries.filter { it != Severity.STALE && it != Severity.ERROR && it != Severity.UNKNOWN }
                .forEach { accountValidity ->
                    assertEquals(
                        "$at accountValidity=$accountValidity",
                        expectedWithoutAccount,
                        row(40.0, Severity.HEALTHY, elapsed).readingValidity(accountValidity),
                    )
                }
        }
    }

    @Test
    fun `a row's own severity never decides its validity`() {
        Severity.entries.forEach { severity ->
            assertEquals(severity.name, ReadingValidity.CURRENT, row(40.0, severity).readingValidity(null))
            assertEquals(severity.name, ReadingValidity.RESET_DUE, row(40.0, severity, resetElapsed = true).readingValidity(null))
        }
    }

    @Test
    fun `a row presents from its raw severity under the validity its account gives it`() {
        val exhausted = row(15.0, Severity.EXHAUSTED, resetElapsed = true)
        assertEquals(
            windowPresentation(15.0, Severity.EXHAUSTED, ReadingValidity.RESET_DUE, PercentDisplay.USED),
            exhausted.presentation(null, PercentDisplay.USED),
        )
        assertEquals(
            windowPresentation(15.0, Severity.EXHAUSTED, ReadingValidity.ERROR, PercentDisplay.LEFT),
            exhausted.presentation(Severity.ERROR, PercentDisplay.LEFT),
        )
    }

    @Test
    fun `a style paints its data hue, its stale grey, or nothing`() {
        listOf(WidgetStyle(), WidgetStyle(WidgetBackground(0xFFF3F0E8.toInt(), 100))).forEach { style ->
            assertEquals(style.data, style.fill(FillState.DATA))
            assertEquals(style.stale, style.fill(FillState.STALE))
            assertNull(style.fill(FillState.NO_FILL))
        }
    }

    @Test
    fun `no row presents as absent`() {
        val none: WidgetRow? = null
        listOf(PercentDisplay.LEFT, PercentDisplay.USED).forEach { mode ->
            listOf(null, Severity.STALE, Severity.ERROR).forEach { accountValidity ->
                assertEquals("$mode $accountValidity", WindowPresentation.absent(mode), none.presentation(accountValidity, mode))
            }
        }
    }
}
