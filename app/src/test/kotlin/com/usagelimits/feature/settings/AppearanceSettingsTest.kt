package com.usagelimits.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.ui.theme.UsageLimitsTheme
import com.usagelimits.feature.UsageUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearanceSettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `each theme is a mutually exclusive native choice and reports its exact value`() {
        val settings = mutableStateOf(AppSettings())
        val changes = mutableListOf<AppTheme>()
        compose.setContent {
            UsageLimitsTheme(appTheme = settings.value.appTheme) {
                AppearanceSettings(settings.value) { theme ->
                    changes += theme
                    settings.value = settings.value.copy(appTheme = theme)
                }
            }
        }

        compose.onNodeWithText("Pure black").assertIsSelected()
        val choices = listOf(
            "Warm dark" to AppTheme.WARM_DARK,
            "Cool graphite" to AppTheme.COOL_GRAPHITE,
            "Light paper" to AppTheme.LIGHT_PAPER,
            "Pure black" to AppTheme.PURE_BLACK,
        )
        choices.forEach { (name, theme) ->
            compose.onNodeWithText(name)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .performClick()
                .assertIsSelected()
            choices.filter { it.second != theme }.forEach { (other, _) ->
                compose.onNodeWithText(other).assertIsNotSelected()
            }
            assertEquals(theme, changes.last())
        }
    }

    @Test
    fun `preview follows the display mode with localized figures and their side words`() {
        val settings = mutableStateOf(AppSettings())
        compose.setContent {
            UsageLimitsTheme {
                AppearanceSettings(settings.value, onAppThemeChange = {})
            }
        }
        compose.onNodeWithText("Claude Pro", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("18%", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("left", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("1h 18m", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("$12.40", substring = true, useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Claude Pro") and hasText("18%") and hasText("left")
            and hasText("1h 18m", substring = true) and hasText("$12.40", substring = true)).assertExists()

        compose.runOnIdle { settings.value = settings.value.copy(percentDisplay = PercentDisplay.USED) }
        compose.onNodeWithText("82%", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("used", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("18%", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `Settings exposes Appearance first and forwards the selected theme`() {
        var chosen: AppTheme? = null
        compose.setContent {
            UsageLimitsTheme {
                SettingsScreen(
                    state = UsageUiState(), onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {},
                    onNotifyExhausted = {}, onNotifyResetCredit = {}, onNotifyAuthExpired = {},
                    onNotifyResetApproaching = {}, onNotifyCreditExpiring = {}, onShowTier = {}, onOverviewLayoutChange = {},
                    onPercentDisplayChange = {}, onNotificationPermissionHandled = {}, onAppThemeChange = { chosen = it },
                )
            }
        }
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithText("Light paper").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(AppTheme.LIGHT_PAPER, chosen)
    }

    @Test
    fun `a theme can be focused and selected from the keyboard`() {
        var chosen: AppTheme? = null
        compose.setContent {
            UsageLimitsTheme {
                AppearanceSettings(AppSettings(), onAppThemeChange = { chosen = it })
            }
        }
        compose.onNodeWithText("Light paper")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        assertEquals(AppTheme.LIGHT_PAPER, chosen)
    }

    @Test
    @Config(qualifiers = "de")
    fun `German appearance keeps its localized values and theme names`() {
        compose.setContent {
            UsageLimitsTheme {
                AppearanceSettings(AppSettings(), onAppThemeChange = {})
            }
        }
        compose.onNodeWithText("Reines Schwarz").assertIsSelected()
        compose.onNodeWithText("Warmes Dunkel").assertExists()
        compose.onNodeWithText("Kühles Graphit").assertExists()
        compose.onNodeWithText("Helles Papier").assertExists()
        compose.onNodeWithText("18 %", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("übrig", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("1 Std. 18 Min.", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("12,40", substring = true, useUnmergedTree = true).assertExists()
    }
}
