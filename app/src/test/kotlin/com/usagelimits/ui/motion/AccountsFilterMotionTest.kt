package com.usagelimits.ui.motion

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.usagelimits.core.model.ProviderId
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.accounts.AccountsScreen
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
 * A filter that empties the Accounts list (#81, noted in #26): the cards leave first, then
 * "No accounts match this filter." arrives. The old cards must never show through the sentence.
 *
 * Two pixels inside the empty box tell the two layers apart. Both are plain card surface before
 * the filter; afterwards one is lit by the sentence and the other is bare ground. While the
 * cards fade, the two stay equal; they may only differ once the cards are gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h700dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountsFilterMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ground = UsageColors.Ground.argb
    private val surface = UsageColors.Surface.argb
    private val radioButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private data class Run(val before: Bitmap, val frames: List<Bitmap>, val settled: Bitmap, val box: IntArray)

    /** Shows two healthy accounts, picks "Needs attention" (which matches neither) and steps the frames. */
    private fun emptyTheList(motion: Boolean, name: String): Run {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme {
                CompositionLocalProvider(LocalMotionEnabled provides motion) {
                    AccountsScreen(
                        state = UsageUiState(accounts = listOf(
                            demoAccount("one", ProviderId.CODEX), demoAccount("two", ProviderId.CLAUDE),
                        )),
                        nowMs = MOTION_NOW, onAccountClick = {}, onAddAccount = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val recorder = FrameRecorder(compose)
        val before = recorder.frame()
        // The semantics action, not a touch: injected touch events advance the held clock.
        compose.onNode(hasText("Needs attention") and radioButton).performSemanticsAction(SemanticsActions.OnClick)
        compose.change {}
        val frames = recorder.next(30)
        compose.mainClock.advanceTimeBy(1_000)
        val settled = recorder.frame()
        recorder.close()
        (listOf(before) + frames + settled).saveSequence(name)
        val bounds = compose.onNodeWithText("No accounts match this filter.").fetchSemanticsNode().boundsInWindow
        return Run(before, frames, settled, intArrayOf(
            bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt(),
        ))
    }

    /** A pixel of the sentence and a bare pixel beside it, both plain card surface before the filter. */
    private fun probes(run: Run): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val (left, top, right, bottom) = run.box
        var sentence: Pair<Int, Int>? = null
        var bare: Pair<Int, Int>? = null
        for (y in top until bottom) for (x in left until right) {
            if (run.before.at(x, y) != surface) continue
            val after = run.settled.at(x, y)
            // The brightest glyph pixel gives the widest margin over anti-aliased edges.
            if (after != ground && (sentence == null || brightness(after) > brightness(run.settled.at(sentence.first, sentence.second)))) {
                sentence = x to y
            }
            if (after == ground && bare == null) bare = x to y
        }
        return checkNotNull(sentence) { "no sentence pixel over plain card surface" } to
            checkNotNull(bare) { "no bare pixel over plain card surface" }
    }

    private fun brightness(argb: Int) = (argb shr 16 and 0xFF) + (argb shr 8 and 0xFF) + (argb and 0xFF)

    @Test
    fun `the empty sentence waits until the old cards are gone`() {
        val run = emptyTheList(motion = true, name = "accounts-filter-empties")
        val (sentence, bare) = probes(run)

        val overlapping = run.frames.withIndex().filter { (_, frame) ->
            val card = frame.at(bare.first, bare.second)
            card != ground && frame.at(sentence.first, sentence.second) != card
        }.map { it.index }
        assertEquals("frames drawing the sentence over a card that is still fading", emptyList<Int>(), overlapping)

        // Both halves were really observed mid-way, so the sequence above is not vacuous.
        assertTrue("the cards fade out over several frames", run.frames.count {
            it.at(bare.first, bare.second).let { c -> c != ground && c != surface }
        } >= 2)
        assertTrue("the sentence fades in over several frames", run.frames.count {
            val c = it.at(sentence.first, sentence.second)
            it.at(bare.first, bare.second) == ground && c != ground && c != run.settled.at(sentence.first, sentence.second)
        } >= 2)
    }

    @Test
    fun `with motion off the list is in its final state on the next frame`() {
        val run = emptyTheList(motion = false, name = "accounts-filter-empties-reduced")

        assertTrue("the first frame after the filter is already the settled one", run.frames.first().sameAs(run.settled))
    }
}
