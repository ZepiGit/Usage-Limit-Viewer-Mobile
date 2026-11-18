package com.usagelimits.ui.motion

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.overview.AccountCard
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.ui.theme.LocalMotionEnabled
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
 * A figure changes the way its bar does: it fades to the next value, and what follows it glides (#81).
 *
 * Native graphics, because the legacy mode measures every character as one pixel wide and a
 * figure that loses two digits would move its neighbour by two pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h700dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigureMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var used by mutableStateOf(40.0)
    private var mode by mutableStateOf(PercentDisplay.LEFT)

    private fun text(value: String) = compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes()

    private fun setCard(motion: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme {
                CompositionLocalProvider(LocalMotionEnabled provides motion) {
                    AccountCard(demoAccount("one", ProviderId.CODEX, used), MOTION_NOW, 3_600_000, mode) {}
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun `a row figure fades to its next value instead of cutting`() {
        setCard(motion = true)
        assertEquals(1, text("60%").size)

        compose.change { mode = PercentDisplay.USED }
        compose.mainClock.advanceTimeBy(48)
        assertTrue("the outgoing figure is still composed mid-fade", text("60%").isNotEmpty())
        assertTrue("the incoming figure is already there", text("40%").isNotEmpty())

        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("the outgoing figure is gone once the fade has finished", 0, text("60%").size)
        assertEquals(1, text("40%").size)
    }

    @Test
    fun `with motion off a row figure is replaced on the next frame`() {
        setCard(motion = false)

        compose.change { mode = PercentDisplay.USED }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(0, text("60%").size)
        assertEquals(1, text("40%").size)
    }

    @Test
    fun `the word after the lead figure glides when the figure loses a digit`() {
        used = 0.0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme {
                CompositionLocalProvider(LocalMotionEnabled provides true) {
                    OverviewScreen(
                        state = UsageUiState(
                            accounts = listOf(demoAccount("one", ProviderId.CODEX, used)),
                            settings = AppSettings(percentDisplay = PercentDisplay.LEFT),
                        ),
                        nowMs = MOTION_NOW, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onReorder = {},
                        onPercentDisplayChange = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        // The lead strip's side word is the topmost "left" on the screen; the row's is below it.
        fun wordLeft() = text("left").minBy { it.boundsInWindow.top }.boundsInWindow.left

        val start = wordLeft()
        val positions = FrameRecorder(compose).use { recorder ->
            val frames = mutableListOf(recorder.frame())
            compose.change { used = 95.0 } // "100%" becomes "5%"
            List(24) {
                frames += recorder.next(1)
                wordLeft()
            }.also { frames.saveSequence("overview-figure-and-bar") }
        }

        assertTrue("the word ends closer to the edge than it started ($start to ${positions.last()})", positions.last() < start)
        // The old, wider figure takes 90 ms to leave; a word that moved sooner would slide over it.
        assertEquals("the word stays put while the old figure fades out", List(5) { start }, positions.take(5))
        assertTrue(
            "the word passes through places in between instead of jumping: $positions",
            positions.filter { it < start && it > positions.last() }.distinct().size >= 4,
        )
    }
}
