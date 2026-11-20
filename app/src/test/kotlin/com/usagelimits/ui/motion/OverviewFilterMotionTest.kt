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
import com.usagelimits.feature.overview.OverviewFilter
import com.usagelimits.feature.overview.OverviewScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * A filter that empties the Overview list (#81, noted in #26; the Accounts screen's case, retargeted
 * when Overview took its filters): the account rows leave first, then "No accounts match this
 * filter." arrives. The old rows must never show through the sentence.
 *
 * Three pixels inside the no-match line's text box tell the layers apart. The line sits on card
 * surface where the first row was: B is plain surface before and after, C carried the row's
 * content (its logo or title) and settles to plain surface, S was plain row surface and settles
 * to a glyph of the sentence. In any frame the row still shows where C differs from B, and the
 * sentence shows where S differs from B; the two must never be true together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h700dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverviewFilterMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val surface = UsageColors.Surface.argb
    private val radioButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private data class Run(val before: Bitmap, val frames: List<Bitmap>, val settled: Bitmap, val box: IntArray)

    /** Shows two healthy accounts, picks "Needs attention" (which matches neither) and steps the frames. */
    private fun emptyTheList(motion: Boolean, name: String): Run {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme {
                CompositionLocalProvider(LocalMotionEnabled provides motion) {
                    var filter by remember { mutableStateOf(OverviewFilter.ALL) }
                    OverviewScreen(
                        state = UsageUiState(accounts = listOf(
                            demoAccount("one", ProviderId.CODEX), demoAccount("two", ProviderId.CLAUDE),
                        )),
                        nowMs = MOTION_NOW, onRefresh = {}, onAccountClick = {}, onAddAccount = {},
                        onPercentDisplayChange = {}, filter = filter, onFilterChange = { filter = it },
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

    private data class Probes(val sentence: Pair<Int, Int>, val content: Pair<Int, Int>, val plain: Pair<Int, Int>)

    private fun Bitmap.at(p: Pair<Int, Int>) = at(p.first, p.second)

    /** S, C and B as the class comment describes them, all inside the sentence's box. */
    private fun probes(run: Run): Probes {
        val (left, top, right, bottom) = run.box
        var sentence: Pair<Int, Int>? = null
        var content: Pair<Int, Int>? = null
        var plain: Pair<Int, Int>? = null
        for (y in top until bottom) for (x in left until right) {
            val before = run.before.at(x, y)
            val after = run.settled.at(x, y)
            // The brightest glyph pixel gives the widest margin over anti-aliased edges.
            if (before == surface && after != surface &&
                (sentence == null || brightness(after) > brightness(run.settled.at(sentence)))) sentence = x to y
            if (before != surface && after == surface && (content == null ||
                    brightness(before) > brightness(run.before.at(content)))) content = x to y
            if (before == surface && after == surface && plain == null) plain = x to y
        }
        return Probes(checkNotNull(sentence) { "no sentence pixel over plain row surface" },
            checkNotNull(content) { "no row content under the sentence's box" },
            checkNotNull(plain) { "no plain surface in the sentence's box" })
    }

    private fun brightness(argb: Int) = (argb shr 16 and 0xFF) + (argb shr 8 and 0xFF) + (argb and 0xFF)

    @Test
    fun `the empty sentence waits until the old cards are gone`() {
        val run = emptyTheList(motion = true, name = "overview-filter-empties")
        val (sentence, content, plain) = probes(run)
        fun Bitmap.rowShows() = at(content) != at(plain)
        fun Bitmap.sentenceShows() = at(sentence) != at(plain)

        val overlapping = run.frames.withIndex().filter { (_, frame) -> frame.rowShows() && frame.sentenceShows() }.map { it.index }
        assertEquals("frames drawing the sentence over a row that is still fading", emptyList<Int>(), overlapping)

        // Both halves were really observed mid-way, so the sequence above is not vacuous.
        assertTrue("the row fades out over several frames", run.frames.count {
            it.rowShows() && it.at(content) != run.before.at(content)
        } >= 2)
        assertTrue("the sentence fades in over several frames", run.frames.count {
            it.sentenceShows() && it.at(sentence) != run.settled.at(sentence)
        } >= 2)
    }

    @Test
    fun `with motion off the list is in its final state on the next frame`() {
        val run = emptyTheList(motion = false, name = "overview-filter-empties-reduced")

        assertTrue("the first frame after the filter is already the settled one", run.frames.first().sameAs(run.settled))
    }
}
