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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
                    onPercentDisplayChange = onChange,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun spoken(description: String) =
        compose.onAllNodesWithContentDescription(description, substring = true).fetchSemanticsNodes()

    private fun text(value: String) = compose.onAllNodesWithText(value).fetchSemanticsNodes()

    /** The display chip's node, found by the label its tap action carries. */
    private fun flipTo(label: String) = SemanticsMatcher("tap action \"$label\"") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    /** The chip is one focus stop: what it shows is its state, read out with its action. */
    private fun chipSays(shown: String) = compose.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, shown)).fetchSemanticsNodes().isNotEmpty()

    // MARK: - Overview in each mode

    @Test
    fun `LEFT mode speaks what is left on the row and the chip names the side`() {
        overview(PercentDisplay.LEFT)

        assertTrue("the row speaks the remaining sentence", spoken("5h limit, 60% remaining").isNotEmpty())
        assertTrue("the chip says the figures count what is left", chipSays("% left"))
        assertTrue("no USED wording in LEFT mode", !chipSays("% used") && spoken("40% used").isEmpty())
    }

    @Test
    fun `USED mode swaps the spoken clause and the chip`() {
        overview(PercentDisplay.USED)

        assertTrue("the row speaks 40% used", spoken("5h limit, 40% used").isNotEmpty())
        assertTrue("the chip says the figures count what is used", chipSays("% used"))
        assertTrue("no LEFT clause survives in USED", spoken("60% remaining").isEmpty() && !chipSays("% left"))
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
    }

    // MARK: - R3: tap to switch, now on the header chip

    @Test
    fun `the display chip is one focus stop with the flip action, the button role and its state`() {
        overview(PercentDisplay.LEFT)

        compose.onAllNodes(flipTo("Show percentage used")).assertCountEquals(1)
        val chip = compose.onNode(flipTo("Show percentage used")).fetchSemanticsNode()
        assertEquals(Role.Button, chip.config.getOrNull(SemanticsProperties.Role))
        assertEquals("% left", chip.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(listOf("Show percentage used"), chip.config.getOrNull(SemanticsProperties.ContentDescription))
    }

    @Test
    fun `invoking the chip asks for USED from LEFT, with a label that names it`() {
        var chosen: PercentDisplay? = null
        overview(PercentDisplay.LEFT) { chosen = it }

        compose.onNode(flipTo("Show percentage used")).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(PercentDisplay.USED, chosen)
    }

    @Test
    fun `invoking the chip asks for LEFT from USED, with a label that names it`() {
        var chosen: PercentDisplay? = null
        overview(PercentDisplay.USED) { chosen = it }

        compose.onAllNodes(flipTo("Show percentage left")).assertCountEquals(1)
        compose.onNode(flipTo("Show percentage left")).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(PercentDisplay.LEFT, chosen)
    }

    @Test
    fun `a finger on the chip flips the mode`() {
        var chosen: PercentDisplay? = null
        overview(PercentDisplay.LEFT) { chosen = it }

        compose.onNode(flipTo("Show percentage used")).performTouchInput { click() }

        assertEquals(PercentDisplay.USED, chosen)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `enter on the focused chip flips the mode`() {
        var chosen: PercentDisplay? = null
        overview(PercentDisplay.LEFT) { chosen = it }

        val chip = compose.onNode(flipTo("Show percentage used"))
        chip.requestFocus()
        chip.performKeyInput { pressKey(Key.Enter) }

        assertEquals(PercentDisplay.USED, chosen)
    }

    // MARK: - R3: what a screen reader reaches during a flip

    /**
     * Rows speaking the LEFT figure ("60% remaining") and the USED one ("40% used") [advanceMs]
     * after the mode flips. Whatever the screen draws meanwhile, the tree describes the current
     * state only: the old figure is out of it at once, with animations on or off.
     */
    private fun spokenAfterFlip(animationScale: Float, advanceMs: Long): Pair<Int, Int> {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, animationScale)
        var mode by mutableStateOf(PercentDisplay.LEFT)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme {
                // The state is rebuilt inside composition so the flip is actually observed;
                // a screen holding a snapshot of the old mode would never change.
                OverviewScreen(
                    state = UsageUiState(accounts = listOf(usage()), settings = AppSettings(percentDisplay = mode)),
                    nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("the row starts on the LEFT figure", 1 to 0, spoken("60% remaining").size to spoken("40% used").size)
        compose.runOnUiThread {
            mode = PercentDisplay.USED
            // With the clock held back no frame or looper turn delivers the write to the
            // composition, so without this the screen would never see the flip.
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(advanceMs)
        return spoken("60% remaining").size to spoken("40% used").size
    }

    @Test
    fun `with animations on a screen reader reaches the new figure at once`() {
        assertEquals("old, new mid-fade", 0 to 1, spokenAfterFlip(animationScale = 1f, advanceMs = 32))
    }

    @Test
    fun `with animations on the old figure is gone once the fade has finished`() {
        assertEquals(0 to 1, spokenAfterFlip(animationScale = 1f, advanceMs = 1_000))
    }

    @Test
    fun `with animations off the figure changes at once`() {
        assertEquals(0 to 1, spokenAfterFlip(animationScale = 0f, advanceMs = 32))
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
