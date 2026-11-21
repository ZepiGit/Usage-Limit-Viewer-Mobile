package com.usagelimits.ui.motion

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.ClaudeCodeMood
import com.usagelimits.core.model.ProviderId
import com.usagelimits.ui.*
import com.usagelimits.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoorayPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val playback = HoorayPlayback()
    private var icon by mutableStateOf("claudecode-color")
    private var slot by mutableStateOf(FigureSlot.LIST_ROW)
    private var second by mutableStateOf(false)
    private var systemMotion by mutableStateOf(false)
    private var appMotion by mutableStateOf(true)
    private var reference by mutableStateOf(ClaudeCodeMood.HOORAY)
    private var ready by mutableStateOf(true)

    private fun show() {
        compose.mainClock.autoAdvance = false
        playback.open(setOf("claude"))
        compose.setContent {
            UsageLimitsTheme(providerIcons = mapOf(ProviderId.CLAUDE.id to icon)) {
                CompositionLocalProvider(LocalHoorayPlayback provides playback, LocalMotionEnabled provides systemMotion,
                    LocalAnimateClaudeCodeIcon provides appMotion) {
                    val hoorayLayout = HoorayPresentation(playback, ready = ready)
                    Row(Modifier.background(UsageColors.Surface).padding(40.dp).then(hoorayLayout), horizontalArrangement = Arrangement.spacedBy(70.dp)) {
                        ProviderLogo(ProviderId.CLAUDE, Modifier.size(28.dp).testTag("first"), slot, ClaudeCodeMood.IDLE, "claude")
                        if (second) ProviderLogo(ProviderId.CLAUDE, Modifier.size(28.dp).testTag("second"), FigureSlot.LIST_ROW, ClaudeCodeMood.IDLE, "claude")
                        val size = with(LocalDensity.current) { 28.dp.toPx() }
                        val art = remember(reference, size) { ClaudeCodeArt(reference, size) }
                        val ink = UsageColors.Muted
                        Spacer(Modifier.size(28.dp).testTag("reference").drawBehind {
                            art.draw(this, ClaudeCodeBrand, ink, Offset.Zero, STILL, FigureSlot.LIST_ROW.amplitude)
                        })
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(80)
    }

    private fun matches(recorder: FrameRecorder, tag: String = "first"): Boolean {
        val subject = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.toRegion(margin = 24)
        val expected = compose.onNodeWithTag("reference").fetchSemanticsNode().boundsInRoot.toRegion(margin = 24)
        val frame = recorder.frame()
        val actual = frame.crop(subject)
        val reference = frame.crop(expected)
        return try { actual.sameAs(reference) } finally { actual.recycle(); reference.recycle(); frame.recycle() }
    }

    @Test fun `elapsed boundary is exactly four seconds and does not depend on motion scale`() {
        playback.visible("claude")
        playback.open(setOf("claude"))
        playback.startVisible(100L)
        playback.advance(4_000_000_099L)
        assertEquals("still Hooray one nanosecond before four seconds", 100L, playback.startedAt("claude"))
        playback.advance(4_000_000_100L)
        assertNull("steady mood at exactly four elapsed seconds", playback.startedAt("claude"))
        playback.hidden("claude")
    }

    @Test fun `real slot shows still Hooray for four elapsed seconds shared with later slots`() {
        show()
        FrameRecorder(compose).use { recorder ->
            assertTrue("first eligible slot renders Hooray with system motion off", matches(recorder))
            val start = playback.startedAt("claude")!!
            compose.mainClock.advanceTimeBy(3000)
            compose.change { second = true }
            compose.mainClock.advanceTimeBy(64)
            assertEquals("a second slot shares the original start", start, playback.startedAt("claude"))
            assertTrue("later slot renders the same Hooray", matches(recorder, "second"))
            compose.mainClock.advanceTimeBy(1100)
            compose.change { reference = ClaudeCodeMood.IDLE }
            compose.mainClock.advanceTimeByFrame()
            assertTrue("first slot returns to steady mood after four elapsed seconds", matches(recorder))
            assertTrue("later slot has no extra four seconds", matches(recorder, "second"))
            recorder.frame().also { it.saveFrame("hooray-expired", 0); it.recycle() }
        }
    }

    @Test fun `enrollment waits for the destination to finish entering without spending the cheer`() {
        ready = false
        show()
        compose.mainClock.advanceTimeBy(500)
        assertNull("entering destination has not started the presentation", playback.startedAt("claude"))
        compose.change { ready = true }
        compose.mainClock.advanceTimeBy(80)
        FrameRecorder(compose).use { recorder ->
            assertTrue("first eligible resumed destination renders Hooray", matches(recorder))
        }
    }

    @Test fun `app motion off still presents Hooray and interruption cannot restart it`() {
        appMotion = false; systemMotion = true
        show()
        FrameRecorder(compose).use { recorder ->
            assertTrue("app motion off renders still Hooray", matches(recorder))
            recorder.frame().also { it.saveFrame("hooray-still", 0); it.recycle() }
            compose.change { playback.stop(); reference = ClaudeCodeMood.IDLE }
            compose.mainClock.advanceTimeByFrame()
            compose.change { second = true }
            compose.mainClock.advanceTimeBy(64)
            assertTrue("interrupted presentation cannot restart in another slot", matches(recorder, "second"))
        }
    }

    @Test fun `Resets sample previews and plain art do not start or preserve a later replay`() {
        for (ineligible in listOf(FigureSlot.RESETS, FigureSlot.LIST_PREVIEW, FigureSlot.DIAL_PREVIEW)) {
            slot = ineligible
            if (ineligible == FigureSlot.RESETS) {
                show()
            } else {
                compose.change { playback.open(setOf("claude")); slot = ineligible }
                compose.mainClock.advanceTimeBy(80)
            }
            assertNull("$ineligible cannot start an opening presentation", playback.startedAt("claude"))
        }
        compose.change { playback.open(setOf("claude")); slot = FigureSlot.LIST_ROW; icon = "claude-color" }
        compose.mainClock.advanceTimeBy(80)
        assertNull("plain artwork cannot start the cheer", playback.startedAt("claude"))
        compose.change { icon = "claudecode-color"; reference = ClaudeCodeMood.IDLE }
        compose.mainClock.advanceTimeBy(80)
        FrameRecorder(compose).use { recorder -> assertTrue("changing artwork later does not replay the opening", matches(recorder)) }
    }
}
