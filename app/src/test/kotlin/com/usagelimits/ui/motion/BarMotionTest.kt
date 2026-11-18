package com.usagelimits.ui.motion

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.Severity
import com.usagelimits.ui.components.UsageBar
import com.usagelimits.ui.theme.LocalMotionEnabled
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
import kotlin.math.roundToInt

/**
 * The quota bar's fill moves in the draw phase only (#81).
 *
 * A bar that reads its animated fraction in composition is recomposed and remeasured on every
 * frame of the fill, and a display-mode flip moves every bar on screen at once. The measure
 * counter below sits on the bar's own layout node, so it counts exactly those passes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h200dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BarMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var remaining by mutableStateOf(20.0)
    private var measures = 0

    private fun setBar(motion: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme {
                CompositionLocalProvider(LocalMotionEnabled provides motion) {
                    Box(Modifier.background(UsageColors.Ground).padding(24.dp)) {
                        UsageBar(
                            remaining, Severity.HEALTHY,
                            Modifier.fillMaxWidth().testTag("bar").layout { measurable, constraints ->
                                measures++
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                            },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    /** How many pixels of the bar's middle row carry the fill colour. */
    private fun filled(frame: Bitmap): Int {
        val bounds = compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInWindow
        val y = ((bounds.top + bounds.bottom) / 2).roundToInt()
        return (bounds.left.roundToInt() until bounds.right.roundToInt()).count { frame.at(it, y) == UsageColors.Data.argb }
    }

    @Test
    fun `the fill grows frame by frame without the bar being measured again`() {
        setBar(motion = true)
        FrameRecorder(compose).use { recorder ->
            val start = filled(recorder.frame())
            val measuredBefore = measures
            compose.change { remaining = 80.0 }
            val frames = recorder.next(20)
            val widths = frames.map(::filled)
            (listOf(recorder.frame()) + frames).saveSequence("bar-fill")

            assertTrue("the fill passes through intermediate lengths: $widths", widths.distinct().size >= 6)
            assertEquals("the fill never shrinks on its way up", widths.sorted(), widths)
            // 20 % left grows to 80 % left; the bar's rounded ends hide a few pixels of fill colour.
            assertTrue("it starts from the old length ($start) and ends near four times it", widths.first() >= start && widths.last() in (start * 4 - 12)..(start * 4 + 12))
            assertEquals("measure passes of the bar during the fill", 0, measures - measuredBefore)
        }
    }

    @Test
    fun `with motion off the fill is at its final length on the next frame`() {
        setBar(motion = false)
        FrameRecorder(compose).use { recorder ->
            val start = filled(recorder.frame())
            compose.change { remaining = 80.0 }
            val first = filled(recorder.next(1).single())
            compose.mainClock.advanceTimeBy(1_000)

            assertTrue("the bar moved at all ($start to $first)", first > start * 3)
            assertEquals(filled(recorder.frame()), first)
        }
    }
}
