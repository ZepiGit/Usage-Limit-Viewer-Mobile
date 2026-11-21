package com.usagelimits.ui.motion

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.ClaudeCodeMood
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.OverviewLayout
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.feature.settings.SettingsScreen
import com.usagelimits.ui.FigureSlot
import com.usagelimits.ui.ProviderLogo
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
 * What a moving Claude Code figure costs the screen around it, over one second of test-clock time
 * while the figure visibly animates.
 *
 * Recompositions are counted by every running recomposer, so one anywhere in the window counts:
 * the List row, the dial and the Settings provider card must see none. Layout is counted on the
 * figure's own node, which the hosts place with a fixed size: a node whose size does not change is
 * remeasured and placed again on its own, without its parent, so the row, the dial and the card can
 * only be laid out again through a recomposition or through the figure, and neither happens.
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

    private fun show(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme(providerIcons = mapOf(ProviderId.CLAUDE.id to "claudecode-color")) { content() }
        }
    }

    /** Steps one second after a second to settle; returns how many of its frames differ from the one before. */
    private fun animatedSecond(recorder: FrameRecorder, during: () -> Unit = {}): Int {
        recorder.next(60).forEach { it.recycle() }
        during()
        var previous = recorder.frame()
        var changed = 0
        repeat(60) {
            val frame = recorder.next(1).single()
            if (!frame.sameAs(previous)) changed++
            previous.recycle()
            previous = frame
        }
        previous.recycle()
        return changed
    }

    private fun assertNoRecomposition(host: String, content: @Composable () -> Unit) {
        show(content)
        FrameRecorder(compose).use { recorder ->
            var before = 0L
            val changed = animatedSecond(recorder) { before = recompositions() }
            assertTrue("$host: the figure animates ($changed changed frames)", changed >= 10)
            assertEquals("$host: recompositions while the figure animates", 0L, recompositions() - before)
        }
    }

    @Test
    fun `the List row is not recomposed`() = assertNoRecomposition("List row") {
        OverviewScreen(state = state, nowMs = MOTION_NOW, zone = ZoneId.of("Etc/UTC"), onRefresh = {},
            onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {})
    }

    @Test
    fun `the dial is not recomposed`() = assertNoRecomposition("dial") {
        OverviewScreen(state = state.copy(settings = AppSettings(overviewLayout = OverviewLayout.DIALS)), nowMs = MOTION_NOW,
            zone = ZoneId.of("Etc/UTC"), onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {})
    }

    // Settings opens with Appearance (themes and fonts); a window this tall holds the provider card
    // without scrolling, so the figure starts on the held test clock.
    @Config(qualifiers = "w393dp-h2400dp-xhdpi")
    @Test
    fun `the Settings provider card is not recomposed`() = assertNoRecomposition("Settings card") {
        SettingsScreen(state = state, onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {},
            onNotifyExhausted = {}, onNotifyResetCredit = {}, onNotifyAuthExpired = {}, onNotifyResetApproaching = {},
            onNotifyCreditExpiring = {}, onShowTier = {}, onOverviewLayoutChange = {}, onPercentDisplayChange = {},
            onNotificationPermissionHandled = {}, nowMs = MOTION_NOW)
    }

    @Test
    fun `the figure's own node is neither measured nor placed while it moves`() {
        var measures = 0
        var placements = 0
        show {
            // The slots call the same composable with a fixed size; the counter sits outermost on its node.
            Box(Modifier.padding(60.dp)) {
                ProviderLogo(ProviderId.CLAUDE, Modifier
                    .layout { measurable, constraints ->
                        measures++
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { placements++; placeable.place(0, 0) }
                    }
                    .size(28.dp), FigureSlot.LIST_ROW, ClaudeCodeMood.DANCING)
            }
        }
        FrameRecorder(compose).use { recorder ->
            var measuredBefore = 0
            var placedBefore = 0
            val changed = animatedSecond(recorder) { measuredBefore = measures; placedBefore = placements }
            assertTrue("the figure animates ($changed changed frames)", changed >= 10)
            assertEquals("measure passes of the figure", 0, measures - measuredBefore)
            assertEquals("placements of the figure", 0, placements - placedBefore)
        }
    }
}
