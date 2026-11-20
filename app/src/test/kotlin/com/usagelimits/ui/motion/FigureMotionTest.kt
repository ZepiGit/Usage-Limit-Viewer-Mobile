package com.usagelimits.ui.motion

import android.graphics.Bitmap
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.ui.components.FigureWithSide
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
 * A row's figure fades through on a mode flip or refresh, without carrying another window's value,
 * and the header chip's word changes in step with it (#88 01b review, item 9): no frame shows the
 * old figure under the new word or the new figure under the old word.
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

    private fun rowText(value: String): List<SemanticsNode> = text(value)

    private fun setCard(motion: Boolean) = setOverview(motion)

    /** [motion] null leaves the decision to the theme, which reads the system's animator duration scale. */
    private fun setOverview(motion: Boolean?) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme {
                val screen = @Composable {
                    OverviewScreen(
                        state = UsageUiState(accounts = accounts, settings = AppSettings(percentDisplay = mode)),
                        nowMs = MOTION_NOW, onRefresh = {}, onAccountClick = {}, onAddAccount = {},
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

    /** Flips LEFT to USED and steps 20 frames; [unit] finds the figure in the given mode. */
    private fun flip(name: String, unit: (figure: String) -> List<SemanticsNode>): Flip =
        FrameRecorder(compose).use { recorder ->
            val before = recorder.frame()
            val from = region(unit("60%"))
            compose.change { mode = PercentDisplay.USED }
            val frames = recorder.next(20)
            compose.mainClock.advanceTimeBy(1_000)
            val settled = recorder.frame()
            (listOf(before) + frames + settled).saveSequence(name)
            Flip(before, frames, settled, from.union(region(unit("40%"))))
        }

    /**
     * Every frame of the flip shows the old figure or the new figure, never both. Ink outside
     * both settled figures indicates that the figures overlap.
     */
    private fun assertFadesThroughAsOneUnit(flip: Flip) {
        val (before, frames, settled, region) = flip
        val mixed = frames.withIndex().filter { (_, frame) ->
            frame.inkOutside(before, region, surface) > 0 && frame.inkOutside(settled, region, surface) > 0
        }.map { it.index }
        assertEquals("frames that are neither the old figure nor the new figure", emptyList<Int>(), mixed)

        // Both halves were seen, so the check above is not vacuous: the old figure, visibly fading...
        val leaving = frames.takeWhile { it.inkOutside(before, region, surface) == 0 }
        assertTrue("the old figure is still there for several frames (${leaving.size})", leaving.size >= 3)
        assertTrue("and it fades: fewer and fainter pixels than at rest",
            leaving.drop(1).any { !it.sameAs(before, region.left, region.top, region.right, region.bottom) })
        // ...then the new figure, fading in until it is the settled picture.
        val arriving = frames.drop(leaving.size)
        assertTrue("the new figure fades in over several frames",
            arriving.count { it.ink(region, surface) > 0 && !it.sameAs(settled, region.left, region.top, region.right, region.bottom) } >= 3)
        assertTrue("the flip ends on the settled picture", frames.last().sameAs(settled))
    }

    /** The summed per-channel difference of two frames inside [region]. */
    private fun distance(a: Bitmap, b: Bitmap, region: Region): Long {
        var sum = 0L
        for (y in region.top until region.bottom) for (x in region.left until region.right) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            for (shift in intArrayOf(16, 8, 0)) sum += kotlin.math.abs((p shr shift and 0xFF) - (q shr shift and 0xFF))
        }
        return sum
    }

    /**
     * Where one part of the screen stands on each frame of a flip: 'o' while it still looks like
     * the frame before the flip, 'n' once it looks like the settled frame, '-' in between (the
     * fade-through's dim middle). "Looks like" is within a quarter of the whole change, measured in
     * the union of the old and new text boxes, so a box that resizes does not read as both.
     */
    private fun phases(before: Bitmap, frames: List<Bitmap>, settled: Bitmap, region: Region): String {
        val whole = distance(before, settled, region)
        check(whole > 0) { "the part does not change in this flip" }
        return frames.joinToString("") { frame ->
            when {
                distance(frame, before, region) * 4 < whole -> "o"
                distance(frame, settled, region) * 4 < whole -> "n"
                else -> "-"
            }
        }
    }

    /** The chip's and the row figure's phases over a LEFT to USED flip. */
    private fun phasesDuringFlip(name: String): Pair<String, String> {
        val chipBefore = text("% left").single().boundsInWindow.toRegion(margin = 0)
        val figureBefore = rowText("60%").single().boundsInWindow.toRegion(margin = 0)
        return FrameRecorder(compose).use { recorder ->
            val before = recorder.frame()
            compose.change { mode = PercentDisplay.USED }
            val frames = recorder.next(24)
            compose.mainClock.advanceTimeBy(1_000)
            val settled = recorder.frame()
            (listOf(before) + frames + settled).saveSequence(name)
            val chip = chipBefore.union(text("% used").single().boundsInWindow.toRegion(margin = 0))
            val figure = figureBefore.union(rowText("40%").single().boundsInWindow.toRegion(margin = 0))
            phases(before, frames, settled, chip) to phases(before, frames, settled, figure)
        }
    }

    private fun assertChipAndFiguresSwitchTogether(phases: Pair<String, String>) {
        val (chip, figure) = phases
        val wrong = chip.indices.filter { (chip[it] == 'n' && figure[it] == 'o') || (chip[it] == 'o' && figure[it] == 'n') }
        assertEquals("frames where the chip and the figure stand on opposite sides of the switch\nchip   $chip\nfigure $figure",
            emptyList<Int>(), wrong)
        // Both halves were seen, so the check is not vacuous.
        assertTrue("the old pair is seen mid-flip:\nchip   $chip\nfigure $figure", chip.indices.any { chip[it] == 'o' && figure[it] == 'o' })
        assertTrue("the new pair is seen mid-flip:\nchip   $chip\nfigure $figure", chip.indices.any { chip[it] == 'n' && figure[it] == 'n' })
    }

    // MARK: - Mode flip

    // Through the real switch: the system setting the theme observes, not a composition local.
    @Test
    fun `with the system's animations on the chip and the figures change at the same point`() {
        setAnimatorScale(1f)
        setOverview(motion = null)
        assertChipAndFiguresSwitchTogether(phasesDuringFlip("row-and-chip-flip-system-on"))
    }

    @Test
    fun `on a mode flip a row's figure leaves before the new figure arrives`() {
        setCard(motion = true)
        assertFadesThroughAsOneUnit(flip("row-mode-flip") { figure -> rowText(figure) })
    }

    @Test
    fun `during a flip a screen reader reaches only the current row description and no figure text`() {
        setCard(motion = true)
        compose.change { mode = PercentDisplay.USED }

        repeat(16) { frame ->
            compose.mainClock.advanceTimeByFrame()
            for ((description, expected) in listOf("60% remaining" to 0, "40% used" to 1)) {
                assertEquals("frame $frame: row description $description", expected,
                    compose.onAllNodes(SemanticsMatcher("says $description") { node ->
                        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                            ?.any { description in it } == true
                    }).fetchSemanticsNodes().size)
            }
            // The merged tree, which is what a screen reader walks, without the nodes it skips: the
            // leaving and the arriving figure are not a second, separate figure there, nor part of
            // the pressable row's text; the line's sentence says it once.
            assertEquals("frame $frame: figure texts a screen reader reaches", emptyList<String>(),
                listOf("60%", "40%").filter { figure ->
                    compose.onAllNodes(hasText(figure, substring = true) and
                        !SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HideFromAccessibility))
                        .fetchSemanticsNodes().isNotEmpty()
                })
            assertEquals("frame $frame: the chip states the new side only", 1,
                compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "% used"))
                    .fetchSemanticsNodes().size)
        }
    }

    // The system-off test goes through the setting the theme observes, not a test composition local.
    @Test
    fun `with the system's animations off a row shows the new figure on the next frame`() {
        setAnimatorScale(0f)
        assertMotionOffShowsTheNewPairAtOnce(motion = null)
    }

    @Test
    fun `with motion off a row shows the new figure on the next frame`() = assertMotionOffShowsTheNewPairAtOnce(motion = false)

    private fun assertMotionOffShowsTheNewPairAtOnce(motion: Boolean?) {
        setOverview(motion)
        FrameRecorder(compose).use { recorder ->
            recorder.frame()
            compose.change { mode = PercentDisplay.USED }
            val first = recorder.next(1).single()
            compose.mainClock.advanceTimeBy(1_000)

            assertTrue("the first frame after the flip is the settled one", first.sameAs(recorder.frame()))
            assertEquals(0, rowText("60%").size)
            assertEquals(1, rowText("40%").size)
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

    @Test
    fun `a colour change updates the same figure without a fade`() {
        compose.mainClock.autoAdvance = false
        var color by mutableStateOf(UsageColors.Ink)
        compose.setContent {
            UsageLimitsTheme {
                CompositionLocalProvider(LocalMotionEnabled provides true) {
                    val style = MaterialTheme.typography.bodyLarge
                    FigureWithSide("60%", "left", color, style, style, 4.dp)
                }
            }
        }
        val figure = text("60%").single().boundsInWindow.toRegion(margin = 0)
        FrameRecorder(compose).use { recorder ->
            val before = recorder.frame()
            compose.change { color = UsageColors.Muted }
            val frames = recorder.next(20)
            val settled = recorder.frame()
            fun Bitmap.matches(other: Bitmap) = sameAs(other, figure.left, figure.top, figure.right, figure.bottom)
            assertTrue("the figure actually changes colour", !before.matches(settled))
            assertTrue("the same figure stays drawn in its new colour", frames.all { it.matches(settled) })
        }
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
    fun `a reading that becomes known replaces the dash at once`() {
        accounts = listOf(demoAccount("one", ProviderId.CODEX, null))
        setCard(motion = true)
        assertEquals(1, rowText("—").size)

        compose.change { accounts = listOf(demoAccount("one", ProviderId.CODEX, 40.0)) }
        compose.mainClock.advanceTimeByFrame()

        assertEquals(listOf("60%"), listOf("—", "60%").filter { rowText(it).isNotEmpty() })
    }
}
