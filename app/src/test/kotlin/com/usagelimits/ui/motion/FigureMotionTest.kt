package com.usagelimits.ui.motion

import android.graphics.Bitmap
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.overview.AccountCard
import com.usagelimits.feature.overview.OverviewScreen
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
 * A figure and the word that says which side it counts are one unit (#81): "60% left" leaves as a
 * whole, then "40% used" arrives. No frame shows a figure beside the other side's word, or one
 * window's figure under another window's name.
 *
 * Native graphics, because the pixel checks need real glyphs, and the legacy mode measures every
 * character as one pixel wide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h700dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigureMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var accounts by mutableStateOf(listOf(demoAccount("one", ProviderId.CODEX, 40.0)))
    private var mode by mutableStateOf(PercentDisplay.LEFT)
    private val surface = UsageColors.Surface.argb

    private fun text(value: String) = compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes()

    /** The lead strip, found by the label its tap action carries in either mode. */
    private val lead = SemanticsMatcher("the lead strip") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label in setOf("Show percentage used", "Show percentage left")
    }

    private fun leadText(value: String) =
        compose.onAllNodes(hasText(value) and hasAnyAncestor(lead), useUnmergedTree = true).fetchSemanticsNodes()

    private fun rowText(value: String): List<SemanticsNode> {
        val inLead = leadText(value).map { it.id }.toSet()
        return text(value).filter { it.id !in inLead }
    }

    private fun setCard(motion: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme {
                CompositionLocalProvider(LocalMotionEnabled provides motion) {
                    AccountCard(accounts.single(), MOTION_NOW, 3_600_000, mode) {}
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    /** [motion] null leaves the decision to the theme, which reads the system's animator duration scale. */
    private fun setOverview(motion: Boolean?) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme {
                val screen = @Composable {
                    OverviewScreen(
                        state = UsageUiState(accounts = accounts, settings = AppSettings(percentDisplay = mode)),
                        nowMs = MOTION_NOW, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onReorder = {},
                        onPercentDisplayChange = {},
                    )
                }
                if (motion == null) screen() else CompositionLocalProvider(LocalMotionEnabled provides motion) { screen() }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun setAnimatorScale(scale: Float) = Settings.Global.putFloat(
        ApplicationProvider.getApplicationContext<Context>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale,
    )

    private fun region(nodes: List<SemanticsNode>) = nodes.map { it.boundsInWindow.toRegion() }.reduce(Region::union)

    private data class Flip(val before: Bitmap, val frames: List<Bitmap>, val settled: Bitmap, val region: Region)

    /** Flips LEFT to USED and steps 20 frames; [unit] finds the figure and its word in the given mode. */
    private fun flip(name: String, unit: (figure: String, word: String) -> List<SemanticsNode>): Flip =
        FrameRecorder(compose).use { recorder ->
            val before = recorder.frame()
            val from = region(unit("60%", "left"))
            compose.change { mode = PercentDisplay.USED }
            val frames = recorder.next(20)
            compose.mainClock.advanceTimeBy(1_000)
            val settled = recorder.frame()
            (listOf(before) + frames + settled).saveSequence(name)
            Flip(before, frames, settled, from.union(region(unit("40%", "used"))))
        }

    /**
     * Every frame of the flip shows the old pair or the new pair, never a mix. A frame's ink that
     * lies outside what the old pair covers AND outside what the new pair covers is a mix: the
     * old figure beside the new word, or two figures on top of each other.
     */
    private fun assertFadesThroughAsOneUnit(flip: Flip) {
        val (before, frames, settled, region) = flip
        val mixed = frames.withIndex().filter { (_, frame) ->
            frame.inkOutside(before, region, surface) > 0 && frame.inkOutside(settled, region, surface) > 0
        }.map { it.index }
        assertEquals("frames that are neither the old pair nor the new pair", emptyList<Int>(), mixed)

        // Both halves were seen, so the check above is not vacuous: the old pair, visibly fading...
        val leaving = frames.takeWhile { it.inkOutside(before, region, surface) == 0 }
        assertTrue("the old pair is still there for several frames (${leaving.size})", leaving.size >= 3)
        assertTrue("and it fades: fewer and fainter pixels than at rest",
            leaving.drop(1).any { !it.sameAs(before, region.left, region.top, region.right, region.bottom) })
        // ...then the new pair, fading in until it is the settled picture.
        val arriving = frames.drop(leaving.size)
        assertTrue("the new pair fades in over several frames",
            arriving.count { it.ink(region, surface) > 0 && !it.sameAs(settled, region.left, region.top, region.right, region.bottom) } >= 3)
        assertTrue("the flip ends on the settled picture", frames.last().sameAs(settled))
    }

    // MARK: - Mode flip

    @Test
    fun `on a mode flip a row's figure leaves with its own word before the new pair arrives`() {
        setCard(motion = true)
        assertFadesThroughAsOneUnit(flip("row-mode-flip") { figure, word -> text(figure) + text(word) })
    }

    @Test
    fun `on a mode flip the lead's figure leaves with its own word before the new pair arrives`() {
        setOverview(motion = true)
        assertFadesThroughAsOneUnit(flip("lead-mode-flip") { figure, word -> leadText(figure) + leadText(word) })
    }

    @Test
    fun `during a flip a screen reader reaches the current pair only and the description is the current one`() {
        setCard(motion = true)
        compose.change { mode = PercentDisplay.USED }

        repeat(16) { frame ->
            compose.mainClock.advanceTimeByFrame()
            // The merged tree, which is what a screen reader walks: the pair that is leaving is
            // still drawn (and still in the unmerged tree), but it is no longer part of the row.
            val reachable = listOf("60%", "left", "40%", "used").filter { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("frame $frame: figure and word a screen reader reaches", listOf("40%", "used"), reachable)
            assertEquals("frame $frame: the row's spoken sentence", 1,
                compose.onAllNodes(SemanticsMatcher("says 40% used") { node ->
                    node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                        ?.any { "40% used" in it } == true
                }).fetchSemanticsNodes().size)
        }
    }

    // The two below go through the real switch: the system setting the theme observes, not a
    // composition local set by the test.
    @Test
    fun `with the system's animations on the lead fades through`() {
        setAnimatorScale(1f)
        setOverview(motion = null)
        assertFadesThroughAsOneUnit(flip("lead-mode-flip-system-on") { figure, word -> leadText(figure) + leadText(word) })
    }

    @Test
    fun `with the system's animations off a row and the lead show the new pair on the next frame`() {
        setAnimatorScale(0f)
        assertMotionOffShowsTheNewPairAtOnce(motion = null)
    }

    @Test
    fun `with motion off a row and the lead show the new pair on the next frame`() = assertMotionOffShowsTheNewPairAtOnce(motion = false)

    private fun assertMotionOffShowsTheNewPairAtOnce(motion: Boolean?) {
        setOverview(motion)
        FrameRecorder(compose).use { recorder ->
            recorder.frame()
            compose.change { mode = PercentDisplay.USED }
            val first = recorder.next(1).single()
            compose.mainClock.advanceTimeBy(1_000)

            assertTrue("the first frame after the flip is the settled one", first.sameAs(recorder.frame()))
            assertEquals(0, text("60%").size + text("left").size)
            assertEquals(2, leadText("40%").size + rowText("40%").size)
        }
    }

    // MARK: - A refresh

    @Test
    fun `a refresh fades the figure through as a row's bar moves`() {
        setCard(motion = true)

        compose.change { accounts = listOf(demoAccount("one", ProviderId.CODEX, 60.0)) } // 60% left becomes 40% left
        compose.mainClock.advanceTimeBy(48)
        assertTrue("the new figure is composed while the old one leaves", text("40%").isNotEmpty())

        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, text("40%").size)
        assertEquals(0, text("60%").size)
    }

    // MARK: - Another window, another account

    /** How many pixels of the card's bar carry the fill colour. */
    private fun barFill(frame: Bitmap): Int {
        var count = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) if (frame.at(x, y) == UsageColors.Data.argb) count++
        return count
    }

    @Test
    fun `a different window in the same slot shows its own figure and bar at once`() {
        setCard(motion = true)
        FrameRecorder(compose).use { recorder ->
            recorder.frame()
            // The 5h window (60% left) is replaced by the weekly one (10% left) in the same slot.
            compose.change {
                accounts = listOf(demoAccount("one", ProviderId.CODEX, 90.0, "week", "Weekly", WindowCategory.WEEKLY))
            }
            val first = recorder.next(1).single()
            val reachable = listOf("60%", "10%").filter { text(it).isNotEmpty() }
            compose.mainClock.advanceTimeBy(1_000)
            val settled = recorder.frame()

            assertEquals("the other window's figure is not carried over", listOf("10%"), reachable)
            assertEquals("the bar does not glide from the other window's value", barFill(settled), barFill(first))
            assertTrue("the first frame is the settled one", first.sameAs(settled))
        }
    }

    @Test
    fun `a lead that changes to another account shows that account's figure and bar at once`() {
        // The lead is the account whose window resets next: account one, in an hour.
        accounts = listOf(demoAccount("one", ProviderId.CODEX, 40.0), demoAccount("two", ProviderId.CLAUDE, 10.0, resetInMs = 7_200_000))
        setOverview(motion = true)
        assertEquals("account one leads with 60% left", 1, leadText("60%").size)
        val leadBounds = compose.onAllNodes(lead).fetchSemanticsNodes().single().boundsInWindow.toRegion(0)

        FrameRecorder(compose).use { recorder ->
            recorder.frame()
            // Account two now resets sooner, at 5% left, and takes the lead; account one keeps its reading.
            compose.change {
                accounts = listOf(demoAccount("one", ProviderId.CODEX, 40.0), demoAccount("two", ProviderId.CLAUDE, 95.0, resetInMs = 1_800_000))
            }
            val first = recorder.next(1).single()
            val inLead = listOf("60%", "5%").filter { leadText(it).isNotEmpty() }
            compose.mainClock.advanceTimeBy(1_000)
            val settled = recorder.frame()

            assertEquals("account one's figure does not linger under account two's name", listOf("5%"), inLead)
            assertTrue("the lead strip is settled on the first frame",
                first.sameAs(settled, leadBounds.left, leadBounds.top, leadBounds.right, leadBounds.bottom))
        }
    }

    @Test
    fun `a reading that becomes known replaces the dash at once`() {
        accounts = listOf(demoAccount("one", ProviderId.CODEX, null))
        setCard(motion = true)
        assertEquals("an unknown reading has no side word", 0, text("left").size)

        compose.change { accounts = listOf(demoAccount("one", ProviderId.CODEX, 40.0)) }
        compose.mainClock.advanceTimeByFrame()

        assertEquals(listOf("60%", "left"), listOf("—", "60%", "left").filter { text(it).isNotEmpty() })
    }
}
