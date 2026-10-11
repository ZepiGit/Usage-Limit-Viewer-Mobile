package com.usagelimits.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic behind the ring, without a framebuffer.
 *
 * The drawing itself needs a real Canvas, but everything that decides what gets drawn is
 * ordinary arithmetic — and it is where the two ways of being wrong live: a full ring for an
 * exhausted account, and an empty one for an account whose provider simply did not say.
 */
class UsageRingTest {

    @Test
    fun `a full account is a whole circle and an empty one is nothing`() {
        assertEquals(360f, UsageRing.sweepDegrees(1f), 0.01f)
        assertEquals(0f, UsageRing.sweepDegrees(0f), 0.01f)
        assertEquals(180f, UsageRing.sweepDegrees(0.5f), 0.01f)
    }

    @Test
    fun `a percentage outside the range cannot draw outside the circle`() {
        // Geometry is bounded; invalid provider percentages must not become a full quota.
        assertEquals(360f, UsageRing.sweepDegrees(1.4f), 0.01f)
        assertEquals(0f, UsageRing.sweepDegrees(-0.2f), 0.01f)
        assertEquals(0f, UsageRing.fractionFor(140.0), 0.001f)
        assertEquals(0f, UsageRing.fractionFor(-5.0), 0.001f)
    }

    @Test
    fun `unknown and invalid percentages claim no available quota`() {
        for (percent in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(0f, UsageRing.fractionFor(percent), 0.001f)
        }
    }

    @Test
    fun `a reported percentage maps straight through`() {
        assertEquals(0.37f, UsageRing.fractionFor(37.0), 0.001f)
    }
}
