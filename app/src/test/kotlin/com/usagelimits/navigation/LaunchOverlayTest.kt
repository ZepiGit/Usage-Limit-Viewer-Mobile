package com.usagelimits.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchOverlayTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun resetProcessFlag() {
        launchOverlayShown = false
    }

    // From Android 12 the system splash screen is the launch; the overlay would be a second icon.
    @Test
    fun `no overlay where the system splash screen already shows the icon`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { LaunchOverlay() }

        compose.onNodeWithTag("launchOverlay").assertDoesNotExist()
    }

    // One test, three phases: the once-per-process flag is static, so separate tests would
    // depend on their run order. API 30: the last release whose starting window is a bare ground.
    @Config(sdk = [30])
    @Test
    fun `overlay shows on the first composition only and clears itself`() {
        compose.mainClock.autoAdvance = false
        var generation by mutableIntStateOf(0)
        compose.setContent { key(generation) { LaunchOverlay() } }

        compose.onNodeWithTag("launchOverlay").assertExists()

        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("launchOverlay").assertDoesNotExist()

        generation++
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("launchOverlay").assertDoesNotExist()
    }
}
