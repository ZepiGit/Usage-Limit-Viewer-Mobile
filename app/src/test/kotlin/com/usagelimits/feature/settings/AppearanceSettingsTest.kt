package com.usagelimits.feature.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import com.usagelimits.ui.theme.colors
import com.usagelimits.core.settings.FigureFont
import com.usagelimits.ui.components.ScreenTitle
import com.usagelimits.ui.components.UsageWindowRow
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import com.usagelimits.feature.credits.CreditArtwork
import com.usagelimits.core.model.CreditProviderId
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.usagelimits.R
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.core.settings.TextFont
import com.usagelimits.ui.theme.face
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w393dp-h1600dp")
    fun `keyboard focus paints a separate ring without changing theme selection`() {
        val settings = AppSettings()
        compose.setContent {
            UsageLimitsTheme {
                AppearanceSettings(settings, onAppThemeChange = {})
            }
        }
        val tile = compose.onNodeWithText("Light paper")
        tile.assertIsNotSelected()
        fun outerPixel(): Int {
            compose.waitForIdle()
            val bounds = tile.fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            val root = compose.activity.window.decorView
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            return try { image.getPixel((bounds.center.x).toInt(), (bounds.top - 3 * density).toInt()) }
                finally { image.recycle() }
        }
        val before = outerPixel()
        val bounds = tile.fetchSemanticsNode().boundsInRoot
        tile.performSemanticsAction(SemanticsActions.RequestFocus) { it() }.assertIsFocused().assertIsNotSelected()
        assertEquals("focus does not resize the tile", bounds, tile.fetchSemanticsNode().boundsInRoot)
        assertNotEquals("the new ring is outside the unchanged selection border", before, outerPixel())
        assertEquals(AppTheme.PURE_BLACK.colors.Ink.toArgb(), outerPixel())
        compose.onNodeWithText("Pure black").assertIsSelected()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `white credit artwork takes paper ink while coloured artwork stays unchanged`() {
        val theme = mutableStateOf(AppTheme.PURE_BLACK)
        compose.setContent {
            UsageLimitsTheme(appTheme = theme.value) {
                Row {
                    for (provider in listOf(CreditProviderId.XAI, CreditProviderId.MOONSHOT, CreditProviderId.DEEPSEEK)) {
                        Column(Modifier.size(64.dp).testTag(provider.id)) { CreditArtwork(provider, 64.dp) }
                    }
                }
            }
        }
        fun pixels(provider: CreditProviderId): List<Int> {
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag(provider.id).fetchSemanticsNode().boundsInRoot
            val root = compose.activity.window.decorView
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            return try {
                buildList {
                    for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                        for (x in bounds.left.toInt() until bounds.right.toInt()) add(image.getPixel(x, y))
                    }
                }
            } finally { image.recycle() }
        }
        val dark = listOf(CreditProviderId.XAI, CreditProviderId.MOONSHOT, CreditProviderId.DEEPSEEK).associateWith(::pixels)
        compose.runOnIdle { theme.value = AppTheme.LIGHT_PAPER }
        for (provider in listOf(CreditProviderId.XAI, CreditProviderId.MOONSHOT)) {
            val light = pixels(provider)
            assertNotEquals("$provider changes from white artwork to paper ink", dark.getValue(provider), light)
            org.junit.Assert.assertTrue("$provider has actual paper-ink pixels", AppTheme.LIGHT_PAPER.colors.Ink.toArgb() in light)
        }
        assertEquals("coloured DeepSeek art is not tinted", dark.getValue(CreditProviderId.DEEPSEEK), pixels(CreditProviderId.DEEPSEEK))
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
        assertEquals(FontWeight(560), defaultText.fontWeight)
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
    fun `text faces request their native weight and variation axis`() {
        val font = mutableStateOf(TextFont.ROBOTO)
        compose.setContent {
            UsageLimitsTheme(textFont = font.value) {
                Column {
                    Text("Body", style = MaterialTheme.typography.bodyMedium)
                    Text("Title", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        for (face in TextFont.entries) {
            compose.runOnIdle { font.value = face }
            val variable = face != TextFont.IBM_PLEX_SANS
            for ((label, weight) in listOf("Body" to if (variable) 450 else 400, "Title" to if (variable) 560 else 500)) {
                val style = styleOf(label)
                assertEquals("$face $label requested weight", FontWeight(weight), style.fontWeight)
                assertEquals("$face $label synthesis", FontSynthesis.None, style.fontSynthesis)
                val fontFamily = style.fontFamily as FontListFontFamily
                val descriptor = fontFamily.single { it.weight == FontWeight(weight) }
                val settings = when (descriptor) {
                    is AndroidFont -> descriptor.variationSettings
                    is ResourceFont -> descriptor.variationSettings
                    else -> error("Unexpected font descriptor: $descriptor")
                }
                assertEquals("$face $label wght axis", weight.toFloat().takeIf { variable },
                    settings.settings.singleOrNull { it.axisName == "wght" }?.toVariationValue(null))
            }
        }
    }

    @Test
    @Config(qualifiers = "en-w393dp-h2400dp")
    fun `reset prose and every Material slot use the selected text face`() {
        val now = 1_757_000_000_000L
        compose.setContent {
            UsageLimitsTheme(textFont = TextFont.FIGTREE, figureFont = FigureFont.IBM_PLEX_MONO) {
                Column {
                    ScreenTitle("Resets", stamp = "3 upcoming")
                    UsageWindowRow(UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, 40.0,
                        18_000, now + 3_600_000, false), nowMs = now, mode = PercentDisplay.LEFT)
                    val typography = MaterialTheme.typography
                    listOf(typography.displayLarge, typography.displayMedium, typography.displaySmall,
                        typography.headlineLarge, typography.headlineMedium, typography.headlineSmall,
                        typography.titleLarge, typography.titleMedium, typography.titleSmall,
                        typography.bodyLarge, typography.bodyMedium, typography.bodySmall,
                        typography.labelLarge, typography.labelMedium, typography.labelSmall).forEachIndexed { index, style ->
                        Text("slot-$index", style = style)
                    }
                }
            }
        }
        assertEquals(TextFont.FIGTREE.face.family, styleOf("3 upcoming").fontFamily)
        assertEquals(TextFont.FIGTREE.face.family, styleOf("Reset in 1h").fontFamily)
        for (index in 0 until 15) assertEquals("slot-$index", TextFont.FIGTREE.face.family, styleOf("slot-$index").fontFamily)
        assertNotEquals(TextFont.FIGTREE.face.family, styleOf("60%").fontFamily)
    }

    @Test
    fun `the provider chooser title inherits the chosen text face`() {
        compose.setContent {
            UsageLimitsTheme(textFont = TextFont.FIGTREE) { ProviderIconSettings(emptyMap()) { _, _ -> } }
        }
        compose.onNodeWithText("Claude", useUnmergedTree = true).performClick()
        val title = styleOf("Claude icon")
        assertEquals(FontWeight(450), title.fontWeight)
        assertEquals(TextFont.FIGTREE.face.family, title.fontFamily)
        compose.onNodeWithText("Done").performClick()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `the font licence view shows every complete notice shipped in the app`() {
        val readDispatcher = StandardTestDispatcher()
        val notices = listOf(
            "Roboto Flex" to R.raw.font_license_roboto_flex,
            "IBM Plex Sans" to R.raw.font_license_ibm_plex_sans,
            "Figtree" to R.raw.font_license_figtree,
            "Roboto Mono" to R.raw.font_license_roboto_mono,
            "JetBrains Mono" to R.raw.font_license_jetbrains_mono,
            "IBM Plex Mono" to R.raw.font_license_ibm_plex_mono,
        ).map { (family, raw) ->
            family to compose.activity.resources.openRawResource(raw).bufferedReader().use { it.readText() }
        }
        compose.setContent {
            UsageLimitsTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AppearanceFonts(AppSettings(), onTextFontChange = {}, onFigureFontChange = {},
                        licenceReadDispatcher = readDispatcher)
                }
            }
        }
        compose.onNodeWithText("Font licences").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Done").assertIsDisplayed()
        val documents = compose.onNode(hasScrollToIndexAction(), useUnmergedTree = true)
        documents.assertDoesNotExist()
        compose.runOnIdle { readDispatcher.scheduler.runCurrent() }
        for ((index, notice) in notices.withIndex()) {
            val (family, fullText) = notice
            documents.performScrollToIndex(index)
            val heading = hasText(family) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
            compose.onNode(heading, useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(hasText(fullText) and hasAnySibling(heading), useUnmergedTree = true).assertIsDisplayed()
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
