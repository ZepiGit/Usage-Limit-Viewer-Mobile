package com.usagelimits.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Overview list in its automatic order (ADR 0004), on the screen a person sees.
 *
 * The vector file proves the rule; this proves the screen uses it, follows a new reading and a
 * reset that falls due on its own clock, and offers no way to drag the order back by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h2400dp-xhdpi")
class OverviewOrderingTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_789_000_000_000L

    @Test
    fun `exhausted outranks stale and error, which come last`() {
        val order = Severity.entries.sortedBy { it.urgency }
        assertEquals(Severity.EXHAUSTED, order.first())
        assertTrue(
            "stale and error must rank after every live severity",
            order.indexOf(Severity.STALE) > order.indexOf(Severity.HEALTHY) &&
                order.indexOf(Severity.ERROR) > order.indexOf(Severity.HEALTHY),
        )
        // The property the bug violated, stated directly.
        assertTrue(Severity.EXHAUSTED.urgency < Severity.STALE.urgency)
        assertTrue(Severity.EXHAUSTED.urgency < Severity.ERROR.urgency)
    }

    private fun account(id: String, createdAt: Long = now) = ProviderAccount(
        localId = id,
        provider = ProviderId.CODEX,
        externalAccountId = "ext-$id",
        email = "$id@example.invalid",
        displayName = id,
        plan = null,
        credentialReference = "codex_$id",
        createdAt = createdAt,
        lastSuccessfulSync = now,
    )

    private fun usage(id: String, vararg windows: UsageWindow, connection: ConnectionStatus = ConnectionStatus.CONNECTED,
        createdAt: Long = now) = AccountUsage(
        account(id, createdAt),
        UsageSnapshot(accountId = id, fetchedAt = now, status = SnapshotStatus.OK, windows = windows.toList(),
            connectionStatus = connection),
    )

    private fun window(id: String, left: Double, resetAt: Long = now + 3_600_000) = UsageWindow(
        id = id, label = id, category = WindowCategory.WEEKLY, usedPercent = 100.0 - left,
        periodSeconds = 604_800, resetAt = resetAt, exhausted = left <= 0.0,
    )

    private var state by mutableStateOf(UsageUiState())
    private var clock by mutableLongStateOf(now)

    private fun showOverview() {
        compose.setContent {
            UsageLimitsTheme {
                OverviewScreen(state = state, nowMs = clock, onRefresh = {}, onAccountClick = {},
                    onAddAccount = {}, onPercentDisplayChange = {})
            }
        }
        compose.waitForIdle()
    }

    /** Masked e-mails of the account cards, top to bottom. */
    private fun shownOrder(vararg ids: String): List<String> = ids.sortedBy { id ->
        compose.onNodeWithText("${id.first()}***@example.invalid", substring = true)
            .fetchSemanticsNode().boundsInRoot.top
    }

    @Test
    fun `the screen ranks the stored order and follows a new reading`() {
        // Stored order (the repository's) puts the roomiest account first; the screen does not.
        state = UsageUiState(listOf(
            usage("alpha", window("weekly", 80.0)),
            usage("bravo", window("weekly", 30.0)),
            usage("charlie", window("weekly", 55.0), connection = ConnectionStatus.RECONNECT_REQUIRED),
        ), AppSettings())
        showOverview()
        assertEquals(listOf("charlie", "bravo", "alpha"), shownOrder("alpha", "bravo", "charlie"))

        compose.runOnIdle {
            state = state.copy(accounts = state.accounts.map {
                if (it.account.localId == "alpha") usage("alpha", window("weekly", 10.0)) else it
            })
        }
        compose.waitForIdle()
        assertEquals("a tighter reading moves alpha ahead of bravo", listOf("charlie", "alpha", "bravo"),
            shownOrder("alpha", "bravo", "charlie"))
    }

    @Test
    fun `a reset falling due reorders the screen on its own clock, with no new reading`() {
        state = UsageUiState(listOf(
            usage("alpha", window("5h", 5.0, resetAt = now + 60_000), window("weekly", 70.0)),
            usage("bravo", window("weekly", 40.0)),
        ), AppSettings())
        showOverview()
        assertEquals(listOf("alpha", "bravo"), shownOrder("alpha", "bravo"))

        compose.runOnIdle { clock = now + 60_000 }
        compose.waitForIdle()
        assertEquals("alpha's tight window has reset; its open weekly window ranks it", listOf("bravo", "alpha"),
            shownOrder("alpha", "bravo"))
    }

    @Test
    fun `the display mode does not change the order`() {
        val accounts = listOf(usage("alpha", window("weekly", 60.0)), usage("bravo", window("weekly", 20.0)))
        for (mode in PercentDisplay.entries) {
            assertEquals(mode.name, listOf("bravo", "alpha"),
                UsageUiState(accounts, AppSettings(percentDisplay = mode)).overviewAccountsAt(now).map { it.account.localId })
        }
    }

    @Test
    fun `Overview offers no reorder action`() {
        state = UsageUiState(listOf(usage("alpha", window("weekly", 60.0)), usage("bravo", window("weekly", 20.0))), AppSettings())
        showOverview()
        assertTrue(compose.onAllNodesWithText("Reorder").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Move", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a never-successful failure cannot advance screen freshness`() {
        val failed = usage("A").let {
            it.copy(snapshot = it.snapshot!!.copy(status = SnapshotStatus.FAILED), account = it.account.copy(lastSuccessfulSync = null))
        }
        assertNull(UsageUiState(accounts = listOf(failed)).lastUpdated)
    }
}
