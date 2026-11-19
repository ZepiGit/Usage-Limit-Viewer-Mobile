package com.usagelimits.navigation

import android.graphics.Bitmap
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.usagelimits.ui.NavigationLayout
import com.usagelimits.ui.motion.FrameRecorder
import com.usagelimits.ui.motion.Region
import com.usagelimits.ui.motion.argb
import com.usagelimits.ui.motion.change
import com.usagelimits.ui.motion.sameAs
import com.usagelimits.ui.motion.saveSequence
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The real navigation shell (chrome and route motion) around stand-in screens (#81): what the
 * tabs do while they fade out, a push or a pop that is interrupted, a window that changes width,
 * and the predictive back gesture, each with motion on and off.
 *
 * "How much of the bar is on screen" is measured, not looked up: the sum of how far each pixel
 * of the bar's strip is from the ground colour, as a share of the bar at rest. It falls smoothly
 * while the bar fades and slides, and drops in one step when the bar is cut.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h700dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationShellTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private lateinit var focus: FocusManager
    private var layout by mutableStateOf(NavigationLayout.BOTTOM_BAR)
    private var motionOn by mutableStateOf(true)
    private val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val pushed = Destination.AddAccount.route

    /** Each stand-in screen writes its name on a row of its own, so two screens can be told apart while both are drawn. */
    @Composable
    private fun Screen(name: String, row: Int) {
        Box(Modifier.fillMaxSize().padding(start = 16.dp, top = (16 + 40 * row).dp)) { Text(name, color = UsageColors.Ink) }
    }

    private fun setShell(motion: Boolean) {
        compose.mainClock.autoAdvance = false
        motionOn = motion
        compose.setContent {
            UsageLimitsTheme {
                CompositionLocalProvider(LocalMotionEnabled provides motionOn) {
                    nav = rememberNavController()
                    focus = LocalFocusManager.current
                    Box(Modifier.fillMaxSize().background(UsageColors.Ground)) {
                        NavigationShell(nav, layout) {
                            composable(Destination.Overview.route) { Screen("Overview screen", 0) }
                            composable(Destination.Accounts.route) { Screen("Accounts screen", 1) }
                            composable(Destination.Resets.route) { Screen("Resets screen", 2) }
                            composable(Destination.Settings.route) { Screen("Settings screen", 3) }
                            composable(pushed) { Screen("Pushed screen", 4) }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun push() = compose.change { nav.navigate(pushed) }
    private fun back() = compose.change { nav.popBackStack() }
    private fun settle() = compose.mainClock.advanceTimeBy(1_000)
    private val route get() = nav.currentDestination?.route
    private fun tabs() = compose.onAllNodes(tab).fetchSemanticsNodes()
    private fun left(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().single().boundsInWindow.left

    /** The strip the bottom bar occupies, taken from a tab while the bar is at rest. */
    private fun barStrip(frame: Bitmap): Region {
        val top = tabs().minOf { it.boundsInWindow.top }.roundToInt() - 4
        return Region(0, top, frame.width, frame.height)
    }

    private fun Bitmap.weight(region: Region): Long {
        val ground = UsageColors.Ground.argb
        var sum = 0L
        for (y in region.top until region.bottom) for (x in region.left until region.right) {
            val pixel = getPixel(x, y)
            sum += abs((pixel shr 16 and 0xFF) - (ground shr 16 and 0xFF)) + abs((pixel shr 8 and 0xFF) - (ground shr 8 and 0xFF)) +
                abs((pixel and 0xFF) - (ground and 0xFF))
        }
        return sum
    }

    /** The largest change of a share between two consecutive frames. */
    private fun largestStep(shares: List<Double>) = shares.zipWithNext { a, b -> abs(b - a) }.max()

    /** The band a stand-in screen's name is written in, wide enough for the name while it shifts. */
    private fun nameBand(row: Int, frame: Bitmap): Region {
        val top = ((16 + 40 * row) * compose.density.density).roundToInt()
        return Region(0, top - 4, frame.width, top + (30 * compose.density.density).roundToInt())
    }

    /**
     * Frame by frame, how much of the bar and of each of the two screens is on screen, as shares
     * of each at rest. A hard cut, or content that is thrown back to full before it leaves, is a
     * large step from one frame to the next.
     */
    private class Shares(val bar: List<Double>, val overview: List<Double>, val pushed: List<Double>) {
        override fun toString() = "bar ${bar.percent()}, Overview ${overview.percent()}, pushed ${pushed.percent()}"
        private fun List<Double>.percent() = map { (it * 100).roundToInt() }
    }

    private fun shares(frames: List<Bitmap>, onOverview: Bitmap, onPushed: Bitmap, strip: Region): Shares {
        val overview = nameBand(0, onOverview)
        val pushed = nameBand(4, onPushed)
        return Shares(
            frames.map { it.weight(strip) / onOverview.weight(strip).toDouble() },
            frames.map { it.weight(overview) / onOverview.weight(overview).toDouble() },
            frames.map { it.weight(pushed) / onPushed.weight(pushed).toDouble() },
        )
    }

    private fun assertNoStep(shares: Shares) {
        assertTrue("the bar never jumps: the largest step is ${largestStep(shares.bar)} in $shares", largestStep(shares.bar) < MAX_STEP)
        for ((what, series) in listOf("Overview" to shares.overview, "the pushed screen" to shares.pushed)) {
            assertTrue("$what never jumps: the largest step is ${largestStep(series)} in $shares", largestStep(series) < MAX_SCREEN_STEP)
        }
    }

    /** With motion off nothing is ever part-way: the bar and each screen are there or they are not. */
    private fun assertNothingPartWay(shares: Shares) {
        for (share in shares.bar + shares.overview + shares.pushed) {
            assertTrue("nothing is part-way in any frame: $shares", share < 0.001 || abs(share - 1) < 0.001)
        }
    }

    @Test
    fun `the bar takes input only when usable`() = assertUsableChrome(NavigationLayout.BOTTOM_BAR, motion = true)

    @Test
    fun `the rail takes input only when usable`() = assertUsableChrome(NavigationLayout.SIDE_RAIL, motion = true)

    @Test
    fun `reduced motion removes the focused bar on the first exit frame`() =
        assertUsableChrome(NavigationLayout.BOTTOM_BAR, motion = false)

    @Test
    fun `reduced motion removes the focused rail on the first exit frame`() =
        assertUsableChrome(NavigationLayout.SIDE_RAIL, motion = false)

    @OptIn(ExperimentalTestApi::class)
    private fun assertUsableChrome(edge: NavigationLayout, motion: Boolean) {
        layout = edge
        setShell(motion)
        val accounts = compose.onNode(hasText("Accounts") and tab)
        val root = compose.onRoot()
        root.performKeyInput { pressKey(Key.Tab) }
        accounts.requestFocus()
        compose.mainClock.advanceTimeByFrame()
        val focused = accounts.fetchSemanticsNode()
        assertEquals(true, focused.config[SemanticsProperties.Focused])
        val position = focused.boundsInWindow.center
        fun tapAtTab() = root.performTouchInput { click(Offset(position.x, position.y)) }
        fun assertInert(phase: String) {
            assertEquals("accessible tabs on $phase", 0, tabs().size)
            val focused = SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)
            assertEquals("retained focus on $phase", 0, compose.onAllNodes(focused, useUnmergedTree = true).fetchSemanticsNodes().size)
            assertFalse("new focus on $phase", compose.runOnUiThread<Boolean> { focus.moveFocus(FocusDirection.Next) })
        }

        push()
        compose.mainClock.advanceTimeByFrame()
        assertInert("the first exit frame")
        root.performKeyInput { pressKey(Key.Enter) }
        tapAtTab()
        settle()
        assertEquals(pushed, route)

        back()
        compose.mainClock.advanceTimeByFrame()
        if (motion) {
            assertInert("the first return frame")
            tapAtTab()
            repeat(12) { compose.mainClock.advanceTimeByFrame() }
            assertInert("before the return finishes")
            settle()
            assertEquals(Destination.Overview.route, route)
        }
        assertEquals("tabs are usable on return", 4, tabs().size)
        tapAtTab()
        settle()
        assertEquals(Destination.Accounts.route, route)
    }

    // MARK: - Interrupted

    /** Overview at rest and the pushed screen at rest, then back on Overview. */
    private fun bothAtRest(recorder: FrameRecorder): Pair<Bitmap, Bitmap> {
        val onOverview = recorder.frame()
        push()
        settle()
        val onPushed = recorder.frame()
        back()
        settle()
        return onOverview to onPushed
    }

    @Test
    fun `back straight after a push reverses the bar and both screens from where they are`() {
        setShell(motion = true)
        FrameRecorder(compose).use { recorder ->
            val (onOverview, onPushed) = bothAtRest(recorder)
            val strip = barStrip(onOverview)
            push()
            // Into the new screen's entrance, so that what is interrupted is an entrance as well as an exit.
            val out = recorder.next(9)
            back()
            val frames = listOf(onOverview) + out + recorder.next(40)
            frames.saveSequence("shell-push-then-back")
            val shares = shares(frames, onOverview, onPushed, strip)

            assertTrue("the push was under way: $shares", shares.bar[9] < 0.5 && shares.pushed[9] > 0.05)
            assertNoStep(shares)
            assertTrue("and Overview is back, whole", frames.last().sameAs(onOverview))
            assertEquals(Destination.Overview.route, route)
            assertEquals("the tabs work again", 4, tabs().size)
            compose.onNode(hasText("Accounts") and tab).performClick()
            settle()
            assertEquals(Destination.Accounts.route, route)
        }
    }

    @Test
    fun `a push straight after back reverses the bar and both screens from where they are`() {
        setShell(motion = true)
        FrameRecorder(compose).use { recorder ->
            val (onOverview, onPushed) = bothAtRest(recorder)
            val strip = barStrip(onOverview)
            push()
            settle()

            back()
            val returning = mutableListOf<Bitmap>()
            // Until the bar is half-way back and still arriving: a step from there is unmistakable.
            while (returning.size < 30 && (returning.lastOrNull()?.weight(strip) ?: 0L) < onOverview.weight(strip) * 0.5) returning += recorder.next(1)
            push()
            val frames = listOf(onPushed) + returning + recorder.next(40)
            frames.saveSequence("shell-back-then-push")
            val shares = shares(frames, onOverview, onPushed, strip)

            assertTrue("the bar was half-way back: $shares", shares.bar[returning.size] >= 0.5)
            assertNoStep(shares)
            assertTrue("and the pushed screen is back, whole, with no bar stuck", frames.last().sameAs(onPushed))
            assertEquals(pushed, route)
            assertEquals(0, tabs().size)
        }
    }

    /**
     * With motion off the chrome changes immediately when the route changes. The NavHost
     * settles later, but neither the chrome nor the screens draw an intermediate animation.
     */
    @Test
    fun `with motion off a push and a back are cuts with nothing part-way`() {
        setShell(motion = false)
        FrameRecorder(compose).use { recorder ->
            val rest = recorder.frame()
            val strip = barStrip(rest)
            push()
            val pushing = recorder.next(4)
            settle()
            val onPushed = recorder.frame()
            pushing.saveSequence("shell-push-reduced")
            assertNothingPartWay(shares(pushing, rest, onPushed, strip))
            assertTrue("pushed: settled by the third frame", pushing[2].sameAs(onPushed))
            assertEquals(0L, onPushed.weight(strip))

            back()
            val returning = recorder.next(4)
            settle()
            returning.saveSequence("shell-back-reduced")
            assertNothingPartWay(shares(returning, rest, onPushed, strip))
            assertTrue("back: settled by the third frame", returning[2].sameAs(rest))
            assertEquals(4, tabs().size)
        }
    }

    @Test
    fun `animations switched off while the bar leaves take it away on the next frame`() {
        setShell(motion = true)
        FrameRecorder(compose).use { recorder ->
            val rest = recorder.frame()
            val strip = barStrip(rest)
            push()
            val leaving = recorder.next(3).last()
            assertTrue("the bar is on its way out", leaving.weight(strip) in 1 until rest.weight(strip))

            compose.change { motionOn = false }
            assertEquals("gone, not faded further", 0L, recorder.next(1).single().weight(strip))
            settle()
            assertEquals(pushed, route)
            back()
            settle()
            assertTrue("and it comes back whole", recorder.frame().sameAs(rest))
        }
    }

    // MARK: - A window that changes width

    private fun assertWidthChangeSwapsTheChromeAtOnce(motion: Boolean) {
        setShell(motion)
        FrameRecorder(compose).use { recorder ->
            val bar = recorder.frame()
            compose.change { layout = NavigationLayout.SIDE_RAIL }
            val first = recorder.next(1).single()
            settle()
            val rail = recorder.frame()
            assertTrue("bar to rail: the first frame is the settled one", first.sameAs(rail))
            assertFalse("and it is not the bar's", rail.sameAs(bar))
            compose.onNode(hasText("Resets") and tab).performClick()
            settle()
            assertEquals("the rail's tabs work", Destination.Resets.route, route)

            val onResets = recorder.frame()
            compose.change { layout = NavigationLayout.BOTTOM_BAR }
            val back = recorder.next(1).single()
            settle()
            assertTrue("rail to bar: the first frame is the settled one", back.sameAs(recorder.frame()))
            assertFalse(back.sameAs(onResets))
            compose.onNode(hasText("Overview") and tab).performClick()
            settle()
            assertEquals("the bar's tabs work", Destination.Overview.route, route)
        }
    }

    @Test
    fun `a window that changes width swaps bar and rail at once`() = assertWidthChangeSwapsTheChromeAtOnce(motion = true)

    @Test
    fun `with motion off a window that changes width swaps bar and rail at once`() = assertWidthChangeSwapsTheChromeAtOnce(motion = false)

    // MARK: - Predictive back

    private val dispatcher get() = compose.activity.onBackPressedDispatcher

    /** A back swipe that started at [edge] and has come [progress] of its way. */
    private fun gesture(edge: Int, progress: Float): BackEventCompat {
        val width = compose.activity.window.decorView.width.toFloat()
        val travelled = 200f * progress
        return BackEventCompat(if (edge == BackEventCompat.EDGE_LEFT) travelled else width - travelled, 300f, progress, edge)
    }

    private fun startGesture(edge: Int, progress: Float) = compose.change {
        dispatcher.dispatchOnBackStarted(gesture(edge, 0f))
        dispatcher.dispatchOnBackProgressed(gesture(edge, progress))
    }

    private fun edgeName(edge: Int) = if (edge == BackEventCompat.EDGE_LEFT) "left" else "right"

    @Test
    fun `a back gesture from the left edge seeks the app's own motion and can be cancelled or completed`() =
        assertGestureSeeksTheAppsMotion(BackEventCompat.EDGE_LEFT)

    @Test
    fun `a back gesture from the right edge seeks the app's own motion and can be cancelled or completed`() =
        assertGestureSeeksTheAppsMotion(BackEventCompat.EDGE_RIGHT)

    private fun assertGestureSeeksTheAppsMotion(edge: Int) {
        setShell(motion = true)
        val overviewRest = left("Overview screen")
        FrameRecorder(compose).use { recorder ->
            val rest = recorder.frame()
            val strip = barStrip(rest)
            push()
            settle()
            val pushedRest = left("Pushed screen")
            val onPushed = recorder.frame()

            startGesture(edge, progress = 0.5f)
            val held = recorder.next(3)
            held.saveSequence("shell-predictive-back-held-${edgeName(edge)}")
            // Back is back whichever edge the finger came from: the screen below waits shifted
            // towards the start, the pushed one leaves towards the end. (The library's default
            // scales the pushed screen down instead.)
            val arriving = left("Overview screen")
            assertTrue("the screen below is shifted towards the start: $arriving against $overviewRest at rest",
                arriving < overviewRest && arriving > overviewRest - SHIFT_PX)
            val heldShares = shares(held, rest, onPushed, strip)
            assertEquals("the screen below is there in full while the finger holds", 1.0, heldShares.overview.last(), 0.02)
            assertTrue("and the one on top is part-way out, not gone: $heldShares", heldShares.pushed.last() in 0.2..0.8)
            assertEquals("the route does not change until the gesture is completed", pushed, route)
            assertEquals("the bar waits for the gesture to complete", 0L, held.last().weight(strip))

            compose.change { dispatcher.dispatchOnBackCancelled() }
            settle()
            assertEquals("cancelled: still on the pushed screen", pushed, route)
            assertEquals(pushedRest, left("Pushed screen"))
            assertTrue("and the picture is the pushed screen at rest", recorder.frame().sameAs(onPushed))

            startGesture(edge, progress = 0.5f)
            recorder.next(2)
            compose.change { dispatcher.onBackPressed() }
            val completing = recorder.next(40)
            completing.saveSequence("shell-predictive-back-completed-${edgeName(edge)}")
            val shares = shares(listOf(held.last()) + completing, rest, onPushed, strip)
            assertEquals("completed: on Overview", Destination.Overview.route, route)
            assertNoStep(shares)
            assertTrue("and the picture is Overview at rest", completing.last().sameAs(rest))
        }
    }

    @Test
    fun `with motion off a back gesture from the left edge moves nothing`() = assertGestureMovesNothing(BackEventCompat.EDGE_LEFT)

    @Test
    fun `with motion off a back gesture from the right edge moves nothing`() = assertGestureMovesNothing(BackEventCompat.EDGE_RIGHT)

    private fun assertGestureMovesNothing(edge: Int) {
        setShell(motion = false)
        FrameRecorder(compose).use { recorder ->
            val rest = recorder.frame()
            val strip = barStrip(rest)
            push()
            settle()
            val onPushed = recorder.frame()

            fun holdAtEnd(): List<Bitmap> {
                startGesture(edge, progress = 0.3f)
                val held = recorder.next(2)
                compose.change { dispatcher.dispatchOnBackProgressed(gesture(edge, 1f)) }
                val end = recorder.next(3)
                assertTrue("even at 100%, every held frame is the pushed screen", (held + end).all { it.sameAs(onPushed) })
                assertTrue("the preview never enters the semantics tree", compose.onAllNodesWithText("Overview screen").fetchSemanticsNodes().isEmpty())
                assertEquals(pushed, route)
                return held + end
            }

            holdAtEnd().saveSequence("shell-predictive-back-held-reduced-${edgeName(edge)}")
            compose.change { dispatcher.dispatchOnBackCancelled() }
            assertTrue("cancel leaves the pushed screen untouched", recorder.next(3).all { it.sameAs(onPushed) })
            assertEquals(pushed, route)

            holdAtEnd()
            compose.change { dispatcher.onBackPressed() }
            val completing = recorder.next(4)
            assertNothingPartWay(shares(completing, rest, onPushed, strip))
            assertTrue("commit ends on Overview at rest", completing.last().sameAs(rest))
            assertEquals(Destination.Overview.route, route)
        }
    }

    private companion object {
        /**
         * The fastest the bar may change between two frames, as a share of the whole bar. Its
         * quickest part is the 90 ms fade-out: 16 of 90 ms is 18 % a frame; a cut is the whole
         * remaining bar in one frame.
         */
        const val MAX_STEP = 0.35

        /**
         * The same for a screen. The navigation library answers an interrupted screen transition
         * with a spring of its own, which no spec of the app replaces: measured, a screen that
         * comes back after it had faded out regains 37 % in its first frame and is whole after
         * five. Fast, but a curve; a cut is the whole screen in one frame.
         */
        const val MAX_SCREEN_STEP = 0.5

        /** AxisShift (24dp) at this test's density of 2. */
        const val SHIFT_PX = 48
    }
}
