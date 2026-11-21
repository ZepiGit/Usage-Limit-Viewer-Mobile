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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$label is not clipped", !layouts.single().hasVisualOverflow)
        }
        credits.performClick().assertIsSelected()
        assertEquals(OverviewShowFirst.CREDIT_ACCOUNTS, settings.overviewShowFirst)
        usage.performClick().assertIsSelected()
        assertEquals(OverviewShowFirst.USAGE_PROVIDERS, settings.overviewShowFirst)
    }

    @Test
    fun `settings preview follows layout and show first and hiding credits preserves manage`() {
        var settings by mutableStateOf(AppSettings())
        compose.setContent {
            UsageLimitsTheme {
                SettingsScreen(
                    state = UsageUiState(settings = settings),
                    onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {},
                    onNotifyExhausted = {}, onNotifyResetCredit = {}, onNotifyAuthExpired = {},
                    onNotifyResetApproaching = {}, onNotifyCreditExpiring = {}, onShowTier = {},
                    onOverviewLayoutChange = {}, onPercentDisplayChange = {},
                    onNotificationPermissionHandled = {},
                    onOverviewCreditLayoutChange = { settings = settings.copy(overviewCreditLayout = it) },
                    onOverviewShowFirstChange = { settings = settings.copy(overviewShowFirst = it) },
                    onOverviewShowCreditsChange = { settings = settings.copy(overviewShowCredits = it) },
                    onManageCredits = {},
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
        val usageTop = compose.onNodeWithText("Claude Pro").fetchSemanticsNode().boundsInRoot.top
        val creditTop = compose.onNodeWithText("Credits").fetchSemanticsNode().boundsInRoot.top
        assertTrue("usage providers lead by default", usageTop < creditTop)
        compose.onNodeWithText("DeepSeek").assertExists()
        val lowBalance = hasContentDescription("Credits · 3 balances, Low balance: DeepSeek \$3.18")
        compose.onNode(lowBalance).assertDoesNotExist()

        compose.onNode(hasText("Compact")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Compact").assertExists()
        compose.onNode(lowBalance).assertExists()

        compose.onNode(
            hasText("Credit accounts") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
        ).performScrollTo().performClick()
        compose.waitForIdle()
        val reorderedUsageTop = compose.onNodeWithText("Claude Pro").fetchSemanticsNode().boundsInRoot.top
        val reorderedCreditTop = compose.onNode(lowBalance).fetchSemanticsNode().boundsInRoot.top
        assertTrue("credit accounts lead when selected", reorderedCreditTop < reorderedUsageTop)

        compose.onNodeWithContentDescription("Show credits").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Show first").assertDoesNotExist()
        compose.onNode(lowBalance).assertDoesNotExist()
        compose.onNodeWithText("DeepSeek").assertDoesNotExist()
        compose.onNodeWithText("Compact").assertDoesNotExist()
        compose.onNodeWithText("Manage credit accounts").assertExists()
    }
}
