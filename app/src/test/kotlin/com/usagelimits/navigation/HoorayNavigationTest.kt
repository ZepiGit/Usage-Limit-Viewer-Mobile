package com.usagelimits.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.sync.resetTestWorkManager
import com.usagelimits.feature.UsageViewModel
import com.usagelimits.feature.waitDrained
import com.usagelimits.ui.LocalHoorayPlayback
import com.usagelimits.ui.motion.FrameRecorder
import com.usagelimits.ui.motion.MOTION_NOW
import com.usagelimits.ui.motion.change
import com.usagelimits.ui.motion.saveFrame
import com.usagelimits.ui.motion.toRegion
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** Real cold navigation and opening claim; only the parent's first placement is held. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34], qualifiers = "w393dp-h700dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoorayNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
    private var placeContent by mutableStateOf(true)
    private lateinit var accountId: String

    @Before fun setup() {
        runBlocking { app.startupJob?.join() }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        resetTestWorkManager()
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        app.mainOpening.processStopped()
        compose.mainClock.autoAdvance = false
        accountId = runBlocking {
            val container = app.container
            container.settingsStore.setProviderIcon(ProviderId.CLAUDE.id, "claudecode-color")
            container.settingsStore.setAnimateClaudeCodeIcon(true)
            val id = container.repository.upsertFromLogin(ProviderId.CLAUDE, "cold-hooray", null,
                "Cold Claude", null, emptyMap()).localId
            fun reading(fetch: Long, reset: Long) = UsageSnapshot(id, fetch, SnapshotStatus.OK, listOf(
                UsageWindow("five-hour", "5h limit", WindowCategory.FIVE_HOUR, 30.0, 18_000, reset, false)))
            container.repository.saveSnapshot(reading(MOTION_NOW - 2_000, MOTION_NOW - 1_000))
            container.repository.saveSnapshot(reading(MOTION_NOW, MOTION_NOW + 3_600_000))
            id
        }
    }

    @After fun cleanup() {
        val jobs = store.keys().mapNotNull { store[it] as? UsageViewModel }
            .map { it.viewModelScope.coroutineContext[Job]!! }
        compose.runOnUiThread { store.clear(); app.mainOpening.processStopped() }
        app.mainOpening.job?.cancel()
        compose.waitDrained { jobs.all { it.isCompleted } && app.mainOpening.job?.isCompleted != false }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
        runBlocking { app.container.repository.deleteAccount(accountId) }
        launchOverlayShown = false
    }

    private fun show() {
        // The production callback join and transactional claim, not playback.open in a UI fixture.
        compose.runOnUiThread {
            app.mainOpening.activityStarted(main = true)
            app.mainOpening.processStarted()
        }
        compose.waitDrained { app.mainOpening.job?.isCompleted == true }
        assertTrue("the opening claims the persisted mark before rendering", runBlocking {
            app.container.repository.claimPendingResetConfirmations().isEmpty()
        })
        compose.setContent {
            UsageLimitsTheme(providerIcons = mapOf(ProviderId.CLAUDE.id to "claudecode-color")) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner,
                    LocalMotionEnabled provides true, LocalHoorayPlayback provides app.hoorayPlayback) {
                    Box(Modifier.fillMaxSize().layout { measurable, constraints ->
                        val child = measurable.measure(constraints)
                        layout(child.width, child.height) {
                            if (placeContent) child.placeRelative(0, 0)
                        }
                    }) {
                        UsageLimitsNavigation(app.container,
                            WindowSizeClass.calculateFromSize(DpSize(393.dp, 700.dp)), now = { MOTION_NOW })
                    }
                }
            }
        }
        // Hold frame time while Room/DataStore deliver the initial complete account state.
        compose.waitDrained {
            store.keys().mapNotNull { store[it] as? UsageViewModel }.any { it.state.value.loaded }
        }
    }

    private fun advanceFrames(count: Int) {
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun assertCheerThenSteady(name: String) {
        advanceFrames(40)
        val figure = compose.onNode(hasContentDescription("Claude"), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.toRegion(margin = 12)
        FrameRecorder(compose).use { recorder ->
            fun sparkles(): Int {
                val frame = recorder.frame()
                return try {
                    var count = 0
                    for (y in figure.top until figure.bottom) for (x in figure.left until figure.right) {
                        val pixel = frame.getPixel(x, y)
                        val red = pixel shr 16 and 0xFF
                        val green = pixel shr 8 and 0xFF
                        val blue = pixel and 0xFF
                        // Gold sparkles, including animated alpha; the orange body is redder than this.
                        if (red > 40 && green * 100 / red in 75..90 && blue * 100 / red in 20..40) count++
                    }
                    count
                } finally { frame.recycle() }
            }
            recorder.frame().also { it.saveFrame("navigation-$name-initial", 0); it.recycle() }
            assertTrue("first ready Claude row visibly cheers ($name, start=${app.hoorayPlayback.startedAt(accountId)}, clock=${compose.mainClock.currentTime})", sparkles() > 0)
            val start = app.hoorayPlayback.startedAt(accountId)!!
            val elapsedMs = compose.mainClock.currentTime - start / 1_000_000L
            compose.mainClock.advanceTimeBy(4_000 - elapsedMs, ignoreFrameDuration = true)
            compose.waitForIdle()
            assertNull("four elapsed seconds end the shared presentation ($name)", app.hoorayPlayback.startedAt(accountId))
            assertEquals("the visible row returns to its steady pose ($name)", 0, sparkles())
            recorder.frame().also { it.saveFrame("navigation-$name-expired", 0); it.recycle() }
        }
    }

    @Test fun `cold main opening visibly cheers through real navigation for four elapsed seconds`() {
        show()
        assertCheerThenSteady("cold")
    }

    @Test fun `first ready placement beyond two frames still visibly cheers through real navigation`() {
        placeContent = false
        show()
        // Measure and compose the actual loaded destination, but withhold its first placement.
        // No later navigation, scrolling, icon change or reading supplies an eligible slot.
        advanceFrames(40)
        assertNull("an unplaced destination has not started", app.hoorayPlayback.startedAt(accountId))
        compose.change { placeContent = true }
        assertCheerThenSteady("held-first-layout")
    }
}
