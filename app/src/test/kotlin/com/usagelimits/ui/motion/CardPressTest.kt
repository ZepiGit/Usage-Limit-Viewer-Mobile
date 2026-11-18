package com.usagelimits.ui.motion

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import com.usagelimits.feature.overview.AccountCard
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * A pressed card lights its rounded shape, not the square around it (#81; the defect class of #26).
 *
 * API 30 and a software frame, for the reason SegmentPressTest gives: the patterned ripple of
 * API 31+ is not drawn under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w393dp-h400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardPressTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the press highlight stays inside the card's rounded corners`() {
        compose.setContent {
            UsageLimitsTheme {
                Box(Modifier.background(UsageColors.Ground).padding(24.dp)) {
                    AccountCard(demoAccount("one", ProviderId.CODEX), MOTION_NOW, 3_600_000, PercentDisplay.LEFT) {}
                }
            }
        }
        compose.waitForIdle()
        val card = compose.onNode(hasClickAction())
        val bounds = card.fetchSemanticsNode().boundsInWindow
        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt()
        val right = bounds.right.roundToInt()
        val bottom = bounds.bottom.roundToInt()
        val before = compose.softwareFrame()

        card.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        val pressed = compose.softwareFrame()
        card.performTouchInput { up() }

        // 6dp in from a corner is inside the 8dp rounding and clear of the card's 16dp content padding.
        val inside = (6 * compose.density.density).roundToInt()
        assertNotEquals("the pressed card shows a highlight", before.at(left + inside, top + inside), pressed.at(left + inside, top + inside))

        // One pixel in from each corner of the bounding square lies outside the rounding: bare ground.
        val corners = listOf(left + 1 to top + 1, right - 2 to top + 1, left + 1 to bottom - 2, right - 2 to bottom - 2)
        val spilled = corners.filter { (x, y) -> before.at(x, y) != pressed.at(x, y) }
        assertEquals("corner pixels the highlight changed outside the rounded card", emptyList<Pair<Int, Int>>(), spilled)
    }
}
