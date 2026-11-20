package com.usagelimits.feature

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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
import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.feature.accounts.AccountDetailScreen
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.feature.resets.ResetsScreen
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

    @Test
    fun `a paused account shows the German reason and keeps its reading`() {
        val snapshot = usage.snapshot!!.copy(
            status = SnapshotStatus.FAILED,
            errorMessage = "Rate limited. Automatic refresh is paused. Refresh manually to try now.",
        )
        render {
            OverviewScreen(
                state = UsageUiState(accounts = listOf(usage.copy(snapshot = snapshot)), settings = AppSettings()),
                nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {},
                onPercentDisplayChange = {},
            )
        }
        val notice = "Zu viele Anfragen. Die automatische Aktualisierung pausiert. Aktualisiere manuell, um es jetzt erneut zu versuchen."
        scrollTo(notice)
        assertShown(notice, substring = true)
        assertEquals(1, compose.onAllNodesWithContentDescription("73 % übrig", substring = true).fetchSemanticsNodes().size)
    }

    private fun renderSettings(mode: PercentDisplay) = render {
        SettingsScreen(
            state = UsageUiState(accounts = listOf(usage), settings = AppSettings(percentDisplay = mode)),
            onSyncIntervalChange = {},
            onNotificationPermissionHandled = {},
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
    fun `the overview row shows the German figure, chip word and reset stamp, and speaks them`() {
        render {
            OverviewScreen(
                state = UsageUiState(accounts = listOf(usage), settings = AppSettings(percentDisplay = PercentDisplay.LEFT)),
                nowMs = now, zone = java.time.ZoneId.of("Etc/UTC"), onRefresh = {}, onAccountClick = {}, onAddAccount = {},
                onPercentDisplayChange = {},
            )
        }
        // 15:33:20 UTC plus one hour: the stamp in German 24-hour time, with no English left.
        assertEquals("the chip shows the German side once", 1, compose.onAllNodesWithText("% übrig", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("the figure is drawn with the German percent spacing", 1,
            compose.onAllNodesWithText("73 %", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("the stamp is drawn", 1, compose.onAllNodesWithText("16:33", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("and spoken with the line", 1,
            compose.onAllNodesWithContentDescription("73 % übrig, 16:33", substring = true).fetchSemanticsNodes().size)
        assertShown("4:33 PM", expected = false)
    }

    @Test
    fun `without a claim or a future reset the German header has no subtitle`() {
        val low = usage.copy(snapshot = usage.snapshot!!.copy(windows = usage.snapshot!!.windows.map {
            it.copy(usedPercent = 85.0, resetAt = null)
        }))
        render {
            OverviewScreen(
                state = UsageUiState(accounts = listOf(low), settings = AppSettings()),
                nowMs = now, zone = java.time.ZoneId.of("Etc/UTC"), onRefresh = {}, onAccountClick = {}, onAddAccount = {},
                onPercentDisplayChange = {},
            )
        }
        assertShown("Alle Konten haben noch Spielraum", expected = false, substring = true)
        assertShown("Nächster Reset", expected = false, substring = true)
        assertShown("nächster Reset", expected = false, substring = true)
        assertShown("Next reset", expected = false, substring = true)
    }

    @Test
    fun `the overview row counts what is left in German`() {
        renderOverview(PercentDisplay.LEFT)

        assertEquals(1, compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "% übrig"))
            .fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithContentDescription("73 % übrig", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithContentDescription("27 % verbraucht", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `the overview row counts what is used in German`() {
        renderOverview(PercentDisplay.USED)

        assertEquals(1, compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "% verbraucht"))
            .fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithContentDescription("27 % verbraucht", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithContentDescription("73 % übrig", substring = true).fetchSemanticsNodes().size)
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
                onPercentDisplayChange = {},
            )
        }

        assertShown("5\u2011Std.-Limit")
        assertShown("5h limit", expected = false)
        // The headline sentence contains the same translated label.
        val startsWithLabel = SemanticsMatcher("description contains the label") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { "5\u2011Std.-Limit, " in it } == true
        }
        val spoken = compose.onAllNodes(startsWithLabel).fetchSemanticsNodes()
        assertEquals("one row speaks the translated label", 1, spoken.size)
    }

    @Test
    fun `the overview row names the long window in German`() {
        val first = usage.snapshot!!.windows.first()
        val withWeekly = usage.copy(
            snapshot = usage.snapshot!!.copy(
                windows = listOf(
                    first,
                    first.copy(
                        id = "weekly",
                        label = "Weekly",
                        category = WindowCategory.WEEKLY,
                        periodSeconds = 604_800,
                        resetAt = now + 3 * 86_400_000L,
                    ),
                ),
            ),
        )
        render {
            OverviewScreen(
                state = UsageUiState(
                    accounts = listOf(withWeekly),
                    settings = AppSettings(percentDisplay = PercentDisplay.LEFT, showRenewalTime = true),
                ),
                nowMs = now,
                onRefresh = {},
                onAccountClick = {},
                onAddAccount = {},
                onPercentDisplayChange = {},
            )
        }

        assertShown("W\u00F6chentlich")
        assertShown("Weekly", expected = false, substring = true)
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
                onPercentDisplayChange = {},
            )
        }

        assertShown("Weekly (Opus)")
        assertShown("W\u00F6chentlich", expected = false)
    }

    @Test
    fun `the overview row names its window in German and no text node keeps the English label`() {
        renderOverview(PercentDisplay.LEFT)

        assertShown("5\u2011Std.-Limit")
        assertShown("5h limit", expected = false, substring = true)
    }

    @Test
    fun `the overview row speaks the window label in German`() {
        renderOverview(PercentDisplay.LEFT)

        val spoken = compose.onAllNodesWithContentDescription("5\u2011Std.-Limit, 73 % übrig", substring = true)
            .fetchSemanticsNodes()
        assertEquals("the row speaks the translated label", 1, spoken.size)
        val english = compose.onAllNodesWithContentDescription("5h limit", substring = true).fetchSemanticsNodes()
        assertEquals("no spoken sentence keeps the English label", 0, english.size)
    }

    private fun renderResets(vararg windows: UsageWindow) = render {
        ResetsScreen(
            state = UsageUiState(accounts = listOf(usage.copy(snapshot = usage.snapshot!!.copy(windows = windows.toList())))),
            nowMs = now,
        )
    }

    @Test
    fun `a reset row names the window in German on screen and in its spoken sentence`() {
        renderResets(usage.snapshot!!.windows.first())

        assertShown("5\u2011Std.-Limit")
        assertShown("5h limit", expected = false, substring = true)
        val spoken = compose.onAllNodesWithContentDescription(", 5\u2011Std.-Limit, ${usage.account.label}, ", substring = true)
            .fetchSemanticsNodes()
        assertEquals("the row speaks the translated label", 1, spoken.size)
        val english = compose.onAllNodesWithContentDescription("5h limit", substring = true).fetchSemanticsNodes()
        assertEquals("no spoken sentence keeps the English label", 0, english.size)
    }

    @Test
    fun `a reset row translates the weekly and monthly horizons`() {
        val first = usage.snapshot!!.windows.first()
        renderResets(
            first.copy(id = "weekly", label = "Weekly", category = WindowCategory.WEEKLY, periodSeconds = 604_800),
            first.copy(
                id = "monthly", label = "Monthly", category = WindowCategory.MONTHLY, periodSeconds = 2_592_000,
                resetAt = now + 7_200_000,
            ),
        )

        assertShown("W\u00F6chentlich")
        assertShown("Monatlich")
        assertShown("Weekly", expected = false, substring = true)
        assertShown("Monthly", expected = false, substring = true)
    }

    @Test
    fun `a reset row keeps a window label the provider reported`() {
        val first = usage.snapshot!!.windows.first()
        renderResets(first.copy(label = "Weekly (Opus)", category = WindowCategory.WEEKLY, scope = UsageScope.MODEL))

        assertShown("Weekly (Opus)")
        assertShown("W\u00F6chentlich", expected = false, substring = true)
        val spoken = compose.onAllNodesWithContentDescription(", Weekly (Opus), ${usage.account.label}, ", substring = true)
            .fetchSemanticsNodes()
        assertEquals("the row speaks the provider's label as it came", 1, spoken.size)
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
