package com.usagelimits.feature

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
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
import com.usagelimits.feature.settings.SettingsScreen
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app screens drawn under the German locale (R4).
 *
 * [ScreenRenderTest] and [PercentDisplaySurfacesTest] pin the English output; this pins that the
 * same screens read their words from resources, so a German phone sees the glossary — übrig,
 * verbraucht, Reset in, Einstellungen, Konto — and the percent sign set off by a space. One window
 * with 27 % used throughout: 73 % left in LEFT, 27 % used in USED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
class GermanScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_757_000_000_000L

    private val usage = AccountUsage(
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
                    usedPercent = 27.0,
                    periodSeconds = 18_000,
                    resetAt = now + 3_600_000,
                    exhausted = false,
                ),
            ),
        ),
    )

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { UsageLimitsTheme { content() } }
        compose.waitForIdle()
    }

    private fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun assertShown(text: String, expected: Boolean = true, substring: Boolean = false) =
        assertEquals("'$text' ${if (expected) "should" else "should not"} be on screen", expected, shown(text, substring))

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))
    }

    private fun renderSettings(mode: PercentDisplay) = render {
        SettingsScreen(
            state = UsageUiState(accounts = listOf(usage), settings = AppSettings(percentDisplay = mode)),
            onSyncIntervalChange = {},
            onNotifyBelow20 = {},
            onNotifyBelow10 = {},
            onNotifyExhausted = {},
            onNotifyResetCredit = {},
            onNotifyAuthExpired = {},
            onNotifyResetApproaching = {},
            onNotifyCreditExpiring = {},
            onShowTier = {},
            onShowRenewal = {},
            onPercentDisplayChange = {},
        )
    }

    private fun renderOverview(mode: PercentDisplay) = render {
        OverviewScreen(
            state = UsageUiState(accounts = listOf(usage), settings = AppSettings(percentDisplay = mode)),
            nowMs = now,
            onRefresh = {},
            onAccountClick = {},
            onAddAccount = {},
            onReorder = {},
            onPercentDisplayChange = {},
        )
    }

    @Test
    fun `settings speaks German and offers the percent display control`() {
        renderSettings(PercentDisplay.LEFT)

        assertShown("Einstellungen")
        assertShown("Settings", expected = false)

        scrollTo("Prozente anzeigen als")
        assertShown("Prozente anzeigen als")
        assertShown("Show percentages as", expected = false)
        // The two choices of the control are the side captions.
        assertShown("Übrig")
        assertShown("Verbraucht")
    }

    @Test
    fun `the notification toggles follow the mode in German`() {
        renderSettings(PercentDisplay.LEFT)
        scrollTo("Unter 20 % übrig")
        assertShown("Unter 20 % übrig")
        assertShown("Below 20% left", expected = false)
    }

    @Test
    fun `the notification toggles read used terms in German`() {
        renderSettings(PercentDisplay.USED)
        scrollTo("Über 80 % verbraucht")
        assertShown("Über 80 % verbraucht")
        assertShown("Über 90 % verbraucht")
        assertShown("Above 80% used", expected = false)
    }

    @Test
    fun `the overview lead and row count what is left in German`() {
        renderOverview(PercentDisplay.LEFT)

        assertShown("73 %")
        assertShown("übrig")
        assertShown("27 %", expected = false)
        // A countdown from resources, with the German unit abbreviations.
        assertShown("Reset in 1 Std.")
        assertShown("1 Konto überwacht", substring = true)
        assertShown("Reset in 1h", expected = false, substring = true)
    }

    @Test
    fun `the overview lead and row count what is used in German`() {
        renderOverview(PercentDisplay.USED)

        assertShown("27 %")
        assertShown("verbraucht")
        assertShown("73 %", expected = false)
        assertShown("Reset in 1 Std.")
    }

    @Test
    fun `a Claude window the app labelled 5h limit reads 5-Std-Limit on the overview and in its spoken row`() {
        val claude = usage.copy(
            account = usage.account.copy(localId = "c", provider = ProviderId.CLAUDE, credentialReference = "ref-c"),
            snapshot = usage.snapshot!!.copy(accountId = "c"),
        )
        render {
            OverviewScreen(
                state = UsageUiState(accounts = listOf(claude), settings = AppSettings(percentDisplay = PercentDisplay.LEFT)),
                nowMs = now,
                onRefresh = {},
                onAccountClick = {},
                onAddAccount = {},
                onReorder = {},
                onPercentDisplayChange = {},
            )
        }

        assertShown("5\u2011Std.-Limit")
        assertShown("5h limit", expected = false)
        // The spoken sentence of the row leads with the same translated label.
        val spoken = compose.onAllNodesWithContentDescription("5\u2011Std.-Limit, ", substring = true)
            .fetchSemanticsNodes()
        assertEquals("one row speaks the translated label", 1, spoken.size)
    }

    @Test
    fun `a window label the provider reported stays verbatim in German`() {
        val reported = usage.copy(
            snapshot = usage.snapshot!!.copy(
                windows = listOf(usage.snapshot!!.windows.first().copy(label = "Weekly (Opus)", category = WindowCategory.WEEKLY)),
            ),
        )
        render {
            OverviewScreen(
                state = UsageUiState(accounts = listOf(reported), settings = AppSettings(percentDisplay = PercentDisplay.LEFT)),
                nowMs = now,
                onRefresh = {},
                onAccountClick = {},
                onAddAccount = {},
                onReorder = {},
                onPercentDisplayChange = {},
            )
        }

        assertShown("Weekly (Opus)")
        assertShown("W\u00F6chentlich", expected = false)
    }

    @Test
    fun `an account detail row and its actions are German`() {
        render {
            AccountDetailScreen(
                usage = usage,
                nowMs = now,
                staleAfterMs = 3_600_000,
                percentDisplay = PercentDisplay.LEFT,
                resetInFlight = false,
                supportsResetCredits = false,
                onRefresh = {},
                onConsumeResetCredit = {},
                onRemove = {},
                notificationsEnabled = true,
                onNotificationsChange = {},
            )
        }

        assertShown("73 %")
        assertShown("übrig")
        assertShown("Reset in 1 Std.")
        scrollTo("Konto entfernen")
        assertShown("Konto entfernen")
        assertShown("Remove account", expected = false)
    }
}
