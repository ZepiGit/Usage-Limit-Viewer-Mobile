package com.usagelimits.widget

import android.app.Application
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageRingRenderingTest {
    @Test fun `unknown quota never paints the healthy foreground`() {
        val unknown = UsageRing.draw(160, null, Color.GREEN, Color.GRAY)
        val full = UsageRing.draw(160, 100.0, Color.GREEN, Color.GRAY)
        fun greenPixels(bitmap: android.graphics.Bitmap): Int =
            (0 until bitmap.width).sumOf { x ->
                (0 until bitmap.height).count { y -> bitmap.getPixel(x, y) == Color.GREEN }
            }
        assertEquals(0, greenPixels(unknown))
        assertTrue("a known full quota must still have a foreground", greenPixels(full) > 0)
        assertTrue("unknown must differ from an exhausted solid track",
            !unknown.sameAs(UsageRing.draw(160, 0.0, Color.GREEN, Color.GRAY)))
    }

    private fun count(bitmap: android.graphics.Bitmap, color: Int): Int =
        (0 until bitmap.width).sumOf { x -> (0 until bitmap.height).count { y -> bitmap.getPixel(x, y) == color } }

    /**
     * The centre of the stroke at a clock position, where coverage is total and anti-aliasing
     * cannot blur the colour. The stroke of a 160px ring is 17.6px wide, inset 8.8px.
     */
    private fun android.graphics.Bitmap.atClock(position: String): Int = when (position) {
        "12" -> getPixel(80, 9)
        "3" -> getPixel(151, 80)
        "6" -> getPixel(80, 151)
        "9" -> getPixel(9, 80)
        else -> error("no such clock position: $position")
    }

    @Test fun `a ring filled to one hundred is coloured all the way round`() {
        // USED mode hands an exhausted window to draw() as 100 (D4): full means consumed. The arc
        // must close the circle, so both ends of the vertical axis carry the data colour.
        val full = UsageRing.draw(160, 100.0, Color.GREEN, Color.GRAY)
        assertEquals("12 o'clock is the data colour at 100", Color.GREEN, full.atClock("12"))
        assertEquals("3 o'clock is the data colour at 100", Color.GREEN, full.atClock("3"))
        assertEquals("6 o'clock is the data colour at 100", Color.GREEN, full.atClock("6"))
        assertEquals("9 o'clock is the data colour at 100", Color.GREEN, full.atClock("9"))
    }

    @Test fun `a ring filled to zero shows only the track`() {
        // USED at nothing consumed is the empty ring, and must stay distinguishable from both
        // the full one and the dashed unknown.
        val empty = UsageRing.draw(160, 0.0, Color.GREEN, Color.GRAY)
        assertEquals("no pixel of an empty ring carries the data colour", 0, count(empty, Color.GREEN))
        assertEquals("12 o'clock is the plain track at 0", Color.GRAY, empty.atClock("12"))
        assertEquals("6 o'clock is the plain track at 0", Color.GRAY, empty.atClock("6"))
        assertTrue("an empty ring differs from a full one",
            !empty.sameAs(UsageRing.draw(160, 100.0, Color.GREEN, Color.GRAY)))
    }

    @Test fun `a half ring fills clockwise from twelve o'clock`() {
        // The arc starts at the top and runs clockwise, so 3 o'clock is inside a half fill and
        // 9 o'clock is not; the same geometry serves LEFT and USED, only the percent differs.
        val half = UsageRing.draw(160, 50.0, Color.GREEN, Color.GRAY)
        assertEquals("12 o'clock opens the arc", Color.GREEN, half.atClock("12"))
        assertEquals("3 o'clock is inside a clockwise half", Color.GREEN, half.atClock("3"))
        assertEquals("9 o'clock is outside a clockwise half", Color.GRAY, half.atClock("9"))
    }

    @Test fun `an unknown figure never looks like a filled or an empty ring`() {
        val unknown = UsageRing.draw(160, null, Color.GREEN, Color.GRAY)
        assertTrue("unknown differs from empty", !unknown.sameAs(UsageRing.draw(160, 0.0, Color.GREEN, Color.GRAY)))
        assertTrue("unknown differs from full", !unknown.sameAs(UsageRing.draw(160, 100.0, Color.GREEN, Color.GRAY)))
        assertTrue("unknown paints no data colour at 12 o'clock", unknown.atClock("12") != Color.GREEN)
    }

    @Test fun `an exhausted window in USED mode hands the ring one hundred, whatever it still has left`() {
        // The widget feeds draw() fillFraction * 100. An exhausted window that still reports 15%
        // remaining is the full ring in USED (it reads "100%"), and the bare 15% arc in LEFT.
        val usedFill = com.usagelimits.core.model.fillFraction(15.0, true, com.usagelimits.core.model.PercentDisplay.USED)!! * 100.0
        assertEquals("USED exhausted fill, as percent", 100.0, usedFill, 0.0)
        val leftFill = com.usagelimits.core.model.fillFraction(15.0, true, com.usagelimits.core.model.PercentDisplay.LEFT)!! * 100.0
        assertEquals("LEFT keeps the remaining figure", 15.0, leftFill, 0.0001)

        val closed = UsageRing.draw(160, usedFill, Color.GREEN, Color.GRAY)
        assertEquals("the USED exhausted ring is closed at 6 o'clock", Color.GREEN, closed.atClock("6"))
        assertEquals("the USED exhausted ring is closed at 9 o'clock", Color.GREEN, closed.atClock("9"))
        val partial = UsageRing.draw(160, leftFill, Color.GREEN, Color.GRAY)
        assertEquals("the LEFT 15% ring is open at 6 o'clock", Color.GRAY, partial.atClock("6"))
    }
}
