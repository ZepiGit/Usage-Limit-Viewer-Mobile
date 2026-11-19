package com.usagelimits.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.database.WidgetConfigEntity
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The widget configuration preview in both percent modes (R2).
 *
 * The preview is the one place a user sees a widget before placing it, so it has to say what the
 * placed widget says: rings carry the one-word caption and a spoken description, bars carry the
 * side word after the figure. Fixture: a Weekly window with 40% used (60% left), so a swapped
 * mode shows up as different digits, not only a different word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetConfigPreviewTest {

    @get:Rule
    val compose = createComposeRule()

    private val weekly = WidgetRow("Weekly", WindowCategory.WEEKLY, 60.0, null, Severity.HEALTHY)

    private fun account(partialUpdate: Boolean = false) = WidgetAccount(
        accountId = "a",
        title = "Claude",
        subtitle = "fixture@example.invalid",
        rows = listOf(weekly),
        severity = Severity.HEALTHY,
        providerId = ProviderId.CLAUDE.id,
        representative = weekly,
        partialUpdate = partialUpdate,
    )

    private fun snapshot(vararg accounts: WidgetAccount) = WidgetSnapshot(
        accounts = accounts.toList(),
        accountCount = accounts.size,
        updatedAt = null,
        nextResetAt = null,
        overallSeverity = Severity.HEALTHY,
        summaryLineShort = null,
        summaryLineLong = weekly,
    )

    private class Shown(val snapshot: WidgetSnapshot, val kind: String, val mode: PercentDisplay)

    private var shown by mutableStateOf<Shown?>(null)
    private var composed = false

    /** Shows the preview; later calls re-drive the same composition, since a rule composes once. */
    private fun preview(snapshot: WidgetSnapshot, kind: String, mode: PercentDisplay) {
        compose.runOnIdle { shown = Shown(snapshot, kind, mode) }
        if (!composed) {
            composed = true
            compose.setContent {
                UsageLimitsTheme {
                    shown?.let { ContentPreview(it.snapshot, it.kind, WidgetStyle(), it.mode) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun texts(value: String) = compose.onAllNodesWithText(value).fetchSemanticsNodes()

    private fun spoken(value: String) =
        compose.onAllNodesWithContentDescription(value, substring = true).fetchSemanticsNodes()

    private fun caption(mode: PercentDisplay) = if (mode == PercentDisplay.USED) "Used" else "Left"
    private fun otherCaption(mode: PercentDisplay) = if (mode == PercentDisplay.USED) "Left" else "Used"

    @Test
    fun `ring previews caption the mode and describe each ring`() {
        for (kind in listOf("Account Rings", "Mini Rings")) {
            for (mode in PercentDisplay.entries) {
                preview(snapshot(account()), kind, mode)
                assertEquals("$kind $mode: one caption '${caption(mode)}'", 1, texts(caption(mode)).size)
                assertTrue("$kind $mode: no caption of the other mode", texts(otherCaption(mode)).isEmpty())
                val sentence = if (mode == PercentDisplay.USED) "Claude, fixture@example.invalid, 40% used"
                    else "Claude, fixture@example.invalid, 60% remaining"
                assertEquals("$kind $mode: the ring says '$sentence'", 1, spoken(sentence).size)
            }
        }
    }

    @Test
    fun `an account rings preview writes the centre figure as the placed widget does`() {
        fun ringAt(id: String, remaining: Double, severity: Severity = Severity.HEALTHY): WidgetAccount {
            val row = WidgetRow("Weekly", WindowCategory.WEEKLY, remaining, null, severity)
            return account().copy(accountId = id, rows = listOf(row), representative = row)
        }
        val accounts = snapshot(ringAt("full", 100.0), ringAt("part", 42.0), ringAt("spent", 0.0, Severity.EXHAUSTED))
        preview(accounts, "Account Rings", PercentDisplay.LEFT)
        assertEquals("LEFT: the ring at 42 writes it", 1, texts("42%").size)
        assertEquals("LEFT: the exhausted ring writes 0%", 1, texts("0%").size)
        assertTrue("LEFT: the full allowance writes nothing", texts("100%").isEmpty() && texts("100").isEmpty())
        assertEquals("LEFT: the full ring still says it", 1, spoken("100% remaining").size)
        preview(accounts, "Account Rings", PercentDisplay.USED)
        assertEquals("USED: the full allowance is 0 used and writes it", 1, texts("0%").size)
        assertEquals("USED: the ring at 42 left writes 58", 1, texts("58%").size)
        assertEquals("USED: the exhausted ring writes a bare 100", 1, texts("100").size)
        assertTrue("USED: never 100% in the centre", texts("100%").isEmpty())
        assertEquals("USED: the exhausted ring still says it", 1, spoken("100% used").size)
        // Mini Rings carry the provider mark, never a figure, in the preview as on the home screen.
        preview(accounts, "Mini Rings", PercentDisplay.LEFT)
        assertTrue("Mini Rings write no figure", texts("42%").isEmpty() && texts("100%").isEmpty())
    }

    @Test
    fun `a ring preview describes a partial update the way the placed widget does`() {
        preview(snapshot(account(partialUpdate = true)), "Account Rings", PercentDisplay.USED)
        assertEquals(1, spoken("Claude, fixture@example.invalid, 40% used, some limits could not be updated").size)
    }

    @Test
    fun `bar previews put the side word after the figure and carry no ring caption`() {
        for (mode in PercentDisplay.entries) {
            preview(snapshot(account()), "Usage Bars", mode)
            val figure = if (mode == PercentDisplay.USED) "40% used" else "60% left"
            val other = if (mode == PercentDisplay.USED) "60% left" else "40% used"
            assertEquals("$mode: figure '$figure'", 1, texts(figure).size)
            assertTrue("$mode: the other mode's '$other' must be absent", texts(other).isEmpty())
            assertTrue("$mode: bars have no one-word caption", texts("Left").isEmpty() && texts("Used").isEmpty())
        }
    }

    @Test
    fun `an empty preview shows its message and no caption`() {
        for (mode in PercentDisplay.entries) {
            preview(WidgetSnapshot.Empty, "Account Rings", mode)
            assertTrue("$mode: no caption over an empty preview", texts("Left").isEmpty() && texts("Used").isEmpty())
        }
    }

    private fun fixtureUsage(): AccountUsage {
        val now = System.currentTimeMillis()
        return AccountUsage(
            ProviderAccount("a", ProviderId.CLAUDE, "ext", "fixture@example.invalid", null, "Pro", "ref", now, now),
            UsageSnapshot("a", now, SnapshotStatus.OK, windows = listOf(
                UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 40.0, 604_800, now + 86_400_000, false))),
        )
    }

    /**
     * The preview of an All accounts widget shows the two accounts the placed widget would, in
     * Overview's order (ADR 0004), not the stored one; Custom keeps its saved order.
     */
    @Test
    fun `the All accounts preview leads with the account Overview ranks first`() {
        // A fixed instant and no reset: nothing in this order depends on the clock the preview reads.
        val fetchedAt = 1_800_000_000_000L
        fun usage(id: String, provider: ProviderId, left: Double) = AccountUsage(
            // The plan makes each title unique to the preview; the provider list says only the name.
            ProviderAccount(id, provider, "ext-$id", "$id@example.invalid", null, "Fixture", "ref-$id", fetchedAt, fetchedAt),
            UsageSnapshot(id, fetchedAt, SnapshotStatus.OK, windows = listOf(
                UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 100.0 - left, 604_800, null, false))),
        )
        // Stored order: Codex (90 left), Kimi (50 left), Claude (12 left).
        val stored = listOf(usage("codex", ProviderId.CODEX, 90.0), usage("kimi", ProviderId.KIMI, 50.0),
            usage("claude", ProviderId.CLAUDE, 12.0))
        fun configured(scope: String, custom: String = "[]") = WidgetConfigEntity(7, scope, null, null, 1L,
            customAccountIdsJson = custom, layoutMetricsJson = WidgetMetrics().toJson())
        var config by mutableStateOf(configured("ALL_ACCOUNTS"))
        compose.setContent {
            UsageLimitsTheme {
                androidx.compose.runtime.key(config) {
                    WidgetConfigScreen(stored, config, "Usage Bars", saving = false, error = null,
                        percentDisplay = PercentDisplay.LEFT, onCancel = {}, onSave = { _, _, _, _, _ -> })
                }
            }
        }
        compose.waitForIdle()
        fun top(title: String) = compose.onAllNodesWithText(title).fetchSemanticsNodes().map { it.boundsInRoot.top }
        assertEquals("the two tightest accounts are previewed", 1, top("Claude Fixture").size)
        assertEquals(1, top("Kimi Fixture").size)
        assertTrue("the roomiest is trimmed", top("OpenAI Codex Fixture").none())
        assertTrue("Claude (12 left) leads Kimi (50 left)", top("Claude Fixture").single() < top("Kimi Fixture").single())

        // Saved Kimi, Claude, Codex: against both the stored order (Codex, Kimi) and the rank (Claude, Kimi).
        compose.runOnIdle { config = configured("CUSTOM", """["kimi","claude","codex"]""") }
        compose.waitForIdle()
        assertTrue("Custom previews its first two saved accounts", top("OpenAI Codex Fixture").none())
        assertTrue("Custom keeps its saved order", top("Kimi Fixture").single() < top("Claude Fixture").first())
    }

    @Test
    fun `the configuration preview follows app mode changes`() {
        var appMode by mutableStateOf(PercentDisplay.LEFT)
        compose.setContent {
            UsageLimitsTheme {
                WidgetConfigScreen(listOf(fixtureUsage()), null, "Usage Bars", saving = false, error = null,
                    percentDisplay = appMode, onCancel = {}, onSave = { _, _, _, _, _ -> })
            }
        }
        compose.waitForIdle()
        assertEquals("the preview starts on the app mode", 1, texts("60% left").size)
        compose.runOnIdle { appMode = PercentDisplay.USED }
        compose.waitForIdle()
        assertEquals("the app setting changes the preview", 1, texts("40% used").size)
        assertTrue("the previous figure is gone", texts("60% left").isEmpty())
    }
}
