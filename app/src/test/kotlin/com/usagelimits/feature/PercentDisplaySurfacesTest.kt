package com.usagelimits.feature

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.feature.accounts.AccountDetailScreen
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app screens in both percent-display modes (R1–R3).
 *
 * One window with 40 % used, so every assertion reads the same account: 60 % left in LEFT, 40 %
 * used in USED. The spoken sentences keep their historical LEFT wording ("remaining" on rows,
 * "left" on the lead strip) and only swap the percent clause in USED — the contract PLAN §2
 * pins for the LEFT-mode strings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PercentDisplaySurfacesTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_757_000_000_000L

    private fun usage(used: Double? = 40.0, exhausted: Boolean = false) = AccountUsage(
        account = ProviderAccount(
            localId = "a",
            provider = ProviderId.CODEX,
            externalAccountId = "ext-a",
            email = "someone@example.com",
            displayName = null,
            plan = "Plus",
            credentialReference = "ref-a",
            createdAt = 0L,
            lastSuccessfulSync = now,
        ),
        snapshot = UsageSnapshot(
            accountId = "a",
            fetchedAt = now,
            status = SnapshotStatus.OK,
            windows = listOf(
                UsageWindow(
                    id = "5h",
                    label = "5h limit",
                    category = WindowCategory.FIVE_HOUR,
                    usedPercent = used,
                    periodSeconds = 18_000,
                    resetAt = now + 3_600_000,
                    exhausted = exhausted,
                ),
            ),
        ),
    )

    private fun state(mode: PercentDisplay, used: Double? = 40.0, exhausted: Boolean = false) = UsageUiState(
        accounts = listOf(usage(used, exhausted)),
        settings = AppSettings(percentDisplay = mode),
    )

    private fun overview(
        mode: PercentDisplay,
        used: Double? = 40.0,
        exhausted: Boolean = false,
        onChange: (PercentDisplay) -> Unit = {},
    ) {
        compose.setContent {
            UsageLimitsTheme {
                OverviewScreen(
                    state = state(mode, used, exhausted),
                    nowMs = now,
                    onRefresh = {},
                    onAccountClick = {},
                    onAddAccount = {},
                    onReorder = {},
                    onPercentDisplayChange = onChange,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun spoken(description: String) =
        compose.onAllNodesWithContentDescription(description, substring = true).fetchSemanticsNodes()

    private fun text(value: String) = compose.onAllNodesWithText(value).fetchSemanticsNodes()

    /** The lead strip's merged node, found by the label its tap action carries. */
    private fun flipTo(label: String) = SemanticsMatcher("tap action \"$label\"") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    // MARK: - Overview in each mode

    @Test
    fun `LEFT mode keeps the historical wording and adds the visible left word`() {
        overview(PercentDisplay.LEFT)

        assertTrue("lead strip speaks 60% left", spoken("60% left").isNotEmpty())
        assertTrue("the row keeps its historical 'remaining' sentence", spoken("5h limit, 60% remaining").isNotEmpty())
        assertTrue("a visible 'left' word names the figure", text("left").isNotEmpty())
        assertTrue("no 'used' word in LEFT mode", text("used").isEmpty())
    }

    @Test
    fun `USED mode swaps the percent clause and the visible word`() {
        overview(PercentDisplay.USED)

        assertTrue("lead strip speaks 40% used", spoken("40% used").isNotEmpty())
        assertTrue("the row speaks 40% used", spoken("5h limit, 40% used").isNotEmpty())
        assertTrue("a visible 'used' word names the figure", text("used").isNotEmpty())
        assertTrue("no 'left' word in USED mode", text("left").isEmpty())
        assertTrue("no LEFT clause survives in USED", spoken("60% left").isEmpty() && spoken("60% remaining").isEmpty())
    }

    @Test
    fun `a blocked window reads 100 percent used in USED mode and keeps its remainder in LEFT`() {
        // Exhausted although 15% remains: the provider's verdict is the figure in USED mode.
        overview(PercentDisplay.USED, used = 85.0, exhausted = true)
        assertTrue(spoken("100% used").isNotEmpty())
        assertTrue("the remainder must not leak into USED", spoken("15%").isEmpty())
    }

    @Test
    fun `the same blocked window in LEFT mode still reports its remainder`() {
        overview(PercentDisplay.LEFT, used = 85.0, exhausted = true)
        assertTrue(spoken("5h limit, 15% remaining").isNotEmpty())
        assertTrue(spoken("15% left").isNotEmpty())
    }

    // MARK: - R3: tap to switch

    @Test
    fun `the lead strip is one focus stop holding its description, the flip action and the button role`() {
        overview(PercentDisplay.LEFT)

        val lead = compose.onNode(hasContentDescription("60% left", substring = true)).fetchSemanticsNode()

        assertEquals("Show percentage used", lead.config.getOrNull(SemanticsActions.OnClick)?.label)
        assertEquals(Role.Button, lead.config.getOrNull(SemanticsProperties.Role))
        compose.onAllNodes(flipTo("Show percentage used")).assertCountEquals(1)
    }

    @Test
    fun `invoking the lead strip action asks for USED from LEFT, with a label that names it`() {
        var chosen: PercentDisplay? = null
        overview(PercentDisplay.LEFT) { chosen = it }

        compose.onAllNodes(flipTo("Show percentage used")).assertCountEquals(1)
        compose.onNode(flipTo("Show percentage used")).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(PercentDisplay.USED, chosen)
    }

    @Test
    fun `invoking the lead strip action asks for LEFT from USED, with a label that names it`() {
        var chosen: PercentDisplay? = null
        overview(PercentDisplay.USED) { chosen = it }

        compose.onAllNodes(flipTo("Show percentage left")).assertCountEquals(1)
        compose.onNode(flipTo("Show percentage left")).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(PercentDisplay.LEFT, chosen)
    }

    @Test
    fun `a finger on the lead figure flips the mode`() {
        var chosen: PercentDisplay? = null
        overview(PercentDisplay.LEFT) { chosen = it }

        compose.onNode(
            hasText("60%") and hasAnyAncestor(hasContentDescription("60% left", substring = true)),
            useUnmergedTree = true,
        ).performTouchInput { click() }

        assertEquals(PercentDisplay.USED, chosen)
    }

    // MARK: - R3: reduced motion

    /** Nodes showing the LEFT figure ("60%") and the USED figure ("40%") once the flip has played. */
    private data class FigureNodes(val old: Int, val new: Int)

    /**
     * Counts the nodes still showing the old LEFT figure a moment after the mode flips. With the
     * clock held back, an animated change leaves the outgoing figure on screen mid-fade; with
     * animations off the figure is replaced at once.
     *
     * Once flipped, the row already reads "40%", so any "60%" left over can only be the lead
     * strip's outgoing figure. The new figure is counted too, so a flip that never reached the
     * screen cannot pass as "the old figure is gone".
     */
    private fun figureNodesAfterFlip(animationScale: Float, advanceMs: Long): FigureNodes {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, animationScale)
        var mode by mutableStateOf(PercentDisplay.LEFT)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme {
                // The state is rebuilt inside composition so the flip is actually observed;
                // a screen holding a snapshot of the old mode would never change.
                OverviewScreen(
                    state = UsageUiState(
                        accounts = listOf(usage()),
                        settings = AppSettings(percentDisplay = mode),
                    ),
                    nowMs = now,
                    onRefresh = {},
                    onAccountClick = {},
                    onAddAccount = {},
                    onReorder = {},
                    onPercentDisplayChange = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("the screen starts on the LEFT figure", text("60%").isNotEmpty() && text("40%").isEmpty())
        compose.runOnUiThread {
            mode = PercentDisplay.USED
            // With the clock held back no frame or looper turn delivers the write to the
            // composition, so without this the screen would never see the flip.
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(advanceMs)
        return FigureNodes(old = text("60%").size, new = text("40%").size)
    }

    @Test
    fun `with animations on the old figure fades out instead of vanishing`() {
        val nodes = figureNodesAfterFlip(animationScale = 1f, advanceMs = 32)
        assertTrue("the outgoing figure should still be composed mid-fade", nodes.old >= 1)
        assertTrue("the incoming figure is already on screen", nodes.new >= 1)
    }

    @Test
    fun `with animations on the old figure is gone once the fade has finished`() {
        val nodes = figureNodesAfterFlip(animationScale = 1f, advanceMs = 1_000)
        assertEquals(0, nodes.old)
        assertTrue("the new figure replaced it", nodes.new >= 1)
    }

    @Test
    fun `with animations off the figure changes at once`() {
        val nodes = figureNodesAfterFlip(animationScale = 0f, advanceMs = 32)
        assertEquals(0, nodes.old)
        assertTrue("the new figure replaced it", nodes.new >= 1)
    }

    // MARK: - Account detail

    private fun detail(mode: PercentDisplay) {
        compose.setContent {
            UsageLimitsTheme {
                AccountDetailScreen(
                    usage = usage(),
                    nowMs = now,
                    staleAfterMs = 3_600_000,
                    percentDisplay = mode,
                    resetInFlight = false,
                    supportsResetCredits = false,
                    onRefresh = {},
                    onConsumeResetCredit = {},
                    onRemove = {},
                    notificationsEnabled = true,
                    onNotificationsChange = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `account detail rows follow the mode`() {
        detail(PercentDisplay.USED)
        assertTrue(spoken("5h limit, 40% used").isNotEmpty())
        assertTrue(spoken("60% remaining").isEmpty())
    }

    @Test
    fun `account detail rows keep the historical wording in LEFT mode`() {
        detail(PercentDisplay.LEFT)
        assertTrue(spoken("5h limit, 60% remaining").isNotEmpty())
        assertTrue(spoken("40% used").isEmpty())
    }
}
