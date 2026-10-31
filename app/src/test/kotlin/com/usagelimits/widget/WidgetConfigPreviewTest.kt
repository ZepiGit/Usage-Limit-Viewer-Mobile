package com.usagelimits.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.Severity
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
        headlineShort = null,
        headlineLong = weekly,
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
}
