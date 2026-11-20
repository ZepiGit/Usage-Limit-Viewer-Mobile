package com.usagelimits.feature.overview

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.feature.UsageUiState
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * Within the List card every figure ends at one x and every bar starts at one x, whatever each
 * row's labels and stamps are: the columns are shared by all rows, not sized per row. One row
 * resets later today ("17:33") under a short label, the other in ten days, whose dated stamp is
 * wider than the stamp column's 66dp minimum, under a long label. Sized per row, both the figure's
 * end and the bar's start would differ between the two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rGB-w393dp-h900dp")
// Real glyph widths: the legacy mode measures every character as one pixel, so every label would
// fit the 58dp minimum and the columns could not differ.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverviewColumnsTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_757_000_000_000L // Thu 2025-09-04 15:33:20 UTC
    private val hour = 3_600_000L

    private fun usage(id: String, label: String, left: Double, resetIn: Long) = AccountUsage(
        ProviderAccount(id, ProviderId.CODEX, "ext-$id", "$id@example.invalid", null, null, "ref-$id", now, now),
        UsageSnapshot(id, now, SnapshotStatus.OK, listOf(
            UsageWindow("w", label, WindowCategory.WEEKLY, 100.0 - left, 604_800, now + resetIn, false))),
    )

    @Test fun `figures end and bars start in one column across the rows of the card`() {
        compose.setContent {
            UsageLimitsTheme {
                OverviewScreen(
                    state = UsageUiState(accounts = listOf(
                        usage("a", "5h", 9.0, 2 * hour),
                        usage("b", "Weekly (all models)", 64.0, 10 * 24 * hour),
                    )),
                    nowMs = now, zone = ZoneId.of("Etc/UTC"),
                    onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
                )
            }
        }
        compose.waitForIdle()


        // The line's merged label node starts its line; the bar is its next sibling in the grid,
        // so the bars line up exactly when the label column has one width card-wide.
        val labels = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) and
            SemanticsMatcher("a window line") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { "remaining" in it } == true
            }, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, labels.size)
        assertEquals("labels have one column width", labels[0].boundsInRoot.width, labels[1].boundsInRoot.width, 0.5f)

        val figures = listOf("9%", "64%").map { compose.onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes().single() }
        assertEquals("figures end at one x", figures[0].boundsInRoot.right, figures[1].boundsInRoot.right, 0.5f)
    }
}
