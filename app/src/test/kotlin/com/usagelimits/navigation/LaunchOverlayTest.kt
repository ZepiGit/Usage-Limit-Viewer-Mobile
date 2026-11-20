package com.usagelimits.navigation

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.R
import com.usagelimits.UsageLimitsActivity
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.splashStyle
import com.usagelimits.feature.settings.AppIcon
import com.usagelimits.ui.motion.FrameRecorder
import com.usagelimits.ui.motion.change
import com.usagelimits.ui.motion.sameAs
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.widget.WidgetConfigActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaunchOverlayTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun resetProcessFlag() {
        launchOverlayShown = false
    }

    @After
    fun backToTheDefaultIcon() = AppIcon.apply(context, AppIcon.CURRENT)

    // One test, three phases: the once-per-process flag is static, so separate tests would
    // depend on their run order.
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

    // The system kills the process in the background and later restores the activity from its
    // saved state: a new process, so the process flag is clear, but not a launch.
    //
    // The saved state is carried over by hand, one frame at a time. StateRestorationTester does
    // both halves between two frames when the clock is paused, so nothing is composed anew, and
    // with a running clock it would play a wrongly replayed overlay to its end before returning.
    @Test
    fun `a process the system restores does not replay the launch`() {
        compose.mainClock.autoAdvance = false
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })
        var alive by mutableStateOf(true)
        compose.setContent {
            if (alive) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { LaunchOverlay() }
        }
        compose.onNodeWithTag("launchOverlay").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("launchOverlay").assertDoesNotExist()

        val saved = registry.performSave()
        compose.change { alive = false }
        compose.mainClock.advanceTimeByFrame()
        launchOverlayShown = false
        compose.change {
            registry = SaveableStateRegistry(saved) { true }
            alive = true
        }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithTag("launchOverlay").assertDoesNotExist()
    }

    // The control for the test above: the same sequence without saved state is a launch.
    @Test
    fun `a new process without saved state plays the launch`() {
        compose.mainClock.autoAdvance = false
        var alive by mutableStateOf(true)
        compose.setContent { if (alive) LaunchOverlay() }
        compose.mainClock.advanceTimeBy(1_000)

        compose.change { alive = false }
        compose.mainClock.advanceTimeByFrame()
        launchOverlayShown = false
        compose.change { alive = true }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithTag("launchOverlay").assertExists()
    }

    @Test
    fun `animations switched off while the overlay plays end it on the next frame, for good`() {
        compose.mainClock.autoAdvance = false
        var motion by mutableStateOf(true)
        compose.setContent { CompositionLocalProvider(LocalMotionEnabled provides motion) { LaunchOverlay() } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("launchOverlay").assertExists()

        compose.change { motion = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("launchOverlay").assertDoesNotExist()

        compose.change { motion = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("launchOverlay").assertDoesNotExist()
    }

    @Test
    fun `the overlay shows the icon the person chose`() {
        compose.mainClock.autoAdvance = false
        var generation by mutableIntStateOf(0)
        compose.setContent { key(generation) { LaunchOverlay() } }

        fun overlayAtFullSize(): Bitmap = FrameRecorder(compose).use { recorder ->
            compose.mainClock.advanceTimeBy(320)
            compose.onNodeWithTag("launchOverlay").assertExists()
            recorder.frame().also { compose.mainClock.advanceTimeBy(1_000) }
        }
        val default = overlayAtFullSize()

        AppIcon.apply(context, AppIcon.AI_HOURGLASS)
        launchOverlayShown = false
        compose.change { generation++ }
        compose.mainClock.advanceTimeByFrame()
        val chosen = overlayAtFullSize()

        assertFalse("the hourglass icon is drawn, not the default one", chosen.sameAs(default))
    }

    @Test
    fun `launcher variants use the matching ground bar flags and no system icon`() {
        for ((theme, ground) in mapOf(
            AppTheme.WARM_DARK to 0xFF0F0F0E.toInt(),
            AppTheme.PURE_BLACK to 0xFF000000.toInt(),
            AppTheme.COOL_GRAPHITE to 0xFF0E1013.toInt(),
            AppTheme.LIGHT_PAPER to 0xFFF3F0E8.toInt(),
        )) {
            val themed = android.view.ContextThemeWrapper(context, theme.splashStyle)
            val light = theme == AppTheme.LIGHT_PAPER
            for (attr in listOf(android.R.attr.windowBackground, android.R.attr.windowSplashScreenBackground,
                android.R.attr.windowSplashScreenIconBackgroundColor)) {
                val a = themed.obtainStyledAttributes(intArrayOf(attr))
                try { assertEquals(theme.name, ground, a.getColor(0, 0)) } finally { a.recycle() }
            }
            for (attr in listOf(android.R.attr.isLightTheme, android.R.attr.windowLightStatusBar,
                android.R.attr.windowLightNavigationBar)) {
                val a = themed.obtainStyledAttributes(intArrayOf(attr))
                try { assertEquals(theme.name, light, a.getBoolean(0, false)) } finally { a.recycle() }
            }
            val icon = themed.obtainStyledAttributes(intArrayOf(android.R.attr.windowSplashScreenAnimatedIcon))
            try { assertEquals(theme.name, android.R.color.transparent, icon.getResourceId(0, 0)) } finally { icon.recycle() }
        }
    }

    // Only the app's own activity has this overlay, so only it may empty the system splash screen.
    @Test
    fun `only the app activity changes the system splash screen`() {
        fun theme(activity: Class<*>) = context.packageManager.getActivityInfo(ComponentName(context, activity), 0).themeResource
        fun splashIcon(style: Int): Int {
            val attributes = context.obtainStyledAttributes(style, intArrayOf(android.R.attr.windowSplashScreenAnimatedIcon))
            return try { attributes.getResourceId(0, 0) } finally { attributes.recycle() }
        }

        val appTheme = theme(UsageLimitsActivity::class.java)
        assertEquals(R.style.Theme_UsageLimits_App, appTheme)
        val ground = context.obtainStyledAttributes(appTheme, intArrayOf(android.R.attr.windowBackground))
        try {
            assertEquals("the manifest starting window is Pure black", android.graphics.Color.BLACK, ground.getColor(0, 0))
        } finally {
            ground.recycle()
        }
        assertEquals("the app activity's splash screen has no icon", android.R.color.transparent, splashIcon(R.style.Theme_UsageLimits_App))
        assertEquals(R.style.Theme_UsageLimits, theme(WidgetConfigActivity::class.java))
        assertTrue("the widget configuration screen keeps the system's splash icon", splashIcon(R.style.Theme_UsageLimits) == 0)
    }
}
