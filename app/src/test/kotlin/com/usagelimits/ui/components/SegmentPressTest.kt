package com.usagelimits.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.usagelimits.R
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.credits.AddCreditScreen
import com.usagelimits.feature.credits.AddCreditState
import com.usagelimits.feature.settings.SettingsScreen
import com.usagelimits.ui.motion.softwareFrame
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageLimitsTheme
import com.usagelimits.widget.WidgetConfigScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A pressed segment lights its rounded pill, not the at-least-48dp touch target around it (#26).
 *
 * The frames are the decor view drawn in software, as RedesignCaptureTest does it:
 * `captureToImage()` never sees a frame under Robolectric.
 *
 * The pixel test runs on API 30: from API 31 the platform ripple is the patterned (sparkle) one,
 * which Robolectric does not draw into a software canvas, so a press on 34 leaves the frame unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h300dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentPressTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setRow() = compose.setContent {
        UsageLimitsTheme {
            Box(Modifier.background(UsageColors.Ground).padding(24.dp)) {
                SegmentedRow {
                    Segment("All", selected = true) {}
                    Segment("Needs attention", selected = false) {}
                }
            }
        }
    }

    private val radioButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private val verticalScroll = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private val horizontalScroll = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
    private val captureDir = System.getProperty("capture.dir")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun setSettings() = compose.setContent {
        UsageLimitsTheme {
            SettingsScreen(
                state = UsageUiState(settings = AppSettings(syncIntervalMinutes = 30, overviewShowCredits = true)),
                onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {},
                onNotifyExhausted = {}, onNotifyResetCredit = {}, onNotifyAuthExpired = {},
                onNotifyResetApproaching = {}, onNotifyCreditExpiring = {}, onShowTier = {},
                onOverviewLayoutChange = {}, onPercentDisplayChange = {},
                onNotificationPermissionHandled = {}, onAppThemeChange = {},
            )
        }
    }

    private fun revealSegment(label: String, horizontal: Boolean = true): SemanticsNodeInteraction {
        val choice = hasText(label) and radioButton
        // Reveal the row vertically before scrolling the choice into that row's horizontal viewport.
        val row = horizontalScroll and hasAnyDescendant(choice)
        compose.onNode(verticalScroll).performScrollToNode(if (horizontal) row else choice)
        val segment = compose.onNode(if (horizontal) choice and hasAnyAncestor(row) else choice)
        if (horizontal) segment.performScrollTo()
        return segment.assertIsDisplayed()
    }

    private fun settleRipple() {
        compose.mainClock.advanceTimeBy(1_000)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        compose.waitForIdle()
    }

    private fun Bitmap.saveFrame(name: String) {
        val dir = captureDir ?: return
        dir.mkdirs()
        File(dir, "segment-$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertPressedPill(segment: SemanticsNodeInteraction, captureName: String? = null) {
        settleRipple()
        val site = captureName ?: "standalone segment"
        val node = segment.assertHeightIsAtLeast(48.dp).assertIsNotSelected().fetchSemanticsNode()
        val bounds = node.boundsInWindow
        val px = compose.density.density
        val left = bounds.left.roundToInt()
        val right = bounds.right.roundToInt()
        val top = bounds.top.roundToInt()
        val bottom = bounds.bottom.roundToInt()
        assertEquals("$site: full target width is visible", node.size.width, right - left)
        assertEquals("$site: full target height is visible", node.size.height, bottom - top)
        // The pill can grow with the text; only the top and bottom padding stay fixed.
        val pillTop = top + (8 * px).roundToInt()
        val pillBottom = bottom - (8 * px).roundToInt()
        val radius = 6 * px
        val rest = compose.softwareFrame()
        var pressedFrame: Bitmap? = null
        try {
            segment.performTouchInput { down(center) }
            try {
                settleRipple()
                pressedFrame = compose.softwareFrame()
            } finally {
                segment.performTouchInput { up() }
            }
            val pressed = checkNotNull(pressedFrame)
            if (captureName != null) {
                rest.saveFrame("$captureName-rest")
                pressed.saveFrame("$captureName-pressed")
            }
            val insideX = left + radius.roundToInt()
            val insideY = (pillTop + pillBottom) / 2
            assertTrue("$site: the pressed pill shows a highlight at ($insideX, $insideY)",
                rest.getPixel(insideX, insideY) != pressed.getPixel(insideX, insideY))

            val restRow = IntArray(rest.width)
            val pressedRow = IntArray(rest.width)
            var spill = 0
            val firstSpill = mutableListOf<Pair<Int, Int>>()
            for (y in 0 until rest.height) {
                rest.getPixels(restRow, 0, rest.width, 0, y, rest.width, 1)
                pressed.getPixels(pressedRow, 0, rest.width, 0, y, rest.width, 1)
                for (x in restRow.indices) {
                    if (restRow[x] == pressedRow[x]) continue
                    // At the rounded edge, a pixel is inside if any of its area is covered.
                    val dx = max(0f, max(left + radius - (x + 1), x - (right - radius)))
                    val dy = max(0f, max(pillTop + radius - (y + 1), y - (pillBottom - radius)))
                    if (x !in left until right || y !in pillTop until pillBottom || dx * dx + dy * dy >= radius * radius) {
                        spill++
                        if (firstSpill.size < 4) firstSpill += x to y
                    }
                }
            }
            assertEquals("$site: pixels changed outside the rounded pill, first: $firstSpill", 0, spill)
        } finally {
            pressedFrame?.recycle()
            rest.recycle()
        }
        settleRipple()
    }

    @Config(sdk = [30])
    @Test
    fun `the press highlight stays inside the rounded pill`() {
        setRow()
        assertPressedPill(compose.onNode(hasText("Needs attention") and radioButton))
    }

    @Config(sdk = [30], qualifiers = "en-w393dp-h873dp-xhdpi")
    @Test
    fun `Settings selectors confine the held highlight to their pills`() {
        setSettings()
        for ((site, label) in listOf(
            "sync-interval" to compose.activity.getString(R.string.countdown_minutes, 15),
            "display-mode" to compose.activity.getString(R.string.percent_caption_used),
            "overview-layout" to compose.activity.getString(R.string.settings_overview_dials),
            "credit-layout" to compose.activity.getString(R.string.settings_credit_compact),
            "show-first" to compose.activity.getString(R.string.settings_credit_layout_title),
        )) {
            assertPressedPill(revealSegment(label), "settings-$site-en-393dp-font1.0")
        }
    }

    @Config(sdk = [30], qualifiers = "de-w320dp-h873dp-xhdpi", fontScale = 1.6f)
    @Test
    fun `the wrapped German Show first choice confines its held highlight`() {
        setSettings()
        val credits = revealSegment(compose.activity.getString(R.string.settings_credit_layout_title), horizontal = false)
        val usage = compose.onNode(hasText(compose.activity.getString(R.string.settings_overview_usage_providers)) and radioButton)
            .assertIsDisplayed().assertIsSelected()
        assertTrue("the second Show first choice wraps below the first",
            credits.fetchSemanticsNode().boundsInWindow.top >= usage.fetchSemanticsNode().boundsInWindow.bottom)
        assertPressedPill(credits, "settings-show-first-de-320dp-font1.6")
    }

    @Config(sdk = [30], qualifiers = "en-w393dp-h873dp-xhdpi")
    @Test
    fun `the Moonshot region selector confines its held highlight`() {
        compose.setContent {
            UsageLimitsTheme {
                AddCreditScreen(state = AddCreditState.Idle,
                    onSubmit = { _, _, _, _, _, _ -> error("A region choice must not submit credentials") },
                    onCancel = {}, onDone = {})
            }
        }
        compose.onNode(hasText(CreditProviderId.MOONSHOT.displayName) and hasClickAction())
            .performScrollTo().assertIsDisplayed().performClick()
        assertPressedPill(revealSegment(compose.activity.getString(R.string.credit_region_china)),
            "add-credit-moonshot-region-en-393dp-font1.0")
    }

    @Config(sdk = [30], qualifiers = "en-w393dp-h873dp-xhdpi")
    @Test
    fun `the widget text tone selector confines its held highlight`() {
        compose.setContent {
            UsageLimitsTheme(appTheme = AppTheme.WARM_DARK) {
                WidgetConfigScreen(accounts = emptyList(), initial = null, kind = "Usage Bars", saving = false,
                    error = null, percentDisplay = PercentDisplay.LEFT, onCancel = {},
                    onSave = { _, _, _, _, _ -> error("A text tone choice must not save the widget") })
            }
        }
        assertPressedPill(revealSegment(compose.activity.getString(R.string.widget_config_tone_light)),
            "widget-config-text-tone-en-393dp-font1.0")
    }

    @Test
    fun `a segment keeps its 48dp touch target and reads as a radio button with its state`() {
        setRow()
        compose.onNode(hasText("All") and radioButton)
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertIsSelected()
        compose.onNode(hasText("Needs attention") and radioButton).assertHeightIsAtLeast(48.dp).assertIsNotSelected()
    }
}
