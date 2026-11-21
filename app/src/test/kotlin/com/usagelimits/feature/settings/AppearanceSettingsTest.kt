package com.usagelimits.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.core.settings.TextFont
import com.usagelimits.feature.waitDrained
import org.junit.Assert.assertNotEquals
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
    fun `each picker updates its own preview face and uses the native weights`() {
        val settings = mutableStateOf(AppSettings())
        compose.setContent {
            val state = settings.value
            UsageLimitsTheme(textFont = state.textFont, figureFont = state.figureFont) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AppearanceSettings(state,
                        onTextFontChange = { settings.value = settings.value.copy(textFont = it) },
                        onFigureFontChange = { settings.value = settings.value.copy(figureFont = it) },
                        onAppThemeChange = {},
                    )
                }
            }
        }
        val defaultText = styleOf("Claude Pro")
        val defaultFigure = styleOf("18%")
        assertEquals(FontFamily.SansSerif, defaultText.fontFamily)
        assertEquals(FontWeight.Medium, defaultText.fontWeight)
        assertEquals(FontWeight.Medium, defaultFigure.fontWeight)

        compose.onNodeWithText("Figtree")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Roboto Mono").assertIsSelected()
        assertEquals(defaultFigure.fontFamily, styleOf("18%").fontFamily)
        assertNotEquals(defaultText.fontFamily, styleOf("Claude Pro").fontFamily)
        assertEquals(FontWeight(560), styleOf("Claude Pro").fontWeight)
        assertEquals(FontWeight(450), styleOf("Text font").fontWeight)

        compose.onNodeWithText("IBM Plex Mono")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Figtree").assertIsSelected()
        assertNotEquals(defaultFigure.fontFamily, styleOf("18%").fontFamily)
        assertEquals(FontSynthesis.None, styleOf("18%").fontSynthesis)
        assertEquals(FontWeight(560), styleOf("Claude Pro").fontWeight)

        compose.onNodeWithText("IBM Plex Sans").performScrollTo().performClick().assertIsSelected()
        assertEquals(FontWeight.Medium, styleOf("Claude Pro").fontWeight)
        assertEquals(FontWeight.Normal, styleOf("Text font").fontWeight)
        compose.onNodeWithText("Roboto Flex").performScrollTo().performClick().assertIsSelected()
        assertEquals(FontWeight(560), styleOf("Claude Pro").fontWeight)
        assertEquals(FontWeight(450), styleOf("Text font").fontWeight)
    }

    @Test
    fun `the provider chooser title inherits the chosen text face`() {
        compose.setContent {
            UsageLimitsTheme(textFont = TextFont.FIGTREE) { ProviderIconSettings(emptyMap()) { _, _ -> } }
        }
        compose.onNodeWithText("Claude", useUnmergedTree = true).performClick()
        val title = styleOf("Claude icon")
        assertEquals(FontWeight(450), title.fontWeight)
        assertNotEquals(FontFamily.SansSerif, title.fontFamily)
        compose.onNodeWithText("Done").performClick()
    }

    @Test
    fun `the font licence view shows every complete notice shipped in the app`() {
        compose.setContent {
            UsageLimitsTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AppearanceSettings(AppSettings(), onAppThemeChange = {})
                }
            }
        }
        compose.onNodeWithText("Font licences").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Done").assertIsDisplayed()
        val documents = compose.onNode(hasScrollToIndexAction(), useUnmergedTree = true)
        try {
            compose.waitDrained { compose.onAllNodes(hasScrollToIndexAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError(compose.onNode(isDialog(), useUnmergedTree = true).printToString(maxDepth = 4), error)
        }
        val families = listOf("Roboto Flex", "IBM Plex Sans", "Figtree", "Roboto Mono", "JetBrains Mono", "IBM Plex Mono")
        for ((index, family) in families.withIndex()) {
            documents.performScrollToIndex(index)
            val heading = hasText(family) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
            compose.onNode(heading, useUnmergedTree = true).assertIsDisplayed()
            val contents = hasText("Copyright", substring = true) and hasText("SIL OPEN FONT LICENSE", substring = true) and
                hasText("DISCLAIMER", substring = true)
            compose.onNode(contents and hasAnySibling(heading), useUnmergedTree = true).assertIsDisplayed()
        }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Done").assertDoesNotExist()
    }

    private fun styleOf(label: String) = mutableListOf<TextLayoutResult>().also { layouts ->
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    }.single().layoutInput.style

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
