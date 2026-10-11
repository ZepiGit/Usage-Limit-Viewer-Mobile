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
}
