package com.usagelimits.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The percent-display parity vectors, shared verbatim with `PercentDisplayTests.swift` in the iOS
 * UsageLimitsKit: one table, two platforms, pinned token by token. When a row changes here, change
 * it there in the same commit.
 */
class DisplayPercentTest {

    private data class Vector(
        val remaining: Double?,
        val exhausted: Boolean,
        val left: String,
        val used: String,
    )

    private val vectors = listOf(
        Vector(0.0, false, "0", "100"),
        Vector(0.4, false, "<1", ">99"),
        Vector(0.5, false, "<1", ">99"),
        Vector(1.0, false, "1", "99"),
        Vector(1.5, false, "2", "99"),
        Vector(25.0, false, "25", "75"),
        Vector(49.5, false, "50", "51"),
        Vector(50.0, false, "50", "50"),
        Vector(73.0, false, "73", "27"),
        Vector(99.0, false, "99", "1"),
        Vector(99.4, false, "99", "<1"),
        Vector(99.5, false, ">99", "<1"),
        Vector(99.6, false, ">99", "<1"),
        Vector(100.0, false, "100", "0"),
        Vector(0.001, false, "<1", ">99"),
        Vector(-0.0, false, "0", "100"),
        Vector(null, false, "—", "—"),
        Vector(null, true, "—", "100"),
        Vector(Double.NaN, false, "—", "—"),
        Vector(Double.NaN, true, "—", "100"),
        Vector(-1.0, false, "—", "—"),
        Vector(101.0, false, "—", "—"),
        Vector(101.0, true, "—", "100"),
        Vector(Double.POSITIVE_INFINITY, false, "—", "—"),
        Vector(15.0, true, "15", "100"),
        Vector(0.0, true, "0", "100"),
    )

    @Test
    fun `the parity table holds for both modes`() {
        vectors.forEach { v ->
            assertEquals(
                "LEFT token for remaining=${v.remaining}, exhausted=${v.exhausted}",
                v.left,
                percentToken(v.remaining, v.exhausted, PercentDisplay.LEFT),
            )
            assertEquals(
                "USED token for remaining=${v.remaining}, exhausted=${v.exhausted}",
                v.used,
                percentToken(v.remaining, v.exhausted, PercentDisplay.USED),
            )
        }
    }

    @Test
    fun `a widget override wins over the app mode and null follows it`() {
        val expected = listOf(
            Triple(PercentDisplay.LEFT, null, PercentDisplay.LEFT),
            Triple(PercentDisplay.LEFT, PercentDisplay.LEFT, PercentDisplay.LEFT),
            Triple(PercentDisplay.LEFT, PercentDisplay.USED, PercentDisplay.USED),
            Triple(PercentDisplay.USED, null, PercentDisplay.USED),
            Triple(PercentDisplay.USED, PercentDisplay.LEFT, PercentDisplay.LEFT),
            Triple(PercentDisplay.USED, PercentDisplay.USED, PercentDisplay.USED),
        )
        expected.forEach { (app, override, resolved) ->
            assertEquals("app=$app override=$override", resolved, app.resolved(override))
        }
    }

    @Test
    fun `the percent label wraps the LEFT token unchanged`() {
        assertEquals("73%", percentLabel(73.0))
        assertEquals("<1%", percentLabel(0.4))
        assertEquals(">99%", percentLabel(99.5))
        assertEquals("—", percentLabel(null))
    }

    @Test
    fun `displayPercent derives used from remaining and keeps null null`() {
        assertEquals(73.0, displayPercent(73.0, PercentDisplay.LEFT)!!, 1e-9)
        assertEquals(27.0, displayPercent(73.0, PercentDisplay.USED)!!, 1e-9)
        assertNull(displayPercent(null, PercentDisplay.USED))
        assertNull(displayPercent(null, PercentDisplay.LEFT))
    }

    @Test
    fun `fillFraction fills exhausted windows completely only in USED mode`() {
        assertEquals(1.0, fillFraction(15.0, exhausted = true, mode = PercentDisplay.USED)!!, 1e-9)
        assertEquals(0.15, fillFraction(15.0, exhausted = true, mode = PercentDisplay.LEFT)!!, 1e-9)
        assertNull(fillFraction(null, exhausted = false, mode = PercentDisplay.USED))
        assertNull(fillFraction(null, exhausted = true, mode = PercentDisplay.LEFT))
        assertEquals(0.27, fillFraction(73.0, exhausted = false, mode = PercentDisplay.USED)!!, 1e-9)
    }

    @Test
    fun `fillFraction for a window reads the raw severity, never an aged one`() {
        // Exhausted with a positive remaining: the conflict case the provider itself reports.
        val conflict = UsageWindow(
            id = "w",
            label = "Weekly",
            category = WindowCategory.WEEKLY,
            usedPercent = 85.0,
            periodSeconds = null,
            resetAt = null,
            exhausted = true,
        )
        assertEquals(1.0, fillFraction(conflict, PercentDisplay.USED)!!, 1e-9)
        assertEquals(0.15, fillFraction(conflict, PercentDisplay.LEFT)!!, 1e-9)
    }

    @Test
    fun `fillFraction with no window at all fills nothing in either mode`() {
        assertNull(fillFraction(null as UsageWindow?, PercentDisplay.LEFT))
        assertNull(fillFraction(null as UsageWindow?, PercentDisplay.USED))
    }

    @Test
    fun `the fill fallback never fires in LEFT mode`() {
        for (fraction in listOf(null, 0.0, 0.15, 1.0)) {
            assertFalse("LEFT, fraction $fraction", usedFillOverridesMissingInk(PercentDisplay.LEFT, fraction))
        }
    }

    @Test
    fun `the fill fallback fires in USED mode only for a positive fraction`() {
        assertFalse(usedFillOverridesMissingInk(PercentDisplay.USED, null))
        assertFalse(usedFillOverridesMissingInk(PercentDisplay.USED, 0.0))
        assertTrue(usedFillOverridesMissingInk(PercentDisplay.USED, 0.15))
        assertTrue(usedFillOverridesMissingInk(PercentDisplay.USED, 1.0))
    }
}
