package com.usagelimits.feature

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.ui.components.Segment
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Overview's header on the screen a person reads, in place of the lead strip it replaced.
 *
 * The rules (which reset, which claim) are proven by the shared vectors; this proves the screen
 * says them: the claim and the stamp joined by one separator, the reset alone, nothing at all, and
 * that a failed, old or reset-due reading is not called current. The zone is fixed, so the stamps
 * do not depend on the host's; the locale is en-GB, whose short time is 24-hour with no narrow
 * no-break space before an AM/PM marker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rGB")
class LeadSelectionTest {

    @get:Rule
    val compose = createComposeRule()

    // 2025-09-04 15:33:20 UTC.
    private val now = 1_757_000_000_000L
    private val hour = 3_600_000L
    private val utc = ZoneId.of("Etc/UTC")

    private fun usage(
        id: String, usedPercent: Double, resetIn: Long?, fetchedAgo: Long = 60_000,
        status: SnapshotStatus = SnapshotStatus.OK, connection: ConnectionStatus = ConnectionStatus.CONNECTED,
    ) = AccountUsage(
        ProviderAccount(id, ProviderId.CODEX, "ext-$id", null, id, null, "ref-$id", now, now),
        UsageSnapshot(id, now - fetchedAgo, status, listOf(
            UsageWindow("w", "Weekly", WindowCategory.WEEKLY, usedPercent, 604_800, resetIn?.let { now + it }, false),
        ), connectionStatus = connection),
    )

    private fun show(vararg accounts: AccountUsage) {
        compose.setContent {
            UsageLimitsTheme {
                OverviewScreen(state = UsageUiState(accounts = accounts.toList()), nowMs = now, zone = utc,
                    onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {})
            }
        }
        compose.waitForIdle()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `room and the next reset share one line`() {
        show(usage("tight", 70.0, 26 * hour), usage("soon", 20.0, 2 * hour))
        compose.onNodeWithText("All accounts have room · next reset 17:33", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `an expired sign-in is counted and its reset is not the header's`() {
        val expired = usage("expired", 20.0, hour, status = SnapshotStatus.FAILED, connection = ConnectionStatus.RECONNECT_REQUIRED)
        show(expired, usage("ok", 30.0, 3 * hour))
        compose.onNodeWithText("1 needs attention · next reset 18:33", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a failed reading cannot claim room but keeps its connected reset`() {
        show(usage("failed", 20.0, 10 * hour, status = SnapshotStatus.FAILED), usage("ok", 30.0, 20 * hour))
        compose.onNodeWithText("Next reset Fri 01:33", useUnmergedTree = true).assertIsDisplayed()
        assertTrue("no room claimed", compose.onAllNodesWithText("All accounts have room", substring = true,
            useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `an old reading cannot claim room`() {
        show(usage("old", 10.0, 10 * hour, fetchedAgo = 48 * hour))
        compose.onNodeWithText("Next reset Fri 01:33", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `without a claim or a future reset the header has no subtitle`() {
        show(usage("low", 85.0, null))
        assertTrue("no claim", !shown("All accounts have room"))
        assertTrue("no reset", compose.onAllNodesWithText("Next reset", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the emphasised line is spoken as the headline`() {
        show(usage("soon", 20.0, 2 * hour + 12 * 60_000))
        compose.onNodeWithContentDescription("Headline: Weekly, 80% remaining, 17:45").assertIsDisplayed()
    }

    @Test
    fun `segment grows past its base height at double font scale and keeps the touch target`() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                UsageLimitsTheme { Segment("Every 30 minutes", selected = true, onClick = {}) }
            }
        }
        // A fixed 32dp cell clamps the 28sp label to 32dp and clips it; a growing cell does not.
        val label = compose.onNodeWithText("Every 30 minutes", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("label clipped at ${label.height}", label.height > 32.dp)
        compose.onNode(isSelectable()).assertHeightIsAtLeast(48.dp)
    }
}
