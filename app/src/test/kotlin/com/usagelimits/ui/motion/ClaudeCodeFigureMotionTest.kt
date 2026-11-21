package com.usagelimits.ui.motion

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.usagelimits.ui.ClaudeCodeArt
import com.usagelimits.ui.ClaudeCodeBrand
import com.usagelimits.ui.STILL
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.usagelimits.core.model.ClaudeCodeMood
import com.usagelimits.core.model.ProviderId
import com.usagelimits.ui.FigureSlot
import com.usagelimits.ui.LocalAnimateClaudeCodeIcon
import com.usagelimits.ui.ProviderLogo
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

/**
 * The Claude Code figure's loop, stepped on the Compose test clock: when it moves, how often it
 * redraws, and that every reason to stop shows the mood's still pose rather than a frozen frame.
 *
 * Each test draws two figures in the same mood: the subject, and beside it the still pose drawn
 * straight from the art, outside every switch the subject obeys. "Still" below means pixel-equal
 * to that reference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClaudeCodeFigureMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var mood by mutableStateOf(ClaudeCodeMood.WORKING)
    private var animate by mutableStateOf(true)
    private var systemMotion by mutableStateOf(true)
    private var subjectOffset by mutableStateOf(0.dp)
    private var clippedBy: ScrollState? = null
    private var draws = 0

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme(providerIcons = mapOf(ProviderId.CLAUDE.id to "claudecode-color")) {
                // 60dp of ground on every side: particles draw outside the 28dp box.
                Row(Modifier.background(UsageColors.Surface).padding(60.dp)) {
                    CompositionLocalProvider(LocalMotionEnabled provides systemMotion, LocalAnimateClaudeCodeIcon provides animate) {
                        // The layer keeps the subject's drawing to itself, so [draws] counts its own redraws.
                        val subject = Modifier.offset(x = subjectOffset).graphicsLayer().size(28.dp)
                            .drawWithContent { draws++; drawContent() }.testTag("subject")
                        val scroll = clippedBy
                        if (scroll == null) {
                            Box(Modifier.size(28.dp)) { ProviderLogo(ProviderId.CLAUDE, subject, FigureSlot.LIST_ROW, mood) }
                        } else {
                            // A clipped, scrolling parent 100dp tall, as a list is for its rows.
                            Column(Modifier.size(28.dp, 100.dp).verticalScroll(scroll)) {
                                ProviderLogo(ProviderId.CLAUDE, subject, FigureSlot.LIST_ROW, mood)
                                Spacer(Modifier.height(300.dp))
                            }
                        }
                    }
                    Spacer(Modifier.width(120.dp))
                    // The still pose drawn straight from the art, past every switch the subject obeys.
                    val muted = UsageColors.Muted
                    val px = with(LocalDensity.current) { 28.dp.toPx() }
                    val art = remember(mood, px) { ClaudeCodeArt(mood, px) }
                    Spacer(Modifier.size(28.dp).testTag("still").drawBehind {
                        art.draw(this, ClaudeCodeBrand, muted, Offset.Zero, STILL, FigureSlot.LIST_ROW.amplitude)
                    })
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The figure's box and 28dp around it, where its particles fly. Read once per placement: the
     * test rule finds no composition to ask while its activity is below RESUMED.
     */
    private val regions = mutableMapOf<String, Region>()
    private fun region(tag: String) = regions.getOrPut(tag) {
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.toRegion(margin = 56)
    }

    /** The subject's pixels in each of the next [count] frames; the full frames are released. */
    private fun FrameRecorder.subjects(count: Int) = next(count).map { frame -> frame.crop(region("subject")).also { frame.recycle() } }

    /** Whether the subject shows the still pose in each of the next [count] frames. */
    private fun FrameRecorder.stillPoses(count: Int) = next(count).map { frame ->
        val subject = frame.crop(region("subject"))
        val still = frame.crop(region("still"))
        frame.recycle()
        subject.sameAs(still)
    }

    /** How many of [frames] differ from the one before. */
    private fun changes(frames: List<Bitmap>) = frames.zipWithNext().count { (a, b) -> !a.sameAs(b) }

    @Test
    fun `every mood moves and redraws at most 30 times a second`() {
        show()
        FrameRecorder(compose).use { recorder ->
            for (each in ClaudeCodeMood.entries) {
                compose.change { mood = each }
                // The loop starts on the frame after the change.
                recorder.next(2)
                val second = recorder.subjects(60)
                second.saveSequence("claude-${each.name.lowercase()}")
                val changed = changes(second)
                assertTrue("$each moves within a second ($changed changes)", changed >= 10)
                assertTrue("$each redraws at most 30 times in 60 display frames ($changed changes)", changed <= 30)
                // A quarter-second apart over eight seconds: every loop of the mood, including the
                // Idle glance (7.5 s), passes through several of its phases.
                val phases = List(32) {
                    compose.mainClock.advanceTimeBy(250)
                    recorder.frame().let { frame -> frame.crop(region("subject")).also { frame.recycle() } }
                }
                phases.saveSequence("claude-${each.name.lowercase()}-phases")
                assertTrue("$each shows different poses across its loops", changes(phases) >= 16)
            }
        }
    }

    @Test
    fun `a screen below RESUMED shows the still pose and moves again when resumed`() {
        mood = ClaudeCodeMood.DANCING
        show()
        FrameRecorder(compose).use { recorder ->
            recorder.next(2)
            assertTrue("moving while resumed", changes(recorder.subjects(10)) > 0)

            region("still")
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            // Ten frames: each costs the test rule's idle timeout here, as it finds no composition
            // below RESUMED; the Dancing loop redraws on every other one of them.
            assertEquals("still pose in every frame below RESUMED", List(10) { true }, recorder.stillPoses(10))

            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            recorder.next(2)
            assertTrue("moving again once resumed", changes(recorder.subjects(10)) > 0)
        }
    }

    @Test
    fun `turning motion off in Settings or in the system shows the still pose on the next frame`() {
        mood = ClaudeCodeMood.DANCING
        show()
        FrameRecorder(compose).use { recorder ->
            recorder.next(10)
            assertEquals("mid-loop before the switch", listOf(false), recorder.stillPoses(1))

            compose.change { animate = false }
            assertEquals("still pose in every frame with the Settings switch off", List(20) { true }, recorder.stillPoses(20))

            compose.change { animate = true }
            recorder.next(10)
            assertEquals("mid-loop again", listOf(false), recorder.stillPoses(1))
            compose.change { systemMotion = false }
            assertEquals("still pose in every frame with animations removed", List(20) { true }, recorder.stillPoses(20))
        }
    }

    @Test
    fun `a figure outside the window does not run its loop`() {
        mood = ClaudeCodeMood.DANCING
        subjectOffset = 2000.dp
        show()
        FrameRecorder(compose).use { recorder ->
            // Long enough that a running loop would be well into its cycle.
            recorder.next(30)
            compose.change { subjectOffset = 0.dp }
            // The first frame on screen is the still pose: nothing ran while it was out of view.
            assertEquals("the first frame in view is the still pose", listOf(true), recorder.stillPoses(1))
            recorder.next(2)
            assertTrue("moving once in view", changes(recorder.subjects(10)) > 0)
        }
    }

    @Test
    fun `a figure scrolled out of its clipped parent stops its loop and returns from the still pose`() {
        mood = ClaudeCodeMood.DANCING
        val scroll = ScrollState(0)
        clippedBy = scroll
        show()
        val shift = with(compose.density) { 50.dp.toPx() }
        FrameRecorder(compose).use { recorder ->
            recorder.next(2)
            assertTrue("moving while in view", changes(recorder.subjects(10)) > 0)
            region("still")

            // 50dp up: the 28dp box is wholly above its parent's visible rect, still inside the window.
            compose.change { scroll.dispatchRawDelta(shift) }
            recorder.next(4).forEach { it.recycle() }
            val top = compose.onNodeWithTag("subject").fetchSemanticsNode().positionInRoot.y
            assertTrue("the hidden figure is inside the window (top at $top px)", top >= 0f)
            val before = draws
            recorder.next(30).forEach { it.recycle() }
            assertEquals("redraws of the hidden figure over 30 frames", 0, draws - before)

            compose.change { scroll.dispatchRawDelta(-shift) }
            assertEquals("the first frame back in view is the still pose", listOf(true), recorder.stillPoses(1))
            recorder.next(2)
            assertTrue("moving again once in view", changes(recorder.subjects(10)) > 0)
        }
    }
}
