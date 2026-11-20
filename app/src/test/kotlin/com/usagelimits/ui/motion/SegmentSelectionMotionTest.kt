package com.usagelimits.ui.motion

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.ui.components.Segment
import com.usagelimits.ui.components.SegmentedRow
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageLimitsTheme
import com.usagelimits.ui.theme.colors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** A segment's pill fades in when it becomes the selection and out when it stops being it (#81). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h200dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentSelectionMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var second by mutableStateOf(false)
    private val radioButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    /** The colour of the "Needs attention" pill, sampled left of its label, on each of the next frames. */
    private fun pillColours(motion: Boolean, name: String): Pair<List<Int>, Int> {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme {
                CompositionLocalProvider(LocalMotionEnabled provides motion) {
                    Box(Modifier.background(UsageColors.Ground).padding(24.dp)) {
                        SegmentedRow {
                            Segment("All", selected = !second) {}
                            Segment("Needs attention", selected = second) {}
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val bounds = compose.onNode(hasText("Needs attention") and radioButton).fetchSemanticsNode().boundsInWindow
        // 6dp in: inside the pill, clear of the label's 14dp side padding.
        val x = (bounds.left + 6 * compose.density.density).roundToInt()
        val y = ((bounds.top + bounds.bottom) / 2).roundToInt()
        return FrameRecorder(compose).use { recorder ->
            val before = recorder.frame()
            assertEquals("an unselected pill is bare ground", Color.Black.argb, before.at(x, y))
            compose.change { second = true }
            val frames = recorder.next(12)
            compose.mainClock.advanceTimeBy(1_000)
            val settled = recorder.frame()
            (listOf(before) + frames + settled).saveSequence(name)
            frames.map { it.at(x, y) } to settled.at(x, y)
        }
    }

    @Test
    fun `the selected pill fades in`() {
        val (colours, settled) = pillColours(motion = true, name = "segment-selection")

        assertEquals(AppTheme.PURE_BLACK.colors.Raised.argb, settled)
        assertTrue(
            "the pill passes through tones between ground and raised: ${colours.map(Integer::toHexString)}",
            colours.filter { it != Color.Black.argb && it != settled }.distinct().size >= 2,
        )
    }

    @Test
    fun `with motion off the pill is selected on the next frame`() {
        val (colours, settled) = pillColours(motion = false, name = "segment-selection-reduced")

        assertEquals(AppTheme.PURE_BLACK.colors.Raised.argb, settled)
        assertEquals(settled, colours.first())
    }
}
