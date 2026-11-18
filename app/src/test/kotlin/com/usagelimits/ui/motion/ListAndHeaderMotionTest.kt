package com.usagelimits.ui.motion

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.usagelimits.core.model.ProviderId
import com.usagelimits.feature.UsageUiState
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

/** The Overview list and its header: neighbours of a changing item move, they do not jump (#81). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListAndHeaderMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(UsageUiState(accounts = listOf(demoAccount("one", ProviderId.CODEX))))

    private fun setOverview(motion: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme {
                CompositionLocalProvider(LocalMotionEnabled provides motion) {
                    OverviewScreen(state = state, nowMs = MOTION_NOW, onRefresh = {}, onAccountClick = {},
                        onAddAccount = {}, onReorder = {}, onPercentDisplayChange = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun addButtonTop() = compose.onNodeWithText("Add account").fetchSemanticsNode().boundsInWindow.top

    private fun addSecondAccount() = compose.change {
        state = state.copy(accounts = state.accounts + demoAccount("two", ProviderId.CLAUDE))
    }

    @Test
    fun `the add button glides down when an account arrives`() {
        setOverview(motion = true)
        val start = addButtonTop()

        val positions = FrameRecorder(compose).use { recorder ->
            val frames = mutableListOf(recorder.frame())
            addSecondAccount()
            List(30) {
                frames += recorder.next(1)
                addButtonTop()
            }.also { frames.saveSequence("overview-account-arrives") }
        }

        assertTrue("the button ends lower than it started ($start to ${positions.last()})", positions.last() > start)
        assertTrue(
            "the button passes through places in between instead of jumping: $positions",
            positions.filter { it > start && it < positions.last() }.distinct().size >= 4,
        )
    }

    @Test
    fun `with motion off the add button is in its new place on the next frame`() {
        setOverview(motion = false)
        val start = addButtonTop()

        addSecondAccount()
        compose.mainClock.advanceTimeByFrame()
        val first = addButtonTop()
        compose.mainClock.advanceTimeBy(1_000)

        assertTrue(first > start)
        assertEquals(addButtonTop(), first)
    }

    // Content arriving for the first time is not a list change: the CI emulator showed the list
    // blank for the fade-in's delay after the system splash screen had gone.
    @Test
    fun `the first load replaces the skeleton in one cut`() {
        state = UsageUiState(loaded = false)
        setOverview(motion = true)

        FrameRecorder(compose).use { recorder ->
            recorder.frame()
            compose.change { state = UsageUiState(accounts = listOf(demoAccount("one", ProviderId.CODEX))) }
            val first = recorder.next(1).single()
            compose.mainClock.advanceTimeBy(1_000)

            assertTrue("the frame after the load is already the settled screen", first.sameAs(recorder.frame()))
        }
    }

    @Test
    fun `the refresh icon fades into the spinner instead of cutting`() {
        setOverview(motion = true)
        fun icon() = compose.onAllNodesWithContentDescription("Refresh usage").fetchSemanticsNodes().size
        assertEquals(1, icon())

        compose.change { state = state.copy(isRefreshing = true) }
        compose.mainClock.advanceTimeBy(48)
        assertEquals("the outgoing icon is still composed mid-fade", 1, icon())

        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("the icon is gone once the fade has finished", 0, icon())
    }
}
