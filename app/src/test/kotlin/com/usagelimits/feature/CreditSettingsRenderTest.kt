package com.usagelimits.feature

import androidx.compose.runtime.getValue
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

        val layoutTop = compose.onNodeWithText("Layout").fetchSemanticsNode().boundsInRoot.top
        val showFirstTop = compose.onNodeWithText("Show first").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Layout precedes Show first", layoutTop < showFirstTop)
        val usageTop = compose.onNodeWithText("Claude Pro").fetchSemanticsNode().boundsInRoot.top
        val creditTop = compose.onNodeWithText("Credits").fetchSemanticsNode().boundsInRoot.top
        assertTrue("usage providers lead by default", usageTop < creditTop)
        compose.onNodeWithText("DeepSeek").assertExists()
        val lowBalance = hasContentDescription("Credits · 3 balances, Low balance: DeepSeek \$3.18")
        compose.onNode(lowBalance).assertDoesNotExist()

        compose.onNode(hasText("Compact")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Compact").assertExists()
        compose.onNode(lowBalance).assertExists()

        compose.onNode(
            hasText("Credit accounts") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
        ).performClick()
        compose.waitForIdle()
        val reorderedUsageTop = compose.onNodeWithText("Claude Pro").fetchSemanticsNode().boundsInRoot.top
        val reorderedCreditTop = compose.onNode(lowBalance).fetchSemanticsNode().boundsInRoot.top
        assertTrue("credit accounts lead when selected", reorderedCreditTop < reorderedUsageTop)

        compose.onNodeWithContentDescription("Show credits").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Show first").assertDoesNotExist()
        compose.onNodeWithText("Compact").assertDoesNotExist()
        compose.onNodeWithText("Manage credit accounts").assertExists()
    }
}
