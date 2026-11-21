package com.usagelimits.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.usagelimits.core.model.ClaudeCodeMood
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.feature.settings.SettingsScreen
import com.usagelimits.ui.motion.FrameRecorder
import com.usagelimits.ui.motion.MOTION_NOW
import com.usagelimits.ui.motion.change
import com.usagelimits.ui.motion.crop
import com.usagelimits.ui.motion.demoAccount
import com.usagelimits.ui.motion.sameAs
import com.usagelimits.ui.motion.toRegion
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
import java.time.ZoneId

/**
 * The Claude Code figure as a person meets it: which icon choices show it, which account speaks
 * for it, what it says to a screen reader, and the still drawing a placed widget will use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClaudeCodeFigureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ink = 0xFF112233.toInt()
    private val sweat = 0xFF9CC3E6.toInt()
    private val spark = 0xFFE6C24A.toInt()

    /** A pixel of [bitmap] at grid point [x],[y] of a figure [size] px wide drawn at its top left. */
    private fun Bitmap.atGrid(x: Float, y: Float, size: Int) = getPixel((x / 24f * size).toInt(), (y / 24f * size).toInt())

    private fun Bitmap.count(color: Int): Int {
        var n = 0
        for (y in 0 until height) for (x in 0 until width) if (getPixel(x, y) == color) n++
        return n
    }

    @Test
    fun `the widget still is drawn in the given ink with its eyes cut out`() {
        val size = 96
        val idle = claudeCodeStillBitmap(ClaudeCodeMood.IDLE, ink, size)
        assertEquals("body", ink, idle.atGrid(12f, 14f, size))
        assertEquals("left eye is transparent", 0, idle.atGrid(6.74f, 9.5f, size) ushr 24)
        assertEquals("right eye is transparent", 0, idle.atGrid(17.25f, 9.5f, size) ushr 24)
        assertEquals("ground outside the figure is transparent", 0, idle.atGrid(1f, 1f, size) ushr 24)

        val asleep = claudeCodeStillBitmap(ClaudeCodeMood.ASLEEP, ink, size)
        assertEquals("asleep: line eyes are cut out", 0, asleep.atGrid(6.7f, 10.2f, size) ushr 24)
        assertEquals("asleep: below the line eye is body", ink, asleep.atGrid(6.7f, 11.2f, size))
        assertTrue("asleep: the first z shows", asleep.count(0xFFB4B2A9.toInt()) > 0)

        val tired = claudeCodeStillBitmap(ClaudeCodeMood.TIRED, ink, size)
        assertTrue("tired: the sweat drop shows", tired.count(sweat) > 0)

        val hooray = claudeCodeStillBitmap(ClaudeCodeMood.HOORAY, ink, size)
        assertEquals("hooray: an arm at rest height is gone", 0, hooray.atGrid(1.5f, 13.5f, size) ushr 24)
        assertEquals("hooray: the arm is raised by one and a half of its height", ink, hooray.atGrid(1.5f, 7.85f, size))
        assertTrue("hooray: the sparkles show", hooray.count(spark) > 0)

        val working = claudeCodeStillBitmap(ClaudeCodeMood.WORKING, ink, size)
        assertEquals("working: arms at rest", ink, working.atGrid(1.5f, 12.5f, size))
        assertEquals("working: the eyes sit a quarter lower", 0, working.atGrid(6.74f, 10.8f, size) ushr 24)
        assertEquals("working: above the lowered eye is body", ink, working.atGrid(6.74f, 8.3f, size))
    }

    private var mode by mutableStateOf(PercentDisplay.LEFT)
    private var icon by mutableStateOf("claudecode-color")

    @Test
    fun `Used shows the same figure as Left`() {
        // 15 % left is Tired; read from the Used figure (85 %) it would be Idle.
        val state = UsageUiState(accounts = listOf(demoAccount("claude", ProviderId.CLAUDE, usedPercent = 85.0)))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme(providerIcons = mapOf(ProviderId.CLAUDE.id to "claudecode-color")) {
                CompositionLocalProvider(LocalMotionEnabled provides false) {
                    OverviewScreen(state = state.copy(settings = AppSettings(percentDisplay = mode)), nowMs = MOTION_NOW,
                        zone = ZoneId.of("Etc/UTC"), onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {})
                }
            }
        }
        FrameRecorder(compose).use { recorder ->
            fun figure(): Bitmap {
                recorder.next(20)
                val bounds = compose.onNodeWithContentDescription("Claude", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                return recorder.frame().crop(bounds.toRegion(margin = 0))
            }
            val left = figure()
            assertTrue("the Tired still shows its sweat drop", left.count(sweat) > 0)
            compose.change { mode = PercentDisplay.USED }
            assertTrue("the same pixels in Used", left.sameAs(figure()))
        }
    }

    @Test
    fun `the figure says what the static logo said`() {
        val state = UsageUiState(accounts = listOf(demoAccount("claude", ProviderId.CLAUDE)))
        compose.setContent {
            UsageLimitsTheme(providerIcons = mapOf(ProviderId.CLAUDE.id to icon)) {
                OverviewScreen(state = state, nowMs = MOTION_NOW, zone = ZoneId.of("Etc/UTC"), onRefresh = {},
                    onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {})
            }
        }
        fun logo() = compose.onNodeWithContentDescription("Claude", useUnmergedTree = true).fetchSemanticsNode().config
        val figure = logo()
        compose.runOnIdle { icon = "claude-color" }
        val static = logo()
        assertEquals(static.getOrNull(SemanticsProperties.ContentDescription), figure.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(Role.Image, static.getOrNull(SemanticsProperties.Role))
        assertEquals(Role.Image, figure.getOrNull(SemanticsProperties.Role))
    }

    @Test
    fun `only the two Claude Code icons get the animation switch`() {
        var changedTo: Boolean? = null
        compose.setContent {
            UsageLimitsTheme(providerIcons = mapOf(ProviderId.CLAUDE.id to icon)) {
                SettingsScreen(state = UsageUiState(settings = AppSettings(providerIcons = mapOf(ProviderId.CLAUDE.id to icon))),
                    onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {}, onNotifyExhausted = {},
                    onNotifyResetCredit = {}, onNotifyAuthExpired = {}, onNotifyResetApproaching = {}, onNotifyCreditExpiring = {},
                    onShowTier = {}, onOverviewLayoutChange = {}, onPercentDisplayChange = {}, onNotificationPermissionHandled = {},
                    onAnimateClaudeCodeIconChange = { changedTo = it }, nowMs = MOTION_NOW)
            }
        }
        val title = compose.activity.getString(com.usagelimits.R.string.settings_animate_claude_code_title)
        for (choice in ProviderIconCatalog.choices(ProviderId.CLAUDE)) {
            compose.runOnIdle { icon = choice.id }
            val shown = compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
            assertEquals(choice.id, choice.id == "claudecode-color" || choice.id == "claudecode", shown)
        }
        compose.runOnIdle { icon = "claudecode" }
        compose.onNodeWithContentDescription(title).performClick()
        assertEquals("the switch turns the stored choice off", false, changedTo)
    }

    @Test
    fun `Claude's Settings row follows the first Claude account in Overview order`() {
        // Added first but 95 % left (Dancing); the 15 %-left account (Tired) leads Overview.
        val dancing = demoAccount("claude-a", ProviderId.CLAUDE, usedPercent = 5.0)
        val tired = demoAccount("claude-b", ProviderId.CLAUDE, usedPercent = 85.0)
            .let { it.copy(account = it.account.copy(createdAt = 1L)) }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme(providerIcons = mapOf(ProviderId.CLAUDE.id to "claudecode-color")) {
                CompositionLocalProvider(LocalMotionEnabled provides false) {
                    SettingsScreen(state = UsageUiState(accounts = listOf(dancing, tired),
                        settings = AppSettings(providerIcons = mapOf(ProviderId.CLAUDE.id to "claudecode-color"))),
                        onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {}, onNotifyExhausted = {},
                        onNotifyResetCredit = {}, onNotifyAuthExpired = {}, onNotifyResetApproaching = {}, onNotifyCreditExpiring = {},
                        onShowTier = {}, onOverviewLayoutChange = {}, onPercentDisplayChange = {}, onNotificationPermissionHandled = {},
                        nowMs = MOTION_NOW)
                }
            }
        }
        FrameRecorder(compose).use { recorder ->
            recorder.next(20)
            val row = compose.onNode(hasClickAction() and hasText("Claude Code · Color")).fetchSemanticsNode().boundsInRoot
            val pixels = recorder.frame().crop(row.toRegion(margin = 0))
            assertTrue("Claude's row shows the Tired figure's sweat drop", pixels.count(sweat) > 0)
        }
    }
}
