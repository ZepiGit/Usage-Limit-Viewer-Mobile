package com.usagelimits.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import kotlin.math.roundToInt

/**
 * A pressed segment lights its rounded 32dp pill, not the 48dp touch target around it (#26).
 *
 * The frames are the decor view drawn in software, as RedesignCaptureTest does it:
 * `captureToImage()` never sees a frame under Robolectric.
 *
 * The pixel test runs on API 30: from API 31 the platform ripple is the patterned (sparkle) one,
 * which Robolectric does not draw into a software canvas, so a press on 34 leaves the frame unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h300dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentPressTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setRow() = compose.setContent {
        UsageLimitsTheme {
            Box(Modifier.background(UsageColors.Ground).padding(24.dp)) {
                SegmentedRow {
                    Segment("All", selected = true) {}
                    Segment("Needs attention", selected = false) {}
                }
            }
        }
    }

    private fun frame(): Bitmap {
        val root = compose.activity.window.decorView
        return Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
    }

    private val radioButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Config(sdk = [30])
    @Test
    fun `the press highlight stays inside the rounded pill`() {
        setRow()
        compose.waitForIdle()
        val segment = compose.onNode(hasText("Needs attention") and radioButton, useUnmergedTree = false)
        val bounds = segment.fetchSemanticsNode().boundsInWindow
        val px = compose.density.density
        val left = bounds.left.roundToInt()
        val right = bounds.right.roundToInt()
        val top = bounds.top.roundToInt()
        val bottom = bounds.bottom.roundToInt()
        // The pill is the touch target less 8dp top and bottom; it fills the width for a label this long.
        val pillTop = top + (8 * px).roundToInt()
        val pillBottom = bottom - (8 * px).roundToInt()
        val before = frame()

        segment.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        compose.waitForIdle()
        val pressed = frame()
        segment.performTouchInput { up() }

        fun changed(x: Int, y: Int) = before.getPixel(x, y) != pressed.getPixel(x, y)
        val margin = (2 * px).roundToInt()

        // Inside the pill, clear of the label's 14dp side padding: the highlight is there.
        val insideX = left + (6 * px).roundToInt()
        val insideY = (pillTop + pillBottom) / 2
        assertTrue("the pressed pill shows a highlight at ($insideX, $insideY)", changed(insideX, insideY))

        // Above and below the pill, the touch padding and the row's frame line stay as they were.
        val outside = mutableListOf<Pair<Int, Int>>()
        for (x in (left + margin) until (right - margin) step 2) {
            for (y in top until pillTop) outside += x to y
            for (y in pillBottom until bottom) outside += x to y
        }
        // The pill's four corners, outside the 6dp rounding.
        val inset = (px / 2).roundToInt().coerceAtLeast(0)
        outside += listOf(
            left + inset to pillTop + inset,
            right - 1 - inset to pillTop + inset,
            left + inset to pillBottom - 1 - inset,
            right - 1 - inset to pillBottom - 1 - inset,
        )
        val spilled = outside.filter { (x, y) -> changed(x, y) }
        assertEquals("pixels the highlight changed outside the pill, first: ${spilled.take(4)}", 0, spilled.size)
    }

    @Test
    fun `a segment keeps its 48dp touch target and reads as a radio button with its state`() {
        setRow()
        compose.onNode(hasText("All") and radioButton).assertHeightIsAtLeast(48.dp).assertIsSelected()
        compose.onNode(hasText("Needs attention") and radioButton).assertHeightIsAtLeast(48.dp).assertIsNotSelected()
    }
}
