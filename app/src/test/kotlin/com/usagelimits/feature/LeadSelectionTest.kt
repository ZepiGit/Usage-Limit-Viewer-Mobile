package com.usagelimits.feature

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.ui.components.Segment
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The overview lead strip: which window leads, whether it is trusted, and what sits beside it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeadSelectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_757_000_000_000L
    private val hour = 3_600_000L

    private fun usage(
        id: String, usedPercent: Double, resetIn: Long, fetchedAgo: Long = 60_000,
        status: SnapshotStatus = SnapshotStatus.OK,
    ) = AccountUsage(
        ProviderAccount(id, ProviderId.CODEX, "ext-$id", null, id, null, "ref-$id", now, now),
        UsageSnapshot(id, now - fetchedAgo, status, listOf(
            UsageWindow("w", "Weekly", WindowCategory.WEEKLY, usedPercent, 604_800, now + resetIn, false),
        )),
    )

    @Test
    fun `a failed account with cached low numbers does not lead while a valid one exists`() {
        val failed = usage("failed", 80.0, 10 * hour, status = SnapshotStatus.FAILED)
        val ok = usage("ok", 30.0, 20 * hour)
        val lead = UsageUiState(accounts = listOf(failed, ok)).leadAt(now)!!
        assertEquals("ok", lead.usage.account.localId)
        assertFalse(lead.stale)
    }

    @Test
    fun `a stale account does not lead while a valid one exists`() {
        val old = usage("old", 90.0, 10 * hour, fetchedAgo = 48 * hour)
        val ok = usage("ok", 30.0, 20 * hour)
        assertEquals("ok", UsageUiState(accounts = listOf(old, ok)).leadAt(now)!!.usage.account.localId)
    }

    @Test
    fun `a window whose reset elapsed since the fetch does not lead`() {
        val closed = usage("closed", 95.0, -hour, fetchedAgo = 2 * hour)
        val ok = usage("ok", 30.0, 20 * hour)
        assertEquals("ok", UsageUiState(accounts = listOf(closed, ok)).leadAt(now)!!.usage.account.localId)
    }

    @Test
    fun `with nothing valid the lead is the tightest cached one and marked stale`() {
        val failed = usage("failed", 80.0, 10 * hour, status = SnapshotStatus.FAILED)
        val lead = UsageUiState(accounts = listOf(failed)).leadAt(now)!!
        assertEquals("failed", lead.usage.account.localId)
        assertTrue(lead.stale)
        assertNull(UsageUiState().leadAt(now))
    }

    @Test
    fun `next reset is the fleet minimum, not the lead account's`() {
        val tight = usage("tight", 96.0, 26 * hour)
        val soon = usage("soon", 10.0, 2 * hour + 12 * 60_000)
        compose.setContent {
            UsageLimitsTheme {
                OverviewScreen(state = UsageUiState(accounts = listOf(tight, soon)), nowMs = now, onRefresh = {},
                    onAccountClick = {}, onAddAccount = {}, onReorder = {})
            }
        }
        compose.onNodeWithText("2h 12m").assertIsDisplayed()
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
