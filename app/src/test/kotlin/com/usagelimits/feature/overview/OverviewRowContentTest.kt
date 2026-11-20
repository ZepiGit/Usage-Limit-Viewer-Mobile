package com.usagelimits.feature.overview

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.UsageSegment
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.feature.UsageUiState
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * What the List row keeps from the Accounts card it replaced (#88 01b review, items 7 and 8): pool
 * names, a reported breakdown, the blocked-although-left note, a mark by the scope rule, and every
 * part readable when the columns do not fit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rGB-w393dp-h1600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverviewRowContentTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_757_000_000_000L // Thu 2025-09-04 15:33:20 UTC
    private val hour = 3_600_000L

    private fun usage(id: String, vararg windows: UsageWindow, provider: ProviderId = ProviderId.ANTIGRAVITY,
        status: SnapshotStatus = SnapshotStatus.OK) = AccountUsage(
        ProviderAccount(id, provider, "ext-$id", "$id@example.invalid", null, null, "ref-$id", now, now),
        UsageSnapshot(id, now, status, windows.toList()),
    )

    private fun window(id: String, label: String, left: Double, group: String? = null,
        scope: UsageScope = UsageScope.GLOBAL, exhausted: Boolean = left <= 0.0, segments: List<UsageSegment> = emptyList()) =
        UsageWindow(id, label, WindowCategory.WEEKLY, 100.0 - left, 604_800, now + 5 * hour, exhausted,
            group = group, scope = scope, segments = segments)

    private fun show(vararg accounts: AccountUsage) {
        compose.setContent {
            UsageLimitsTheme {
                OverviewScreen(state = UsageUiState(accounts = accounts.toList()), nowMs = now, zone = ZoneId.of("Etc/UTC"),
                    onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {})
            }
        }
        compose.waitForIdle()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
    private fun spoken(part: String) = compose.onAllNodes(SemanticsMatcher("speaks $part") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { part in it } == true
    }, useUnmergedTree = true).fetchSemanticsNodes()

    @Test fun `two pools whose buckets share a label are named apart`() {
        show(usage("ag",
            window("p1-week", "Weekly", 60.0, group = "Gemini Pro", scope = UsageScope.MODEL),
            window("p2-week", "Weekly", 30.0, group = "Claude Opus", scope = UsageScope.MODEL)))

        assertEquals("each pool's name is shown once", listOf(1, 1), listOf("Gemini Pro", "Claude Opus").map { shown(it).size })
        val pro = shown("Gemini Pro").single().boundsInRoot
        val opus = shown("Claude Opus").single().boundsInRoot
        val weekly = shown("Weekly").map { it.boundsInRoot.top }.sorted()
        assertEquals(2, weekly.size)
        assertTrue("each Weekly sits under its own pool", pro.top < weekly[0] && weekly[0] < opus.top && opus.top < weekly[1])
        assertEquals("a screen reader hears the pool with the window", 1, spoken("Gemini Pro, Weekly, 60%").size)
        assertEquals(1, spoken("Claude Opus, Weekly, 30%").size)
    }

    @Test fun `a failed read with an exhausted model window beside an open global one is marked limited`() {
        show(usage("ag",
            window("global", "Weekly", 50.0),
            window("opus", "Opus", 0.0, scope = UsageScope.MODEL),
            status = SnapshotStatus.FAILED))

        assertEquals("the mark speaks the scope rule's verdict", 1,
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Limited")),
                useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("not the whole account exhausted", 0,
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Exhausted")),
                useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun `a reported breakdown and a blocked limit keep their lines`() {
        show(usage("xai",
            window("credits", "Monthly credits", 40.0, segments = listOf(UsageSegment("Grok 4", 35.0), UsageSegment("Grok 3", 25.0))),
            window("api", "API", 20.0, exhausted = true),
            provider = ProviderId.XAI))

        assertEquals(1, shown("Grok 4 usage: 35%").size)
        assertEquals(1, shown("Grok 3 usage: 25%").size)
        assertEquals("a screen reader hears the breakdown with its window", 1, spoken("Monthly credits, 40%").filter { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)!!.single().contains("Grok 4 usage: 35%")
        }.size)
        assertEquals(1, shown("Provider reports this limit as blocked despite a positive remaining value.").size)
        assertEquals(1, spoken("API, 20%").filter { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)!!.single().contains("blocked despite a positive")
        }.size)
    }

    @Config(qualifiers = "en-rGB-w200dp-h1600dp", fontScale = 2.0f)
    @Test fun `a narrow window with a large font stacks each window and keeps every part inside the row`() {
        show(usage("codex",
            window("week", "Weekly limit", 64.0),
            window("api", "API", 20.0, exhausted = true),
            provider = ProviderId.CODEX))

        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        for (text in listOf("Weekly limit", "64%", "Provider reports this limit as blocked despite a positive remaining value.")) {
            val node = shown(text).single().boundsInRoot
            assertTrue("$text inside the screen: $node in $root", node.left >= root.left && node.right <= root.right)
            assertTrue("$text has a size: $node", node.width > 0f && node.height > 0f)
        }
        val stamp = shown("20:33").first().boundsInRoot
        assertTrue("the stamp is inside the screen: $stamp", stamp.left >= root.left && stamp.right <= root.right && stamp.width > 0f)
        val label = shown("Weekly limit").single().boundsInRoot
        assertTrue("stacked: the stamp is below its label", stamp.top >= label.bottom)
    }
}
