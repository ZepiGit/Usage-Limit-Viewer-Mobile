package com.usagelimits.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The presentation parity vectors (PLAN M3b.5), shared row for row with `WindowPresentationTests.swift`
 * in the iOS UsageLimitsKit. Both tables are written out literally: the independent proof that the
 * rule is the one the surfaces used before is the frozen-copy equivalence tests, not this file.
 */
class WindowPresentationTest {

    private data class Row(
        val n: Int,
        val remaining: Double?,
        val severity: Severity,
        val validity: ReadingValidity,
        val mode: PercentDisplay,
        val token: String,
        val fill: Double?,
        val fillState: FillState,
        val exhausted: Boolean,
    )

    private val rows = listOf(
        Row(1, 73.0, Severity.HEALTHY, ReadingValidity.CURRENT, PercentDisplay.LEFT, "73", 0.73, FillState.DATA, false),
        Row(2, 73.0, Severity.HEALTHY, ReadingValidity.CURRENT, PercentDisplay.USED, "27", 0.27, FillState.DATA, false),
        Row(3, 15.0, Severity.EXHAUSTED, ReadingValidity.CURRENT, PercentDisplay.LEFT, "15", 0.15, FillState.NO_FILL, true),
        Row(4, 15.0, Severity.EXHAUSTED, ReadingValidity.CURRENT, PercentDisplay.USED, "100", 1.0, FillState.DATA, true),
        Row(5, null, Severity.EXHAUSTED, ReadingValidity.CURRENT, PercentDisplay.LEFT, "—", null, FillState.NO_FILL, true),
        Row(6, null, Severity.EXHAUSTED, ReadingValidity.CURRENT, PercentDisplay.USED, "100", 1.0, FillState.DATA, true),
        Row(7, 0.0, Severity.EXHAUSTED, ReadingValidity.CURRENT, PercentDisplay.LEFT, "0", 0.0, FillState.NO_FILL, true),
        Row(8, 0.0, Severity.EXHAUSTED, ReadingValidity.CURRENT, PercentDisplay.USED, "100", 1.0, FillState.DATA, true),
        Row(9, null, Severity.UNKNOWN, ReadingValidity.CURRENT, PercentDisplay.LEFT, "—", null, FillState.NO_FILL, false),
        Row(10, null, Severity.UNKNOWN, ReadingValidity.CURRENT, PercentDisplay.USED, "—", null, FillState.NO_FILL, false),
        Row(11, 150.0, Severity.UNKNOWN, ReadingValidity.CURRENT, PercentDisplay.LEFT, "—", null, FillState.NO_FILL, false),
        Row(12, 150.0, Severity.HEALTHY, ReadingValidity.CURRENT, PercentDisplay.LEFT, "—", null, FillState.DATA, false),
        Row(13, -5.0, Severity.HEALTHY, ReadingValidity.CURRENT, PercentDisplay.USED, "—", null, FillState.DATA, false),
        Row(14, Double.NaN, Severity.HEALTHY, ReadingValidity.CURRENT, PercentDisplay.USED, "—", null, FillState.DATA, false),
        Row(15, 73.0, Severity.HEALTHY, ReadingValidity.STALE, PercentDisplay.LEFT, "73", 0.73, FillState.STALE, false),
        Row(16, 15.0, Severity.EXHAUSTED, ReadingValidity.STALE, PercentDisplay.USED, "100", 1.0, FillState.STALE, true),
        Row(17, 40.0, Severity.MEDIUM, ReadingValidity.RESET_DUE, PercentDisplay.USED, "60", 0.60, FillState.STALE, false),
        Row(18, 73.0, Severity.HEALTHY, ReadingValidity.ERROR, PercentDisplay.LEFT, "73", 0.73, FillState.NO_FILL, false),
        Row(19, 73.0, Severity.HEALTHY, ReadingValidity.ERROR, PercentDisplay.USED, "27", 0.27, FillState.NO_FILL, false),
        Row(20, 15.0, Severity.EXHAUSTED, ReadingValidity.ERROR, PercentDisplay.USED, "100", 1.0, FillState.NO_FILL, true),
        Row(21, 0.4, Severity.LOW, ReadingValidity.CURRENT, PercentDisplay.USED, ">99", 0.996, FillState.DATA, false),
        Row(22, 99.6, Severity.HEALTHY, ReadingValidity.CURRENT, PercentDisplay.USED, "<1", 0.004, FillState.DATA, false),
        Row(23, 100.0, Severity.HEALTHY, ReadingValidity.CURRENT, PercentDisplay.USED, "0", 0.0, FillState.DATA, false),
        Row(24, 5.0, Severity.LOW, ReadingValidity.CURRENT, PercentDisplay.LEFT, "5", 0.05, FillState.DATA, false),
    )

    @Test
    fun `the presentation table holds row for row`() {
        rows.forEach { r ->
            val p = windowPresentation(r.remaining, r.severity, r.validity, r.mode)
            val at = "row ${r.n} (${r.remaining}, ${r.severity}, ${r.validity}, ${r.mode})"
            assertEquals("$at token", r.token, p.token)
            assertEquals("$at side", r.mode, p.side)
            assertEquals("$at fillState", r.fillState, p.fillState)
            assertEquals("$at exhausted", r.exhausted, p.exhausted)
            if (r.fill == null) assertNull("$at fill", p.fill)
            else assertEquals("$at fill", r.fill, p.fill ?: Double.NaN, 1e-9)
        }
    }

    @Test
    fun `the figure exists exactly when the token is not the dash`() {
        rows.forEach { r ->
            val p = windowPresentation(r.remaining, r.severity, r.validity, r.mode)
            assertEquals("row ${r.n}", r.token != "—", p.hasFigure)
        }
    }

    private data class State(
        val n: Int,
        val validity: ReadingValidity,
        val severity: Severity,
        val fill: Double?,
        val mode: PercentDisplay,
        val expected: FillState,
    )

    private val states = listOf(
        State(1, ReadingValidity.STALE, Severity.HEALTHY, 0.73, PercentDisplay.LEFT, FillState.STALE),
        State(2, ReadingValidity.STALE, Severity.EXHAUSTED, 1.0, PercentDisplay.USED, FillState.STALE),
        State(3, ReadingValidity.STALE, Severity.UNKNOWN, null, PercentDisplay.USED, FillState.STALE),
        State(4, ReadingValidity.RESET_DUE, Severity.MEDIUM, 0.6, PercentDisplay.USED, FillState.STALE),
        State(5, ReadingValidity.RESET_DUE, Severity.EXHAUSTED, 0.15, PercentDisplay.LEFT, FillState.STALE),
        State(6, ReadingValidity.ERROR, Severity.HEALTHY, 0.73, PercentDisplay.LEFT, FillState.NO_FILL),
        State(7, ReadingValidity.ERROR, Severity.HEALTHY, 0.27, PercentDisplay.USED, FillState.NO_FILL),
        State(8, ReadingValidity.ERROR, Severity.EXHAUSTED, 1.0, PercentDisplay.USED, FillState.NO_FILL),
        State(9, ReadingValidity.CURRENT, Severity.STALE, 0.5, PercentDisplay.LEFT, FillState.STALE),
        State(10, ReadingValidity.CURRENT, Severity.ERROR, 0.5, PercentDisplay.USED, FillState.NO_FILL),
        State(11, ReadingValidity.CURRENT, Severity.UNKNOWN, 0.5, PercentDisplay.USED, FillState.NO_FILL),
        State(12, ReadingValidity.CURRENT, Severity.UNKNOWN, null, PercentDisplay.LEFT, FillState.NO_FILL),
        State(13, ReadingValidity.CURRENT, Severity.EXHAUSTED, 1.0, PercentDisplay.USED, FillState.DATA),
        State(14, ReadingValidity.CURRENT, Severity.EXHAUSTED, 0.15, PercentDisplay.USED, FillState.DATA),
        State(15, ReadingValidity.CURRENT, Severity.EXHAUSTED, 0.0, PercentDisplay.USED, FillState.NO_FILL),
        State(16, ReadingValidity.CURRENT, Severity.EXHAUSTED, null, PercentDisplay.USED, FillState.NO_FILL),
        State(17, ReadingValidity.CURRENT, Severity.EXHAUSTED, 0.15, PercentDisplay.LEFT, FillState.NO_FILL),
        State(18, ReadingValidity.CURRENT, Severity.HEALTHY, null, PercentDisplay.LEFT, FillState.DATA),
        State(19, ReadingValidity.CURRENT, Severity.MEDIUM, 0.4, PercentDisplay.USED, FillState.DATA),
        State(20, ReadingValidity.CURRENT, Severity.LOW, 0.05, PercentDisplay.LEFT, FillState.DATA),
        State(21, ReadingValidity.CURRENT, Severity.LIMITED, 0.4, PercentDisplay.USED, FillState.DATA),
        State(22, ReadingValidity.CURRENT, Severity.HEALTHY, 0.0, PercentDisplay.USED, FillState.DATA),
    )

    @Test
    fun `no window presents the dash with no fill in both modes`() {
        listOf(PercentDisplay.LEFT, PercentDisplay.USED).forEach { mode ->
            val p = WindowPresentation.absent(mode)
            assertEquals("—", p.token)
            assertEquals(mode, p.side)
            assertNull(p.fill)
            assertEquals(FillState.NO_FILL, p.fillState)
            assertFalse(p.exhausted)
            assertFalse(p.hasFigure)
        }
    }

    @Test
    fun `absent is exactly what the primitives give for a missing window`() {
        listOf(PercentDisplay.LEFT, PercentDisplay.USED).forEach { mode ->
            val p = WindowPresentation.absent(mode)
            assertEquals(percentToken(null, false, mode), p.token)
            assertEquals(fillFraction(null, false, mode), p.fill)
        }
    }

    @Test
    fun `a null window presents as absent whatever the validity`() {
        val none: UsageWindow? = null
        ReadingValidity.entries.forEach { validity ->
            listOf(PercentDisplay.LEFT, PercentDisplay.USED).forEach { mode ->
                assertEquals("$validity $mode", WindowPresentation.absent(mode), none.presentation(validity, mode))
            }
        }
    }

    @Test
    fun `a window presents from its remainder and its raw severity`() {
        val healthy = window(usedPercent = 27.0)
        val exhaustedWithRemainder = window(usedPercent = 85.0, exhausted = true)
        val noFigure = window(usedPercent = null, exhausted = true)

        assertEquals(
            windowPresentation(73.0, Severity.HEALTHY, ReadingValidity.CURRENT, PercentDisplay.USED),
            healthy.presentation(ReadingValidity.CURRENT, PercentDisplay.USED),
        )
        assertEquals(
            windowPresentation(15.0, Severity.EXHAUSTED, ReadingValidity.STALE, PercentDisplay.USED),
            exhaustedWithRemainder.presentation(ReadingValidity.STALE, PercentDisplay.USED),
        )
        assertEquals(
            windowPresentation(null, Severity.EXHAUSTED, ReadingValidity.CURRENT, PercentDisplay.USED),
            noFigure.presentation(ReadingValidity.CURRENT, PercentDisplay.USED),
        )
        val p = exhaustedWithRemainder.presentation(ReadingValidity.STALE, PercentDisplay.LEFT)
        assertEquals("15", p.token)
        assertTrue(p.exhausted)
        assertEquals(FillState.STALE, p.fillState)
    }

    // f9075fb UsageWindowRow: `stale || (fetchedAt != null && window.resetElapsedAt(fetchedAt, nowMs))`
    // decided whether the row took the stale treatment. Frozen here so the helper cannot drift from it.
    private fun staleBeforeM3b(window: UsageWindow, stale: Boolean, fetchedAt: Long?, nowMs: Long): Boolean =
        stale || (fetchedAt != null && window.resetElapsedAt(fetchedAt, nowMs))

    @Test
    fun `reading validity agrees with the inline expression it replaces over every input`() {
        val resetAt = 10_000L
        val windows = listOf(window(usedPercent = 27.0, resetAt = resetAt), window(usedPercent = 27.0, resetAt = null))
        val fetchedAts = listOf(null, 5_000L, 12_000L)
        val nows = listOf(8_000L, 15_000L)
        var resetDueSeen = false
        windows.forEach { w ->
            listOf(false, true).forEach { stale ->
                fetchedAts.forEach { fetchedAt ->
                    nows.forEach { now ->
                        val actual = w.readingValidity(stale, fetchedAt, now)
                        val at = "resetAt=${w.resetAt} stale=$stale fetchedAt=$fetchedAt now=$now"
                        if (staleBeforeM3b(w, stale, fetchedAt, now)) {
                            assertEquals(at, if (stale) ReadingValidity.STALE else ReadingValidity.RESET_DUE, actual)
                            if (!stale) resetDueSeen = true
                        } else {
                            assertEquals(at, ReadingValidity.CURRENT, actual)
                        }
                    }
                }
            }
        }
        assertTrue("the sweep never reached RESET_DUE", resetDueSeen)
    }

    @Test
    fun `reading validity names the three outcomes`() {
        val w = window(usedPercent = 27.0, resetAt = 10_000L)
        assertEquals(ReadingValidity.STALE, w.readingValidity(stale = true, fetchedAt = null, nowMs = 8_000L))
        // Stale outranks a passed reset: the failed account's grey is not a reset prompt.
        assertEquals(ReadingValidity.STALE, w.readingValidity(stale = true, fetchedAt = 5_000L, nowMs = 15_000L))
        assertEquals(ReadingValidity.RESET_DUE, w.readingValidity(stale = false, fetchedAt = 5_000L, nowMs = 15_000L))
        // Fetched after the reset, the cache already describes the new window.
        assertEquals(ReadingValidity.CURRENT, w.readingValidity(stale = false, fetchedAt = 12_000L, nowMs = 15_000L))
        assertEquals(ReadingValidity.CURRENT, w.readingValidity(stale = false, fetchedAt = 5_000L, nowMs = 8_000L))
        // No fetch time, as on the lead strip, never reads as reset due.
        assertEquals(ReadingValidity.CURRENT, w.readingValidity(stale = false, fetchedAt = null, nowMs = 15_000L))
    }

    private fun window(usedPercent: Double?, exhausted: Boolean = false, resetAt: Long? = null) = UsageWindow(
        id = "w",
        label = "5h limit",
        category = WindowCategory.FIVE_HOUR,
        usedPercent = usedPercent,
        periodSeconds = null,
        resetAt = resetAt,
        exhausted = exhausted,
    )

    @Test
    fun `the fill state table holds row for row`() {
        states.forEach { s ->
            assertEquals(
                "S${s.n} (${s.validity}, ${s.severity}, ${s.fill}, ${s.mode})",
                s.expected,
                fillState(s.validity, s.severity, s.fill, s.mode),
            )
        }
    }
}
