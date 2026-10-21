package com.usagelimits.feature

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import com.usagelimits.core.model.CreditStatus
import com.usagelimits.core.settings.AppSettings
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
import java.math.BigDecimal

/** The credit screens, actually drawn: overview section, add sheet, detail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreditScreensRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_757_000_000_000L

    private fun entry(id: String = "credit-fal-1", amount: String = "24.50", error: String? = null) = CreditEntry(
        CreditAccount(id, CreditProviderId.FAL, "My fal", "credit:$id", createdAt = now),
        CreditSnapshot(
            id, listOf(CreditBalance("USD", BigDecimal(amount), total = BigDecimal(100))), fetchedAt = now,
            status = if (error == null) CreditStatus.OK else CreditStatus.FAILED, error = error,
        ),
    )

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { UsageLimitsTheme { content() } }
        compose.waitForIdle()
    }

    @Test fun `overview shows the credits section with an amount and the add row`() {
        render {
            OverviewScreen(
                state = UsageUiState(settings = AppSettings(), credits = listOf(entry(error = "The key was rejected (HTTP 401)."))),
                nowMs = now, onRefresh = {}, onAccountClick = {}, onAddAccount = {}, onReorder = {},
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
