package com.usagelimits.feature.overview

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.ui.theme.UsageColors
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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

    private var mode by mutableStateOf(PercentDisplay.LEFT)

    private fun show(vararg accounts: AccountUsage, at: Long = now) {
        compose.setContent {
            UsageLimitsTheme {
                CompositionLocalProvider(LocalMotionEnabled provides false) {
                    OverviewScreen(state = UsageUiState(accounts = accounts.toList(), settings = AppSettings(percentDisplay = mode)),
                        nowMs = at, zone = ZoneId.of("Etc/UTC"),
                        onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()

    /**
     * The row's quota bar as drawn: the share of its track that carries [ink], and whether any of
     * the segment colours appear on it. The bar lies between the label's end and the figure's start,
     * on the label's line.
     */
    private fun bar(label: String, figure: String, ink: Int): Pair<Double, Boolean> {
        val l = shown(label).single().boundsInRoot
        val f = shown(figure).single().boundsInRoot
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        val y = ((l.top + l.bottom) / 2).toInt()
        var filled = 0
        var track = 0
        var segment = false
        for (x in (l.right + 12).toInt() until (f.left - 4).toInt()) {
            for (dy in -3..3) {
                val c = image.getPixel(x, y + dy)
                if (c == ink) filled++
                if (c == ink || c == UsageColors.Rule.toArgb()) track++
                if (c == UsageColors.Ink.toArgb()) segment = true
            }
        }
        image.recycle()
        return (if (track == 0) 0.0 else filled.toDouble() / track) to segment
    }
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

    @Test fun `the identity line names the account and not the age of its reading`() {
        show(usage("ag", window("week", "Weekly", 60.0)))
        assertEquals("the masked e-mail stands alone", 1, shown("a***@example.invalid").size)
        assertEquals("no age in the row", 0,
            compose.onAllNodesWithText("Updated", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private val xai = usage("xai",
        window("credits", "Monthly credits", 40.0, segments = listOf(UsageSegment("Grok 4", 35.0), UsageSegment("Grok 3", 25.0))),
        provider = ProviderId.XAI)

    @Test fun `a breakdown window's bar is the quota in the chosen mode, not the reported segments`() {
        val data = UsageColors.Data.toArgb()
        mode = PercentDisplay.LEFT
        show(xai)
        val (left, leftSegments) = bar("Monthly credits", "40%", data)
        mode = PercentDisplay.USED
        compose.waitForIdle()
        val (used, usedSegments) = bar("Monthly credits", "60%", data)

        assertEquals("% left fills what is left", 0.40, left, 0.05)
        assertEquals("% used fills what is used", 0.60, used, 0.05)
        assertTrue("no segment colour in the row's bar", !leftSegments && !usedSegments)
        assertEquals("the breakdown stays as its legend", 1, shown("Grok 4 usage: 35%").size)
    }

    @Test fun `an aged breakdown reading draws a stale bar and a faint legend`() {
        // Two days after the fetch: old, so the bar is the stale ink and the legend loses its colours.
        show(xai, at = now + 48 * hour)
        val (data, _) = bar("Monthly credits", "40%", UsageColors.Data.toArgb())
        val (stale, segments) = bar("Monthly credits", "40%", UsageColors.StaleMark.toArgb())
        assertEquals("no data ink on an aged reading", 0.0, data, 0.0)
        assertTrue("the stale ink fills the bar", stale > 0.3)
        assertTrue("no segment colour", !segments)
        assertEquals("the legend is faint", UsageColors.Faint, legendColour("Grok 4 usage: 35%"))
    }

    /** The colour the legend text is drawn in, read from its first glyph pixels. */
    private fun legendColour(text: String): androidx.compose.ui.graphics.Color {
        val box = shown(text).single().boundsInRoot
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        try {
            val surface = UsageColors.Surface.toArgb()
            // The brightest pixel of the glyphs is the full text colour; edges are blended.
            var best = surface
            for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() until box.right.toInt()) {
                val c = image.getPixel(x, y)
                if (brightness(c) > brightness(best)) best = c
            }
            return androidx.compose.ui.graphics.Color(best)
        } finally {
            image.recycle()
        }
    }

    private fun brightness(c: Int) = (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)

    @Config(qualifiers = "en-rGB-w300dp-h1600dp", fontScale = 1.6f)
    @Test fun `a mode flip at a large font keeps the layout although the figures differ in width`() {
        // 9% left is 91% used: the wider figure must not push the row over the stacking breakpoint.
        mode = PercentDisplay.LEFT
        show(usage("codex", window("week", "Weekly", 9.0), provider = ProviderId.CODEX))
        val label = shown("Weekly").single().boundsInRoot
        val stampLeft = shown("20:33").single().boundsInRoot
        val stackedLeft = stampLeft.top >= label.bottom
        mode = PercentDisplay.USED
        compose.waitForIdle()
        val stampUsed = shown("20:33").single().boundsInRoot
        val stackedUsed = stampUsed.top >= shown("Weekly").single().boundsInRoot.bottom
        assertEquals("the same layout in both modes", stackedLeft, stackedUsed)
        assertEquals("the stamp does not move", stampLeft, stampUsed)
    }

    /** Where the bar on [label]'s line ends: its rightmost pixel that is not the background. */
    private fun barEnd(label: String, figure: String): Int {
        val l = shown(label).single().boundsInRoot
        val f = shown(figure).single().boundsInRoot
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        try {
            val y = ((l.top + l.bottom) / 2).toInt()
            // Just before the figure is the gap or the figure column's free start: background.
            val background = image.getPixel(f.left.toInt() - 2, y)
            return ((l.right + 12).toInt() until f.left.toInt()).last { x -> (-1..1).any { image.getPixel(x, y + it) != background } }
        } finally {
            image.recycle()
        }
    }

    @Config(qualifiers = "en-rGB-w600dp-h1600dp", fontScale = 1.6f)
    @Test fun `the figure column is as wide as either side's figure, so a mode flip does not move the bar`() {
        // At this size "100%" is wider than the column's floor and "0%" is not: a column sized for
        // the shown side alone would be wider in % left than in % used, and the bar would move.
        mode = PercentDisplay.LEFT
        show(usage("codex", window("week", "Weekly", 100.0), provider = ProviderId.CODEX))
        val endLeft = barEnd("Weekly", "100%")
        mode = PercentDisplay.USED
        compose.waitForIdle()
        assertEquals("the bar ends in the same place in both modes", endLeft, barEnd("Weekly", "0%"))
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
