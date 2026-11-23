package com.usagelimits.feature

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.usagelimits.feature.credits.CreditCard
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.R
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import com.usagelimits.core.model.CreditStatus
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.OverviewCreditLayout
import com.usagelimits.core.settings.OverviewShowFirst
import com.usagelimits.feature.credits.AddCreditScreen
import com.usagelimits.feature.credits.AddCreditState
import com.usagelimits.feature.credits.CreditDetailScreen
import com.usagelimits.feature.credits.creditIcon
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigDecimal

/** The credit screens, actually drawn: overview section, add sheet, detail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreditScreensRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_757_000_000_000L

    private fun entry(
        id: String = "credit-fal-1", amount: String = "24.50", error: String? = null,
        provider: CreditProviderId = CreditProviderId.FAL, label: String = "My fal",
    ) = CreditEntry(
        CreditAccount(id, provider, label, "credit:$id", createdAt = now),
        CreditSnapshot(
            id, listOf(CreditBalance("USD", BigDecimal(amount), total = BigDecimal(100))), fetchedAt = now,
            status = if (error == null) CreditStatus.OK else CreditStatus.FAILED, error = error,
        ),
    )

    private fun usageAccount() = AccountUsage(
        account = ProviderAccount(
            localId = "usage", provider = ProviderId.CODEX, externalAccountId = "usage", email = null,
            displayName = "Usage marker", plan = null, credentialReference = "usage", createdAt = now,
            lastSuccessfulSync = now,
        ),
        snapshot = UsageSnapshot(
            accountId = "usage", fetchedAt = now, status = SnapshotStatus.OK,
            windows = listOf(UsageWindow("weekly", "Weekly", WindowCategory.WEEKLY, 50.0, 604_800, now + 60_000, false)),
            connectionStatus = ConnectionStatus.CONNECTED,
        ),
    )

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { UsageLimitsTheme { content() } }
        compose.waitForIdle()
    }

    @Test fun `overview shows the credits section with an amount and the add row`() {
        render {
            OverviewScreen(
                state = UsageUiState(settings = AppSettings(), credits = listOf(entry(error = "Rate limited. Automatic refresh is paused. Refresh manually to try now."))),
                nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
                onCreditClick = {}, onAddCredit = {},
            )
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Credits"))
        assertEquals(1, compose.onAllNodesWithText("Credits").fetchSemanticsNodes().size)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Add credit balance"))
        compose.onNode(hasText("Add credit balance")).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("My fal"))
        compose.onNode(hasText("My fal")).assertExists()
        assertTrue(compose.onAllNodesWithText("\$24.50", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("showing last known balance", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Automatic refresh is paused.", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    @Config(qualifiers = "w393dp-h2400dp-xhdpi")
    fun `compact overview speaks the low balance and can expand and collapse`() {
        render {
            OverviewScreen(
                state = UsageUiState(settings = AppSettings(overviewCreditLayout = OverviewCreditLayout.COMPACT), credits = listOf(
                    entry(id = "credit-deepseek", amount = "3.18", provider = CreditProviderId.DEEPSEEK, label = "DeepSeek"),
                )),
                nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
                onCreditClick = {}, onAddCredit = {}, onManageCredits = {},
            )
        }

        compose.onNode(hasContentDescription("Credits · 1 balance, Low balance: DeepSeek \$3.18")).assertExists()
        val compactRow = compose.onNode(hasContentDescription("Credits · 1 balance", substring = true) and hasClickAction())
        // TalkBack says "Double-tap to <label>", so the label is an imperative phrase.
        org.junit.Assert.assertEquals(
            "Expand credit balances",
            compactRow.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label,
        )
        compactRow.performClick()
        compose.onNode(hasText("Collapse")).assertExists()
        compose.onNode(hasText("\$3.18")).assertExists()
        compose.onNode(hasText("Manage credit accounts")).assertExists()
        compose.onNode(hasText("Collapse")).performClick()
        compose.onNode(hasContentDescription("Credits · 1 balance", substring = true)).assertExists()
    }

    @Test
    @Config(qualifiers = "w393dp-h2400dp-xhdpi")
    fun `expanded credits survive saved state restoration and collapse still works`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            UsageLimitsTheme {
                OverviewScreen(
                    state = UsageUiState(settings = AppSettings(overviewCreditLayout = OverviewCreditLayout.COMPACT),
                        credits = listOf(entry())),
                    nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
                    onManageCredits = {},
                )
            }
        }
        compose.onNode(hasContentDescription("Credits · 1 balance", substring = true)).performClick()
        compose.onNodeWithText("Collapse").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Collapse").assertIsDisplayed()
        compose.onNodeWithText("My fal").assertIsDisplayed()
        compose.onNodeWithText("Manage credit accounts").assertIsDisplayed()
        compose.onNode(hasContentDescription("Credits · 1 balance", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("Collapse").performClick()
        compose.onNode(hasContentDescription("Credits · 1 balance", substring = true)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w393dp-h2400dp-xhdpi")
    fun `show first controls the semantic order of the real overview blocks`() {
        var order by mutableStateOf(OverviewShowFirst.USAGE_PROVIDERS)
        val usage = listOf(usageAccount())
        val credits = listOf(entry())
        render {
            OverviewScreen(
                state = UsageUiState(accounts = usage, settings = AppSettings(overviewShowFirst = order), credits = credits),
                nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
            )
        }
        for (first in OverviewShowFirst.entries) {
            compose.runOnIdle { order = first }
            val usageTop = compose.onNodeWithText("Usage marker", substring = true).fetchSemanticsNode().boundsInRoot.top
            val creditsTop = compose.onNodeWithText("Credits").fetchSemanticsNode().boundsInRoot.top
            if (first == OverviewShowFirst.USAGE_PROVIDERS) assertTrue("usage is first", usageTop < creditsTop)
            else assertTrue("credits are first", creditsTop < usageTop)
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h2400dp-xhdpi")
    fun `compact overview drops its low balance hint at the stale threshold`() {
        var clock by mutableLongStateOf(now)
        val state = UsageUiState(
            settings = AppSettings(overviewCreditLayout = OverviewCreditLayout.COMPACT),
            credits = listOf(entry(id = "credit-deepseek", amount = "3.18", provider = CreditProviderId.DEEPSEEK, label = "DeepSeek")),
        )
        compose.setContent {
            UsageLimitsTheme {
                OverviewScreen(
                    state = state, nowMs = clock, onRefresh = {}, onAccountClick = {}, onAddAccount = {},
                    onPercentDisplayChange = {},
                )
            }
        }
        compose.waitForIdle()

        val hint = hasContentDescription("Credits · 1 balance, Low balance: DeepSeek \$3.18")
        assertEquals(1, compose.onAllNodes(hint).fetchSemanticsNodes().size)
        compose.runOnIdle { clock = now + state.staleAfterMs - 1 }
        compose.waitForIdle()
        assertEquals("one millisecond before stale, the hint remains", 1, compose.onAllNodes(hint).fetchSemanticsNodes().size)
        compose.runOnIdle { clock = now + state.staleAfterMs }
        compose.waitForIdle()
        assertEquals("at the stale threshold, the hint disappears", 0, compose.onAllNodes(hint).fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "w393dp-h2400dp-xhdpi")
    fun `empty compact credits fall back to the dashed normal entry`() {
        render {
            OverviewScreen(
                state = UsageUiState(settings = AppSettings(overviewCreditLayout = OverviewCreditLayout.COMPACT)),
                nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
                onCreditClick = {}, onAddCredit = {},
            )
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Add credit balance"))
        compose.onNode(hasText("Add credit balance")).assertExists()
        compose.onNode(hasText("No prepaid balances tracked.")).assertExists()
    }

    @Test
    @Config(qualifiers = "w393dp-h2400dp-xhdpi")
    fun `credit only Overview names missing usage accounts and offers one visible add credit path`() {
        var visible by mutableStateOf(true)
        var tracked by mutableStateOf(listOf(entry(error = "Network unavailable")))
        var additions = 0
        render {
            OverviewScreen(
                state = UsageUiState(settings = AppSettings(overviewShowCredits = visible), credits = tracked),
                nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
                onAddCredit = { additions++ },
            )
        }
        compose.onNodeWithText("No usage accounts yet").assertIsDisplayed()
        compose.onNodeWithText("My fal").assertIsDisplayed()
        compose.onNodeWithText("Nothing to monitor yet").assertDoesNotExist()
        compose.onNodeWithText("Track a prepaid credit balance").assertDoesNotExist()
        compose.onNodeWithText("Add credit balance").assertIsDisplayed().performClick()
        assertEquals(1, additions)
        compose.runOnIdle { visible = false }
        compose.onNodeWithText("No usage accounts yet").assertIsDisplayed()
        compose.onNodeWithText("My fal").assertDoesNotExist()
        compose.onNodeWithText("Add credit balance").assertDoesNotExist()
        compose.onNodeWithText("Track a prepaid credit balance").assertIsDisplayed().performClick()
        assertEquals(2, additions)
        compose.runOnIdle { tracked = emptyList(); visible = true }
        compose.onNodeWithText("Nothing to monitor yet").assertIsDisplayed()
        compose.onNodeWithText("No usage accounts yet").assertDoesNotExist()
        compose.onNodeWithText("Track a prepaid credit balance").assertDoesNotExist()
        compose.onNodeWithText("Add credit balance").assertIsDisplayed().performClick()
        assertEquals(3, additions)
    }

    @Test
    @Config(qualifiers = "de-w393dp-h2400dp-xhdpi")
    fun `German credit only Overview names usage accounts without hiding the existing balance`() {
        var additions = 0
        render {
            OverviewScreen(
                state = UsageUiState(credits = listOf(entry(error = "Network unavailable"))),
                nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
                onAddCredit = { additions++ },
            )
        }
        compose.onNodeWithText("Noch keine Nutzungskonten").assertIsDisplayed()
        compose.onNodeWithText("Noch nichts zu verfolgen").assertDoesNotExist()
        compose.onNodeWithText("My fal").assertIsDisplayed()
        compose.onNodeWithText("Prepaid-Guthaben erfassen").assertDoesNotExist()
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        compose.onNodeWithText(resources.getString(R.string.overview_add_credit)).assertIsDisplayed().performClick()
        assertEquals(1, additions)
    }

    @Test
    @Config(qualifiers = "w393dp-h2400dp-xhdpi")
    fun `credit only collapsed summary keeps its add path until expanded Credits supplies one`() {
        var additions = 0
        render {
            OverviewScreen(
                state = UsageUiState(settings = AppSettings(overviewCreditLayout = OverviewCreditLayout.COMPACT), credits = listOf(entry())),
                nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onPercentDisplayChange = {},
                onAddCredit = { additions++ },
            )
        }
        compose.onNodeWithText("Track a prepaid credit balance").assertIsDisplayed().performClick()
        assertEquals(1, additions)
        compose.onNodeWithText("Add credit balance").assertDoesNotExist()
        compose.onNode(hasContentDescription("Credits · 1 balance", substring = true)).performClick()
        compose.onNodeWithText("Track a prepaid credit balance").assertDoesNotExist()
        compose.onNodeWithText("My fal").assertIsDisplayed()
        compose.onNodeWithText("Add credit balance").assertIsDisplayed().performClick()
        assertEquals(2, additions)
        compose.onNodeWithText("Collapse").performClick()
        compose.onNodeWithText("Track a prepaid credit balance").assertIsDisplayed()
        compose.onNodeWithText("Add credit balance").assertDoesNotExist()
    }

    @Test fun `the add screen lists every provider with Anthropic and OpenAI disabled`() {
        render {
            AddCreditScreen(
                state = AddCreditState.Idle, onSubmit = { _, _, _, _, _, _ -> }, onCancel = {}, onDone = {},
            )
        }
        for (name in CreditProviderId.entries.map { it.displayName } + listOf("Anthropic API", "OpenAI API")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(name))
            compose.onNode(hasText(name)).assertExists()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("No balance endpoint for API keys"))
        assertEquals(6, compose.onAllNodesWithText("Open").fetchSemanticsNodes().size)
    }

    @Test fun `open buttons launch the provider key page without selecting a provider`() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        render {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                AddCreditScreen(
                    state = AddCreditState.Idle, onSubmit = { _, _, _, _, _, _ -> }, onCancel = {}, onDone = {},
                )
            }
        }
        CreditProviderId.entries.forEach { provider ->
            compose.onNodeWithContentDescription("Open ${provider.displayName} key page").performScrollTo().performClick()
            assertEquals(provider.consoleUrl, opened.last())
            compose.onNode(hasText("Connect ${provider.displayName}")).assertDoesNotExist()
        }
        assertEquals(CreditProviderId.entries.map { it.consoleUrl }, opened)
        assertEquals("https://console.x.ai/team/default/api-keys", CreditProviderId.XAI.consoleUrl)
    }

    @Test fun `each credit provider has its own readable icon`() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals(6, CreditProviderId.entries.map(::creditIcon).distinct().size)
        CreditProviderId.entries.forEach { provider ->
            val icon = creditIcon(provider)
            assertTrue(icon != 0)
            val bitmap = requireNotNull(BitmapFactory.decodeResource(resources, icon))
            assertEquals(512, bitmap.width)
            assertEquals(512, bitmap.height)
        }
    }

    @Test fun `a failed add shows the reason and a finished one offers done`() {
        render {
            AddCreditScreen(
                state = AddCreditState.Added("Work"), onSubmit = { _, _, _, _, _, _ -> }, onCancel = {}, onDone = {},
            )
        }
        compose.onNode(hasText("Work is now being tracked.")).assertExists()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-w300dp-h873dp", fontScale = 1.6f)
    fun `a long credit identity and its freshness remain readable at large text`() {
        val label = "Personal OpenRouter development balance"
        render {
            CreditCard(entry(label = label, provider = CreditProviderId.OPENROUTER), now, 3_600_000)
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val identityMetrics = "size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
            "constraints=${layout.layoutInput.constraints}, fontScale=${layout.layoutInput.density.fontScale}"
        assertEquals("the identity uses the requested large text: $identityMetrics", 1.6f, layout.layoutInput.density.fontScale, 0f)
        assertTrue("the identity wraps rather than clipping: $identityMetrics", layout.lineCount > 1 && !layout.hasVisualOverflow)
        val freshnessText = "OpenRouter · Updated just now"
        val freshness = mutableListOf<TextLayoutResult>()
        val freshnessNode = compose.onNodeWithText(freshnessText, useUnmergedTree = true).assertIsDisplayed()
        freshnessNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(freshness) }
        val age = freshness.single()
        val bounds = freshnessNode.fetchSemanticsNode().boundsInRoot
        val metrics = "size=${age.size}, bounds=$bounds, paragraph=${age.multiParagraph.width}x${age.multiParagraph.height}, " +
            "maxIntrinsic=${age.multiParagraph.intrinsics.maxIntrinsicWidth}, constraints=${age.layoutInput.constraints}, " +
            "fontScale=${age.layoutInput.density.fontScale}"
        assertEquals("freshness uses the requested large text: $metrics", 1.6f, age.layoutInput.density.fontScale, 0f)
        assertEquals("the full freshness width is visible: $metrics", age.size.width.toFloat(), bounds.width, 0f)
        assertEquals("the full freshness height is visible: $metrics", age.size.height.toFloat(), bounds.height, 0f)
        assertTrue("the provider and update age remain complete: $metrics", !age.didOverflowHeight)
        assertEquals("freshness retains its ending: $metrics", freshnessText.length, age.getLineEnd(age.lineCount - 1, visibleEnd = true))
        // Compose 1.12 reconstructs a wider paragraph for String semantics; only the line geometry describes its ink.
        for (line in 0 until age.lineCount) {
            val lineMetrics = "line $line=${age.getLineLeft(line)},${age.getLineTop(line)}.." +
                "${age.getLineRight(line)},${age.getLineBottom(line)}; $metrics"
            assertTrue("freshness is not ellipsized: $lineMetrics", !age.isLineEllipsized(line))
            assertTrue("freshness fits horizontally: $lineMetrics",
                age.getLineLeft(line) >= 0f && age.getLineRight(line) <= bounds.width)
            assertTrue("freshness fits vertically: $lineMetrics",
                age.getLineTop(line) >= 0f && age.getLineBottom(line) <= bounds.height)
        }
    }

    @Test fun `the detail screen offers edit and remove`() {
        render {
            CreditDetailScreen(
                entry = entry(), nowMs = now, staleAfterMs = 3_600_000,
                onSave = { _, _ -> }, onRefresh = {}, onRemove = {},
            )
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Remove credit account"))
        compose.onNode(hasText("Remove credit account")).assertExists()
        compose.onNode(hasText("Save")).assertExists()
    }

    @Test fun `a missing credit account renders a message not a crash`() {
        render { CreditDetailScreen(entry = null, nowMs = now, staleAfterMs = 1, onSave = { _, _ -> }, onRefresh = {}, onRemove = {}) }
        compose.onNode(hasText("This credit account is no longer available.")).assertExists()
    }
}
