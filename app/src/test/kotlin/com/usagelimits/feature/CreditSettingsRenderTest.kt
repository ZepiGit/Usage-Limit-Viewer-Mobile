package com.usagelimits.feature

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.usagelimits.R
import com.usagelimits.feature.settings.OverviewSettings
import org.junit.Assert.assertEquals
import java.util.Locale
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.OverviewCreditLayout
import com.usagelimits.core.settings.OverviewShowFirst
import com.usagelimits.feature.settings.SettingsScreen
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h2400dp-xhdpi")
class CreditSettingsRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `enabling credits grows below the switch in English and German`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        var locale by mutableStateOf(Locale.ENGLISH)
        var settings by mutableStateOf(AppSettings(overviewShowCredits = false))
        compose.setContent {
            val configuration = Configuration(base.resources.configuration).apply { setLocale(locale) }
            val context = base.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
                UsageLimitsTheme {
                    OverviewSettings(settings, {}, {}, {}, {}, { settings = settings.copy(overviewShowCredits = it) }, null)
                }
            }
        }
        for (language in listOf(Locale.ENGLISH, Locale.GERMAN)) {
            compose.runOnIdle { locale = language; settings = settings.copy(overviewShowCredits = false) }
            val resources = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(language) }).resources
            val toggle = compose.onNodeWithContentDescription(resources.getString(R.string.settings_show_credits_title))
            val before = toggle.fetchSemanticsNode().boundsInRoot
            toggle.performClick()
            val after = toggle.fetchSemanticsNode().boundsInRoot
            assertEquals("$language switch stays at the tapped bounds", before, after)
            val normal = compose.onNodeWithText(resources.getString(R.string.settings_credit_normal)).fetchSemanticsNode().boundsInRoot
            val showFirst = compose.onNodeWithText(resources.getString(R.string.settings_overview_show_first_title)).fetchSemanticsNode().boundsInRoot
            assertTrue("$language dependent controls grow below the switch", normal.top >= after.bottom && showFirst.top > normal.top)
            compose.onNodeWithText("DeepSeek").assertIsDisplayed()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w320dp-h2400dp-xhdpi", fontScale = 1.6f)
    fun `large Show first radio choices wrap without losing either action`() {
        var settings by mutableStateOf(AppSettings())
        compose.setContent {
            UsageLimitsTheme {
                Column(Modifier.padding(16.dp)) {
                    OverviewSettings(settings, {}, {}, {}, { settings = settings.copy(overviewShowFirst = it) }, {}, null)
                }
            }
        }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val usage = compose.onNode(hasText("Usage providers") and radio)
        val credits = compose.onNode(hasText("Credit accounts") and radio)
        usage.assertIsDisplayed().assertIsSelected()
        credits.assertIsDisplayed()
        val usageBox = usage.fetchSemanticsNode().boundsInRoot
        val creditsBox = credits.fetchSemanticsNode().boundsInRoot
        assertTrue("the second full label wraps below the first", creditsBox.top >= usageBox.bottom)
        for (label in listOf("Usage providers", "Credit accounts")) {
            val layouts = mutableListOf<TextLayoutResult>()
            val text = compose.onNode(hasText(label) and hasAnyAncestor(radio), useUnmergedTree = true).assertIsDisplayed()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val bounds = text.fetchSemanticsNode().boundsInRoot
            val metrics = "size=${layout.size}, bounds=$bounds, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                "maxIntrinsic=${layout.multiParagraph.intrinsics.maxIntrinsicWidth}, constraints=${layout.layoutInput.constraints}, " +
                "fontScale=${layout.layoutInput.density.fontScale}"
            assertEquals("$label uses the requested large text: $metrics", 1.6f, layout.layoutInput.density.fontScale, 0f)
            assertEquals("$label has its full visible width: $metrics", layout.size.width.toFloat(), bounds.width, 0f)
            assertEquals("$label has its full visible height: $metrics", layout.size.height.toFloat(), bounds.height, 0f)
            assertTrue("$label is not clipped vertically: $metrics", !layout.didOverflowHeight)
            assertEquals("$label retains its ending: $metrics", label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            // String semantics can report the unused paragraph width as overflow, unlike the actual lines.
            for (line in 0 until layout.lineCount) {
                val lineMetrics = "line $line=${layout.getLineLeft(line)},${layout.getLineTop(line)}.." +
                    "${layout.getLineRight(line)},${layout.getLineBottom(line)}; $metrics"
                assertTrue("$label is not ellipsized: $lineMetrics", !layout.isLineEllipsized(line))
                assertTrue("$label fits horizontally: $lineMetrics",
                    layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= bounds.width)
                assertTrue("$label fits vertically: $lineMetrics",
                    layout.getLineTop(line) >= 0f && layout.getLineBottom(line) <= bounds.height)
            }
        }
        credits.performClick().assertIsSelected()
        assertEquals(OverviewShowFirst.CREDIT_ACCOUNTS, settings.overviewShowFirst)
        usage.performClick().assertIsSelected()
        assertEquals(OverviewShowFirst.USAGE_PROVIDERS, settings.overviewShowFirst)
    }

    @Test
    fun `settings preview follows layout and show first and hiding credits preserves manage`() {
        var settings by mutableStateOf(AppSettings())
        var manageRequests = 0
        compose.setContent {
            UsageLimitsTheme {
                SettingsScreen(
                    state = UsageUiState(settings = settings),
                    onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {},
                    onNotifyExhausted = {}, onNotifyResetCredit = {}, onNotifyAuthExpired = {},
                    onNotifyResetApproaching = {}, onNotifyCreditExpiring = {}, onShowTier = {},
                    onOverviewLayoutChange = {}, onPercentDisplayChange = {},
                    onNotificationPermissionHandled = {}, onAppThemeChange = {},
                    onOverviewCreditLayoutChange = { settings = settings.copy(overviewCreditLayout = it) },
                    onOverviewShowFirstChange = { settings = settings.copy(overviewShowFirst = it) },
                    onOverviewShowCreditsChange = { settings = settings.copy(overviewShowCredits = it) },
                    onManageCredits = { manageRequests++ },
                )
            }
        }
        compose.waitForIdle()
        // The preview follows the controls, so scroll to its actual credit content rather than
        // assume the Appearance section or the Overview card fits the viewport.
        compose.onNodeWithText("DeepSeek").performScrollTo()
        compose.waitForIdle()

        val layoutTop = compose.onNodeWithText("Layout").fetchSemanticsNode().boundsInRoot.top
        val showFirstTop = compose.onNodeWithText("Show first").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Layout precedes Show first", layoutTop < showFirstTop)
        val usageTop = compose.onNodeWithText("OpenAI Codex Plus").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        val creditTop = compose.onNodeWithText("Credits").fetchSemanticsNode().boundsInRoot.top
        assertTrue("usage providers lead by default", usageTop < creditTop)
        compose.onNodeWithText("DeepSeek").assertIsDisplayed()
        val lowBalance = hasContentDescription("Credits · 3 balances, Low balance: DeepSeek \$3.18")
        compose.onNode(lowBalance).assertDoesNotExist()

        compose.onNode(hasText("Compact")).performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Compact").assertIsSelected()
        compose.onNode(lowBalance).performScrollTo().assertIsDisplayed()

        compose.onNode(
            hasText("Credit accounts") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
        ).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        compose.waitForIdle()
        compose.onNodeWithText("OpenAI Codex Plus").performScrollTo().assertIsDisplayed()
        val reorderedUsageTop = compose.onNodeWithText("OpenAI Codex Plus").fetchSemanticsNode().boundsInRoot.top
        val reorderedCreditTop = compose.onNode(lowBalance).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue("credit accounts lead when selected", reorderedCreditTop < reorderedUsageTop)

        compose.onNodeWithContentDescription("Show credits").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Show first").assertDoesNotExist()
        compose.onNode(lowBalance).assertDoesNotExist()
        compose.onNodeWithText("DeepSeek").assertDoesNotExist()
        compose.onNodeWithText("Compact").assertDoesNotExist()
        compose.onNodeWithText("Manage credit accounts").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, manageRequests)
    }
}
