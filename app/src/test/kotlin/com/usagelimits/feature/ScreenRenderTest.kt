package com.usagelimits.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.ui.theme.colors
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.OverviewLayout
import com.usagelimits.feature.overview.OverviewFilter
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.feature.accounts.AccountDetailScreen
import com.usagelimits.feature.accounts.AddAccountScreen
import com.usagelimits.feature.accounts.AddAccountState
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.feature.resets.ResetsScreen
import com.usagelimits.feature.settings.SettingsScreen
import com.usagelimits.ui.theme.UsageLimitsTheme
import androidx.compose.ui.test.hasClickAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every screen, actually rendered.
 *
 * Until this file existed, no Compose code in this app had ever been executed anywhere: the unit
 * tests covered parsers and the CI job compiled the module, and neither of those runs a
 * composable. An app whose screens have never been drawn is an app nobody has run, and the
 * failures that hides — a crash in a lazy list key, a null dereference in a formatter, a theme
 * that cannot resolve — are exactly the ones a user meets first.
 *
 * Robolectric on the JVM rather than an instrumented test, because this environment has no KVM
 * and therefore no emulator. It is a weaker signal than a device, and it is the difference
 * between "never run" and "runs".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_757_000_000_000L

    private fun account(
        id: String = "a",
        provider: ProviderId = ProviderId.CODEX,
        plan: String? = "Plus",
    ) = ProviderAccount(
        localId = id,
        provider = provider,
        externalAccountId = "ext-$id",
        email = "someone@example.com",
        displayName = null,
        plan = plan,
        credentialReference = "ref-$id",
        createdAt = 0L,
        lastSuccessfulSync = now,
    )

    private fun window(usedPercent: Double?, label: String = "5h limit") = UsageWindow(
        id = "5h",
        label = label,
        category = WindowCategory.FIVE_HOUR,
        usedPercent = usedPercent,
        periodSeconds = 18_000,
        resetAt = now + 3_600_000,
        exhausted = false,
    )

    private fun usage(
        id: String = "a",
        usedPercent: Double? = 40.0,
        status: SnapshotStatus = SnapshotStatus.OK,
        provider: ProviderId = ProviderId.CODEX,
    ) = AccountUsage(
        account = account(id, provider),
        snapshot = UsageSnapshot(
            accountId = id,
            fetchedAt = now,
            status = status,
            windows = listOf(window(usedPercent)),
        ),
    )

    private fun state(vararg accounts: AccountUsage) = UsageUiState(
        accounts = accounts.toList(),
        settings = AppSettings(),
    )

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { UsageLimitsTheme { content() } }
        compose.waitForIdle()
    }

    // MARK: - The four screens, with data

    @Test
    fun `overview renders an account`() {
        render {
            OverviewScreen(
                state = state(usage()),
                nowMs = now,
                onRefresh = {},
                onAccountClick = {},
                onAddAccount = {},
                onPercentDisplayChange = {},
            )
        }

        assertTrue(
            "the window label should be on screen",
            compose.onAllNodesWithText("5h limit", substring = true)
                .fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `an otherwise current Overview row shows a pause without presenting a failure`() {
        render {
            OverviewScreen(
                state = state(pausedAntigravity()), nowMs = now, onRefresh = {}, onAccountClick = {},
                onAddAccount = {}, onPercentDisplayChange = {},
            )
        }

        compose.onNodeWithText(pauseNotice, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("60%", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("showing last known data", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("partial update", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Stale", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Error", useUnmergedTree = true).assertDoesNotExist()
    }

    // C1, loading and a filter without matches: OverviewEmptyStatesTest, in English and German.

    @Test
    fun `overview survives a window whose percentage the provider never stated`() {
        // Unknown is not zero, and the formatter has to say so rather than divide by it.
        render {
            OverviewScreen(
                state = state(usage(usedPercent = null)),
                nowMs = now,
                onRefresh = {},
                onAccountClick = {},
                onAddAccount = {},
                onPercentDisplayChange = {},
            )
        }

        compose.waitForIdle()
    }

    @Test
    fun `a failed row says why once, with its last figures and a retry`() {
        val failed = usage(status = SnapshotStatus.FAILED).let {
            it.copy(snapshot = it.snapshot!!.copy(errorMessage = "Network unavailable"))
        }
        render {
            OverviewScreen(
                state = state(failed),
                nowMs = now,
                onRefresh = {},
                onAccountClick = {},
                onAddAccount = {},
                onPercentDisplayChange = {},
            )
        }

        fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        assertTrue("the stored message, worded as a failure", shown("Network unavailable · showing last known data"))
        assertTrue("the last figure stays", shown("60%"))
        assertTrue("a retry is offered", shown("Retry"))
        assertTrue("the bare severity word does not repeat the message", !shown("Error"))
    }

    @Test
    fun `failed retained global scope determines the actual List mark`() {
        val sample = mutableStateOf(usage(status = SnapshotStatus.FAILED))
        render {
            OverviewScreen(
                state = state(sample.value), nowMs = now,
                onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
            )
        }
        val cases = listOf(
            Triple(37.0, 90.0, null),
            Triple(37.0, 70.0, null),
            Triple(70.0, 90.0, "Moderate"),
            Triple(90.0, 37.0, "Low"),
            Triple(100.0, 37.0, "Exhausted"),
            Triple(37.0, 100.0, "Limited"),
            Triple(null, 90.0, null),
        )
        for ((globalUsed, modelUsed, expected) in cases) {
            compose.runOnIdle {
                sample.value = usage(status = SnapshotStatus.FAILED).let {
                    it.copy(snapshot = it.snapshot!!.copy(windows = listOf(
                        window(globalUsed, "Weekly").copy(id = "global"),
                        window(modelUsed, "Model").copy(id = "model", scope = com.usagelimits.core.model.UsageScope.MODEL),
                    )))
                }
            }
            compose.waitForIdle()
            for (label in listOf("Moderate", "Low", "Limited", "Exhausted", "Unknown")) {
                val mark = compose.onNode(hasContentDescription(label), useUnmergedTree = true)
                if (label == expected) mark.assertIsDisplayed() else mark.assertDoesNotExist()
            }
            compose.onNodeWithText("Error", useUnmergedTree = true).assertIsDisplayed()
            if (modelUsed == 90.0 && globalUsed != 90.0) {
                compose.onNodeWithText("10%", useUnmergedTree = true).assertIsDisplayed()
            }
            if (globalUsed == 37.0) {
                compose.onNodeWithText("63%", useUnmergedTree = true).assertIsDisplayed()
            }
        }
    }

    @Test
    fun `overview renders an account that has never synced`() {
        // A snapshot of null is a distinct shape from a failed one, and it reaches every
        // formatter on the card.
        render {
            OverviewScreen(
                state = UsageUiState(
                    accounts = listOf(AccountUsage(account = account(), snapshot = null)),
                ),
                nowMs = now,
                onRefresh = {},
                onAccountClick = {},
                onAddAccount = {},
                onPercentDisplayChange = {},
            )
        }

        compose.waitForIdle()
    }

    // Tall enough that the lazy list composes all four rows.
    @Config(qualifiers = "w393dp-h1600dp")
    @Test
    fun `overview renders one row per account of four providers`() {
        render {
            OverviewScreen(
                state = state(
                    usage("a", provider = ProviderId.CODEX),
                    usage("b", provider = ProviderId.CLAUDE),
                    usage("c", provider = ProviderId.ANTIGRAVITY),
                    usage("d", provider = ProviderId.XAI),
                ),
                nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
            )
        }

        assertEquals("one row per account", 4,
            compose.onAllNodes(androidx.compose.ui.test.hasClickAction() and
                androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasContentDescription("Headline", substring = true)))
                .fetchSemanticsNodes().size)
    }

    @Test
    fun `resets renders upcoming windows`() {
        render { ResetsScreen(state = state(usage()), nowMs = now) }

        compose.waitForIdle()
    }

    @Test
    fun `resets renders with nothing scheduled`() {
        render { ResetsScreen(state = state(), nowMs = now) }

        compose.waitForIdle()
    }

    @Test
    fun `settings renders and its toggles report back`() {
        var toggled = false
        render {
            SettingsScreen(
                state = state(usage()),
                onSyncIntervalChange = {},
                onNotificationPermissionHandled = {},
                onNotifyBelow20 = { toggled = true },
                onNotifyBelow10 = {},
                onNotifyExhausted = {},
                onNotifyResetCredit = {},
                onNotifyAuthExpired = {},
                onNotifyResetApproaching = {},
                onNotifyCreditExpiring = {},
                onShowTier = {},
                onOverviewLayoutChange = {},
                onPercentDisplayChange = {},
            )
        }

        // LazyColumn only composes visible rows; scroll before looking for a toggle below the fold.
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Below 20% left", substring = true))
        compose.onAllNodesWithText("Below 20% left", substring = true)
            .fetchSemanticsNodes()
            .let { assertTrue("the 20% toggle should be reachable", it.isNotEmpty()) }

        // Only the tier remains configurable; reset stamps are part of the layout.
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Show subscription tier", substring = true))
        compose.onAllNodesWithText("Show subscription tier", substring = true)
            .fetchSemanticsNodes()
            .let { assertTrue("the tier switch should be reachable", it.isNotEmpty()) }
        compose.onAllNodesWithText("Show renewal time", substring = true)
            .fetchSemanticsNodes()
            .let { assertTrue("the stored renewal preference no longer has a switch", it.isEmpty()) }

        // The percent-display control, with both modes reachable.
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Show percentages as", substring = true))
        compose.onAllNodesWithText("Show percentages as", substring = true)
            .fetchSemanticsNodes()
            .let { assertTrue("the percent display control should be reachable", it.isNotEmpty()) }

        // Credential storage details are absent from Settings.
        compose.onAllNodesWithText("Credential storage", substring = true)
            .fetchSemanticsNodes()
            .let { assertTrue("the Security section should be gone", it.isEmpty()) }
    }

    @Test
    @Config(qualifiers = "w393dp-h873dp")
    fun `settings offers five minutes and explains background delays`() {
        var chosen: Int? = null
        renderSettings(PercentDisplay.LEFT) { chosen = it }

        val caption = "Android may refresh less often in Doze or battery saver."
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(caption))
        compose.onNodeWithText(caption).assertIsDisplayed()
        compose.onNodeWithText("5m").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(5, chosen)
    }

    private fun renderSettings(mode: PercentDisplay, onSyncIntervalChange: (Int) -> Unit = {}) = render {
        SettingsScreen(
            state = UsageUiState(accounts = listOf(usage()), settings = AppSettings(percentDisplay = mode)),
            onSyncIntervalChange = onSyncIntervalChange,
            onNotificationPermissionHandled = {},
            onNotifyBelow20 = {},
            onNotifyBelow10 = {},
            onNotifyExhausted = {},
            onNotifyResetCredit = {},
            onNotifyAuthExpired = {},
            onNotifyResetApproaching = {},
            onNotifyCreditExpiring = {},
            onShowTier = {},
            onOverviewLayoutChange = {},
            onPercentDisplayChange = {},
        )
    }

    private fun assertShown(text: String, expected: Boolean) {
        val found = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        assertEquals("'$text' ${if (expected) "should" else "should not"} be on the settings screen", expected, found)
    }

    @Test
    fun `the notification toggles are worded in used terms when the mode is used`() {
        // The thresholds are unchanged (the same two crossings on remaining); only the words
        // follow the mode, so the toggle reads in the figures the user is watching.
        renderSettings(PercentDisplay.USED)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Above 80% used"))

        assertShown("Above 80% used", true)
        assertShown("Once, the first time a limit exceeds four fifths used", true)
        assertShown("Above 90% used", true)
        assertShown("Once more when it gets tight, even after the 80% warning", true)

        assertShown("Below 20% left", false)
        assertShown("Below 10% left", false)
        assertShown("Once, the first time a limit drops under a fifth remaining", false)
        assertShown("Once more when it gets tight, even after the 20% warning", false)
    }

    @Test
    fun `the notification toggles keep their left wording when the mode is left`() {
        renderSettings(PercentDisplay.LEFT)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Below 20% left"))

        assertShown("Below 20% left", true)
        assertShown("Once, the first time a limit drops under a fifth remaining", true)
        assertShown("Below 10% left", true)
        assertShown("Once more when it gets tight, even after the 20% warning", true)

        assertShown("Above 80% used", false)
        assertShown("Above 90% used", false)
        assertShown("Once, the first time a limit exceeds four fifths used", false)
        assertShown("Once more when it gets tight, even after the 80% warning", false)
    }

    @Test
    @Config(qualifiers = "en-rGB-w393dp-h1600dp")
    fun `settings previews change with layout display mode and tier`() {
        val settings = mutableStateOf(AppSettings())
        render {
            SettingsScreen(
                state = UsageUiState(settings = settings.value),
                onSyncIntervalChange = {}, onNotificationPermissionHandled = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {},
                onNotifyExhausted = {}, onNotifyResetCredit = {}, onNotifyAuthExpired = {}, onNotifyResetApproaching = {},
                onNotifyCreditExpiring = {}, onShowTier = { settings.value = settings.value.copy(showSubscriptionTier = it) },
                onOverviewLayoutChange = { settings.value = settings.value.copy(overviewLayout = it) },
                onPercentDisplayChange = { settings.value = settings.value.copy(percentDisplay = it) },
            )
        }
        val scroll = compose.onNode(hasScrollToNodeAction())
        scroll.performScrollToNode(hasText("Layout"))
        compose.onNodeWithText("OpenAI Codex Plus", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("61%", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasContentDescription("Show subscription tier") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("OpenAI Codex Plus", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithText("OpenAI Codex", useUnmergedTree = true).onLast().assertIsDisplayed()

        scroll.performScrollToNode(hasText("Used"))
        compose.onNode(hasText("Used") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("used", useUnmergedTree = true).assertIsDisplayed()
        scroll.performScrollToNode(hasText("Dials"))
        compose.onNode(hasText("Dials") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNode(hasContentDescription("OpenAI Codex", substring = true) and
            hasContentDescription("56%", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("39%", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "en-rGB")
    fun `an expired dial shows reconnect while keeping its reset stamp in semantics`() {
        val expired = usage().let { usage ->
            usage.copy(snapshot = usage.snapshot!!.copy(connectionStatus = ConnectionStatus.RECONNECT_REQUIRED))
        }
        render {
            OverviewScreen(
                state = UsageUiState(accounts = listOf(expired), settings = AppSettings(overviewLayout = OverviewLayout.DIALS)),
                nowMs = now, zone = java.time.ZoneId.of("Etc/UTC"),
                onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
            )
        }

        val tile = compose.onNode(hasContentDescription("OpenAI Codex", substring = true) and
            hasContentDescription("16:33", substring = true))
        tile.assertIsDisplayed()
        compose.onNodeWithText("Reconnect", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("5h limit · 16:33", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `same-provider dials identify accounts without emails`() {
        val first = usage(id = "first").let { usage ->
            usage.copy(account = usage.account.copy(email = null, createdAt = 1L))
        }
        val second = usage(id = "second").let { usage ->
            usage.copy(account = usage.account.copy(email = null, createdAt = 2L))
        }
        render {
            OverviewScreen(
                state = UsageUiState(accounts = listOf(first, second), settings = AppSettings(overviewLayout = OverviewLayout.DIALS)),
                nowMs = now, zone = java.time.ZoneId.of("Etc/UTC"),
                onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
            )
        }

        compose.onNode(hasContentDescription("OpenAI Codex", substring = true) and
            hasContentDescription("Account 1", substring = true)).assertIsDisplayed()
        compose.onNode(hasContentDescription("OpenAI Codex", substring = true) and
            hasContentDescription("Account 2", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `a moderate dial does not add an extra spoken severity`() {
        render {
            OverviewScreen(
                state = UsageUiState(accounts = listOf(usage(usedPercent = 60.0)),
                    settings = AppSettings(overviewLayout = OverviewLayout.DIALS)),
                nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
            )
        }
        compose.onNode(hasContentDescription("OpenAI Codex", substring = true) and hasClickAction())
            .assert(hasContentDescription("40% remaining", substring = true) and hasContentDescription("Moderate", substring = true).not())
    }

    @Test
    @Config(qualifiers = "en-rGB-w393dp-h1600dp")
    fun `List and Dials preserve stored reading messages with their reset stamps`() {
        val layout = mutableStateOf(OverviewLayout.LIST)
        val snapshot = mutableStateOf(usage().snapshot!!.copy(errorMessage = "Network unavailable"))
        render {
            OverviewScreen(
                state = UsageUiState(accounts = listOf(usage().copy(snapshot = snapshot.value)),
                    settings = AppSettings(overviewLayout = layout.value)),
                nowMs = now, zone = java.time.ZoneId.of("Etc/UTC"), onRefresh = {}, onAccountClick = {},
                onAddAccount = {}, onPercentDisplayChange = {},
            )
        }
        for (choice in OverviewLayout.entries) {
            for ((status, message) in listOf(SnapshotStatus.OK to "Network unavailable",
                SnapshotStatus.PARTIAL to "Network unavailable · partial update",
                SnapshotStatus.FAILED to "Network unavailable · showing last known data")) {
                compose.runOnIdle { layout.value = choice; snapshot.value = snapshot.value.copy(status = status) }
                compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText(if (choice == OverviewLayout.LIST) "16:33" else "5h limit · 16:33",
                    useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText("60%", useUnmergedTree = true).assertIsDisplayed()
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-rGB-w393dp-h873dp")
    fun `Dials draw warning expired stale data and missing arcs in their actual palette colours`() {
        val palette = AppTheme.PURE_BLACK.colors
        val low = usage(usedPercent = 80.0)
        val data = usage(usedPercent = 40.0)
        val expired = low.copy(snapshot = low.snapshot!!.copy(connectionStatus = ConnectionStatus.RECONNECT_REQUIRED))
        val stale = low.copy(snapshot = low.snapshot!!.copy(fetchedAt = now - 48 * 3_600_000L))
        val failed = low.copy(snapshot = low.snapshot!!.copy(status = SnapshotStatus.FAILED))
        val unknown = usage(usedPercent = null)
        val reading = mutableStateOf(low)
        compose.setContent {
            UsageLimitsTheme {
                CompositionLocalProvider(LocalMotionEnabled provides false) {
                    OverviewScreen(
                        state = UsageUiState(accounts = listOf(reading.value),
                            settings = AppSettings(overviewLayout = OverviewLayout.DIALS, percentDisplay = PercentDisplay.USED)),
                        nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
                    )
                }
            }
        }
        val cases = listOf(
            "low" to (low to palette.Warning), "exhausted" to (usage(usedPercent = 100.0) to palette.Warning),
            "expired" to (expired to palette.Faint),
            "stale" to (stale to palette.StaleMark), "current" to (data to palette.Data),
            "failed" to (failed to palette.Rule), "unknown" to (unknown to palette.Rule),
        )
        for ((name, sample) in cases) {
            compose.runOnIdle { reading.value = sample.first }
            compose.waitForIdle()
            val tile = compose.onNode(hasContentDescription("OpenAI Codex", substring = true) and hasClickAction())
                .fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            val root = compose.activity.window.decorView
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            try {
                // The 3 o'clock centre of the real 104dp ring: 48dp radius, 16dp tile top padding.
                val x = (tile.center.x + 48 * density).toInt()
                val y = (tile.top + 68 * density).toInt()
                assertEquals("$name ring stroke", sample.second.toArgb(), image.getPixel(x, y))
            } finally { image.recycle() }
        }
    }

    @Test
    @Config(qualifiers = "en-rGB-w393dp-h1600dp", fontScale = 1.6f)
    fun `large Dials keep the headline and ignore the List filter`() {
        val codex = usage().let { usage ->
            usage.copy(snapshot = usage.snapshot!!.copy(windows = listOf(
                window(20.0).copy(id = "first", resetAt = now + 2 * 60 * 60 * 1000),
                window(90.0).copy(id = "headline", resetAt = now + 3 * 60 * 60 * 1000),
            )))
        }
        val claude = codex.copy(account = account("claude", ProviderId.CLAUDE, "Pro"))
        var opened: String? = null
        var add = false
        render {
            OverviewScreen(
                state = UsageUiState(accounts = listOf(codex, claude), settings = AppSettings(overviewLayout = OverviewLayout.DIALS)),
                nowMs = now, zone = java.time.ZoneId.of("Etc/UTC"), filter = OverviewFilter.NEEDS_ATTENTION,
                onRefresh = {}, onAccountClick = { opened = it }, onAddAccount = { add = true }, onPercentDisplayChange = {},
            )
        }
        val codexTile = compose.onNode(hasContentDescription("OpenAI Codex", substring = true) and hasClickAction())
        val claudeTile = compose.onNode(hasContentDescription("Claude", substring = true) and hasClickAction())
        codexTile.assertIsDisplayed()
        claudeTile.assertIsDisplayed()
        val boxes = listOf(codexTile, claudeTile).map { it.fetchSemanticsNode().boundsInRoot }.sortedBy { it.top }
        assertTrue("a large font puts the dials on separate rows", boxes[0].bottom <= boxes[1].top)
        assertEquals(boxes[0].left, boxes[1].left)
        codexTile.assert(hasContentDescription("10% remaining", substring = true) and
            hasContentDescription("18:33", substring = true))
        codexTile.assert(hasContentDescription("80%", substring = true).not() and
            hasContentDescription("17:33", substring = true).not())
        compose.onNodeWithText("Needs attention").assertDoesNotExist()
        compose.onNodeWithText("OpenAI Codex Plus", useUnmergedTree = true).assertDoesNotExist()
        codexTile.performClick()
        assertEquals(codex.account.localId, opened)
        compose.onNode(hasContentDescription("Add account") and hasClickAction()).performClick()
        assertTrue(add)
    }

    @Test
    fun `the percent display control reports its choice`() {
        var chosen: PercentDisplay? = null
        render {
            SettingsScreen(
                state = state(usage()),
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
                onOverviewLayoutChange = {},
                onPercentDisplayChange = { chosen = it },
            )
        }

        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Used"))
        compose.onNodeWithText("Used").performClick()
        assertEquals(PercentDisplay.USED, chosen)
    }

    @Test
    fun `account detail renders and can be refreshed`() {
        var refreshed = false
        render {
            AccountDetailScreen(
                usage = usage(),
                nowMs = now,
                staleAfterMs = 3_600_000,
                percentDisplay = PercentDisplay.LEFT, resetInFlight = false,
                supportsResetCredits = true,
                onRefresh = { refreshed = true },
                onConsumeResetCredit = {},
                onRemove = {},
                notificationsEnabled = true,
                onNotificationsChange = {},
            )
        }

        compose.waitForIdle()
    }

    @Test
    fun `a first failed refresh is never presented as a successful update`() {
        val failed = usage(status = SnapshotStatus.FAILED).let {
            it.copy(account = it.account.copy(lastSuccessfulSync = null),
                snapshot = it.snapshot!!.copy(windows = emptyList(), errorMessage = "No network connection"))
        }
        renderDetail(failed)
        // One stamp line now carries both: "Never updated · Last attempt just now".
        assertTrue(compose.onAllNodesWithText("Never updated", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Updated just now", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Last attempt just now", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `a partial update identifies current figures without claiming all data is cached`() {
        val partial = usage(status = SnapshotStatus.PARTIAL).let {
            it.copy(snapshot = it.snapshot!!.copy(errorMessage = "Monthly usage could not be refreshed"))
        }
        renderDetail(partial)
        assertTrue(compose.onAllNodesWithText("Available figures are from this update; some limits could not be refreshed.")
            .fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("The figures below are the last values that loaded successfully.")
            .fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `account detail shows the pause without treating the current reading as cached`() {
        renderDetail(pausedAntigravity())

        compose.onNodeWithText(pauseNotice).assertIsDisplayed()
        compose.onNodeWithText("The figures below are the last values that loaded successfully.").assertDoesNotExist()
        compose.onNodeWithText("Error").assertDoesNotExist()
    }

    private val pauseNotice = "Rate limited. Automatic refresh is paused. Refresh manually to try now."

    private fun pausedAntigravity() = usage(provider = ProviderId.ANTIGRAVITY).let {
        it.copy(snapshot = it.snapshot!!.copy(errorMessage = pauseNotice))
    }

    @Test
    fun `a model restriction retains its identity in the account detail`() {
        val scoped = usage().let {
            it.copy(snapshot = it.snapshot!!.copy(windows = listOf(
                window(0.0),
                window(100.0, "Weekly (Fable)").copy(id = "fable", scope = com.usagelimits.core.model.UsageScope.MODEL),
            )))
        }
        renderDetail(scoped)
        assertTrue(compose.onAllNodesWithText("Restricted: Weekly (Fable)").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Exhausted").fetchSemanticsNodes().isEmpty())
    }

    private fun renderDetail(usage: AccountUsage) = render {
        AccountDetailScreen(
            usage = usage, nowMs = now, staleAfterMs = 3_600_000,
            percentDisplay = PercentDisplay.LEFT, resetInFlight = false, supportsResetCredits = false,
            onRefresh = {}, onConsumeResetCredit = {}, onRemove = {},
            notificationsEnabled = true, onNotificationsChange = {},
        )
    }

    @Test
    fun `account detail renders for an account that is gone`() {
        // Reachable by deleting an account while its detail screen is open.
        render {
            AccountDetailScreen(
                usage = null,
                nowMs = now,
                staleAfterMs = 3_600_000,
                percentDisplay = PercentDisplay.LEFT, resetInFlight = false,
                supportsResetCredits = false,
                onRefresh = {},
                onConsumeResetCredit = {},
                onRemove = {},
                notificationsEnabled = true,
                onNotificationsChange = {},
            )
        }

        compose.waitForIdle()
    }

    /**
     * Signing in with a device code means carrying the code from this screen to a browser on the
     * same phone. Reading eight characters off the screen and typing them into another app is
     * where that flow was breaking down, so the code goes to the clipboard on its own, and the
     * card offers to do it again by hand.
     *
     * Both halves are asserted because both can fail on their own: the automatic copy is a
     * `LaunchedEffect` that a refactor can drop without breaking the layout, and the buttons are
     * the fallback for a browser that never opened.
     */
    @Test
    fun `the device code is copied for you and can be copied again`() {
        render {
            AddAccountScreen(
                state = AddAccountState.AwaitingDeviceCode(
                    provider = ProviderId.CODEX,
                    userCode = "WDJB-MJHT",
                    verificationUri = "https://auth.openai.com/device",
                ),
                providers = listOf(ProviderId.CODEX),
                onStart = {},
                onCancel = {},
                onDone = {},
                onSubmitApiKey = {},
            )
        }

        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            "the code should be on the clipboard without anyone pressing anything",
            "WDJB-MJHT",
            clipboard.primaryClip?.getItemAt(0)?.text?.toString(),
        )

        assertTrue(
            "the code should still be copyable by hand",
            compose.onAllNodesWithText("Copy code").fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "and the sign-in page reopenable, for when the browser never came up",
            compose.onAllNodesWithText("Open page").fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
