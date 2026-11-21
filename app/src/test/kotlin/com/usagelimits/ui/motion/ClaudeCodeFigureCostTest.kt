package com.usagelimits.ui.motion

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.OverviewLayout
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.feature.settings.SettingsScreen
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * What a moving Claude Code figure costs the screen around it: for one second of test-clock time,
 * while the figure visibly animates, the List row, the dial and the Settings provider card are
 * neither recomposed nor measured nor laid out again. The figure's time is read only while
 * drawing, so its frames reach the draw phase and nothing earlier.
 *
 * Recompositions are counted by every running recomposer, so a recomposition anywhere in the
 * window counts. A remeasure or relayout request stays pending in the window's root until the
 * next draw; it is checked after every frame's clock step and before that frame is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClaudeCodeFigureCostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // 95 % left: Dancing, the mood with the most parts in motion.
    private val claude = demoAccount("claude", ProviderId.CLAUDE, usedPercent = 5.0)
    private val state = UsageUiState(accounts = listOf(claude))

    private fun recompositions() = Recomposer.runningRecomposers.value.sumOf { it.changeCount }

    private fun View.composeRoot(): ViewRootForTest? = this as? ViewRootForTest
        ?: (this as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { group.getChildAt(it).composeRoot() } }

    private fun assertNoCostWhileAnimating(host: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme(providerIcons = mapOf(ProviderId.CLAUDE.id to "claudecode-color")) { content() }
        }
        val root = requireNotNull(compose.activity.window.decorView.composeRoot())
        FrameRecorder(compose).use { recorder ->
            // A second to settle: the first composition's own motion (bar fills, figures) ends.
            recorder.next(60).forEach { it.recycle() }
            val before = recompositions()
            var pendingLayouts = 0
            var previous = recorder.frame()
            var changedFrames = 0
            repeat(60) {
                compose.mainClock.advanceTimeByFrame()
                if (root.hasPendingMeasureOrLayout) pendingLayouts++
                val frame = recorder.frame()
                if (!frame.sameAs(previous)) changedFrames++
                previous.recycle()
                previous = frame
            }
            previous.recycle()
            assertTrue("$host: the figure animates ($changedFrames changed frames)", changedFrames >= 10)
            assertEquals("$host: recompositions while the figure animates", 0L, recompositions() - before)
            assertEquals("$host: frames that asked for a measure or layout", 0, pendingLayouts)
        }
    }

    @Test
    fun `the List row`() = assertNoCostWhileAnimating("List row") {
        OverviewScreen(state = state, nowMs = MOTION_NOW, zone = ZoneId.of("Etc/UTC"), onRefresh = {},
            onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {})
    }

    @Test
    fun `the dial`() = assertNoCostWhileAnimating("dial") {
        OverviewScreen(state = state.copy(settings = AppSettings(overviewLayout = OverviewLayout.DIALS)), nowMs = MOTION_NOW,
            zone = ZoneId.of("Etc/UTC"), onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {})
    }

    @Test
    fun `the Settings provider card`() = assertNoCostWhileAnimating("Settings card") {
        SettingsScreen(state = state, onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {},
            onNotifyExhausted = {}, onNotifyResetCredit = {}, onNotifyAuthExpired = {}, onNotifyResetApproaching = {},
            onNotifyCreditExpiring = {}, onShowTier = {}, onOverviewLayoutChange = {}, onPercentDisplayChange = {},
            onNotificationPermissionHandled = {}, nowMs = MOTION_NOW)
    }
}
