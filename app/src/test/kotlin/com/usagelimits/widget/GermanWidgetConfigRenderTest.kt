package com.usagelimits.widget

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.usagelimits.core.database.AccountUsage
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
 * The widget configuration screen and its preview under the German locale (R4).
 *
 * [WidgetConfigPreviewTest] pins the English words; this pins that the screen's controls, titles
 * and the preview it draws read them from resources. A separate class from
 * [GermanWidgetRenderTest] because a Compose rule and a Glance session want different SDK setups.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
class GermanWidgetConfigRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val weekly = WidgetRow("Weekly", WindowCategory.WEEKLY, 60.0, null, Severity.HEALTHY)

    private val account = WidgetAccount(
        accountId = "a",
        title = "Claude",
        subtitle = "fixture@example.invalid",
        rows = listOf(weekly),
        severity = Severity.HEALTHY,
        providerId = ProviderId.CLAUDE.id,
        representative = weekly,
    )

    private val snapshot = WidgetSnapshot(
        accounts = listOf(account),
        accountCount = 1,
        updatedAt = null,
        nextResetAt = null,
        overallSeverity = Severity.HEALTHY,
        headlineShort = null,
        headlineLong = weekly,
    )

    private fun texts(value: String) = compose.onAllNodesWithText(value).fetchSemanticsNodes()

    private fun preview(kind: String, mode: PercentDisplay, shown: WidgetSnapshot = snapshot) {
        compose.setContent { UsageLimitsTheme { ContentPreview(shown, kind, WidgetStyle(), mode) } }
        compose.waitForIdle()
    }

    private fun fixtureUsage(): AccountUsage {
        val now = System.currentTimeMillis()
        return AccountUsage(
            ProviderAccount("a", ProviderId.CLAUDE, "ext", "fixture@example.invalid", null, "Pro", "ref", now, now),
            UsageSnapshot("a", now, SnapshotStatus.OK, windows = listOf(
                UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 40.0, 604_800, now + 86_400_000, false))),
        )
    }

    private fun reveal(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    @Test
    fun `the bar preview counts the German title and puts the German side word after the figure`() {
        preview("Usage Bars", PercentDisplay.LEFT)
        assertEquals(1, texts("Vorschau · 1 ausgewählt").size)
        assertEquals(1, texts("60 % übrig").size)
    }

    @Test
    fun `the ring preview carries the German caption and a German spoken description`() {
        preview("Account Rings", PercentDisplay.USED)
        assertEquals(1, texts("Verbraucht").size)
        assertEquals(1, compose.onAllNodesWithContentDescription("Claude, fixture@example.invalid, 40 % verbraucht",
            substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `an empty preview asks for accounts in German`() {
        preview("Account Rings", PercentDisplay.LEFT, WidgetSnapshot.Empty)
        assertEquals(1, texts("Wähle Konten aus, um dein Widget zu sehen").size)
    }

    @Test
    fun `the configuration screen speaks German and the percent control keeps its three segments`() {
        compose.setContent {
            UsageLimitsTheme {
                WidgetConfigScreen(listOf(fixtureUsage()), null, "Usage Bars", saving = false, error = null,
                    percentDisplay = PercentDisplay.LEFT, onCancel = {}, onSave = { _, _, _, _, _, _ -> })
            }
        }
        compose.waitForIdle()
        assertTrue("the widget kind is titled in German", texts("Verbrauchsbalken").isNotEmpty())
        assertEquals(1, texts("Widget speichern").size)
        assertEquals(1, texts("Abbrechen").size)
        reveal("Prozente anzeigen als")
        compose.onNodeWithText("App-Einstellung").assertIsSelected()
        compose.onNodeWithText("Übrig").assertIsNotSelected()
        compose.onNodeWithText("Verbraucht").assertIsNotSelected()
        reveal("Widget-Inhalt")
        assertEquals(1, texts("Widget-Inhalt").size)
        reveal("Ein Anbieter")
        assertEquals(1, texts("Ein Anbieter").size)
    }
}
