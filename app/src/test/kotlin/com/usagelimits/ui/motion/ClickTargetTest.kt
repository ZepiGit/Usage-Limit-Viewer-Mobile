package com.usagelimits.ui.motion

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.ProviderId
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.overview.OverviewScreen
import com.usagelimits.ui.components.TextAction
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Clipping a press highlight must not shrink what can be pressed (#81; the rule #26 set for
 * `Segment`: clip the indication, not the target). A tap in the corner of a control's box lands
 * outside a rounded or round clip; it has to reach the control all the same, as it does on main.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h900dp-xhdpi")
class ClickTargetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    /** One pixel inside each corner of the node's own box. */
    private fun SemanticsNodeInteraction.tapEveryCorner() {
        val size = fetchSemanticsNode().size
        for (corner in listOf(
            Offset(1f, 1f), Offset(size.width - 2f, 1f), Offset(1f, size.height - 2f), Offset(size.width - 2f, size.height - 2f),
        )) performTouchInput { click(corner) }
    }

    @Test
    fun `the refresh action takes a tap in every corner of its 48dp box`() {
        var refreshes = 0
        compose.setContent {
            UsageLimitsTheme {
                OverviewScreen(state = UsageUiState(), nowMs = MOTION_NOW, onRefresh = { refreshes++ }, onAccountClick = {},
                    onAddAccount = {}, onPercentDisplayChange = {})
            }
        }
        val refresh = compose.onNode(hasContentDescription("Refresh usage") and button)
        refresh.assertHasClickAction()
        assertEquals("the touch target is 48dp square", 96 to 96, refresh.fetchSemanticsNode().size.let { it.width to it.height })

        refresh.tapEveryCorner()

        assertEquals("taps taken in the four corners", 4, refreshes)
    }

    @Test
    fun `a List row takes a tap in every corner of its box`() {
        var opened = 0
        compose.setContent {
            UsageLimitsTheme {
                Box(Modifier.background(UsageColors.Ground).padding(24.dp)) {
                    OverviewScreen(state = UsageUiState(accounts = listOf(demoAccount("one", ProviderId.CODEX))),
                        nowMs = MOTION_NOW, zone = java.time.ZoneId.of("Etc/UTC"), onRefresh = {}, onAccountClick = { opened++ },
                        onAddAccount = {}, onPercentDisplayChange = {})
                }
            }
        }

        compose.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Headline", substring = true))).tapEveryCorner()

        assertEquals("taps taken in the four corners", 4, opened)
    }

    @Test
    fun `a text action takes a tap in every corner of its box and stays a button`() {
        var retried = 0
        compose.setContent {
            UsageLimitsTheme {
                Column(Modifier.background(UsageColors.Ground).padding(24.dp)) { TextAction("Retry", { retried++ }) }
            }
        }
        val action = compose.onNode(hasText("Retry") and button)
        action.assert(hasClickAction())

        action.tapEveryCorner()

        assertEquals("taps taken in the four corners", 4, retried)
    }
}
