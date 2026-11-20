package com.usagelimits.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.sync.resetTestWorkManager
import com.usagelimits.feature.waitDrained
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The app shell with the real container, view model and Room: three tabs, account links that
 * resolve cold and warm, an unavailable account with a way back, and the filter that goes back
 * to All when another tab is chosen. Motion is off so each step lands on its frame; the view
 * model lives in a store this test owns and clears.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34], qualifiers = "w393dp-h1600dp-xhdpi")
class OverviewRoutingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
    private var requested by mutableStateOf<String?>(null)
    private val tab = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    @Before fun setUp() {
        runBlocking { app.startupJob?.join() }
        WorkManager.getInstance(app).cancelAllWork().result.get()
    }

    @After fun tearDown() {
        // The navigation's view model and its jobs end with the store, before WorkManager goes.
        compose.runOnUiThread { store.clear() }
        ShadowLooper.idleMainLooper()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
    }

    private suspend fun account(name: String, left: Double) = app.container.repository
        .upsertFromLogin(ProviderId.CODEX, "$name-${System.nanoTime()}", null, name, "Plus", emptyMap()).also {
            val now = System.currentTimeMillis()
            app.container.repository.saveSnapshot(UsageSnapshot(it.localId, now, SnapshotStatus.OK, listOf(
                UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 100.0 - left, 604_800, now + 3_600_000, false))))
        }

    private fun showShell() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalMotionEnabled provides false) {
                UsageLimitsTheme {
                    UsageLimitsNavigation(app.container, WindowSizeClass.calculateFromSize(DpSize(393.dp, 800.dp)),
                        requestedAccountId = requested, onAccountHandled = { requested = null })
                }
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun `the shell has Overview, Resets and Settings and no Accounts tab`() {
        showShell()
        compose.waitDrained { compose.onAllNodes(tab).fetchSemanticsNodes().size == 3 }
        assertEquals(listOf(true, true, true), listOf("Overview", "Resets", "Settings").map {
            compose.onAllNodes(hasText(it) and tab).fetchSemanticsNodes().size == 1
        })
        assertEquals(0, compose.onAllNodes(hasText("Accounts") and tab).fetchSemanticsNodes().size)
    }

    @Test fun `a cold link waits for the store and opens the account's detail`() {
        val alpha = runBlocking { account("Alpha", 60.0) }
        requested = alpha.localId
        showShell()
        compose.waitDrained { shown("Remove account") }
        assertEquals("the request is consumed once", null, requested)
    }

    @Test fun `a warm link opens the account over a filter that hides it, and back keeps that filter`() {
        val alpha = runBlocking { account("Alpha", 60.0) }
        showShell()
        compose.waitDrained { shown("Needs attention") }
        compose.onNode(hasText("Needs attention")).performClick()
        compose.waitDrained { shown("No accounts match this filter.") }
        compose.runOnUiThread { requested = alpha.localId }
        compose.waitDrained { shown("Remove account") }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitDrained { shown("No accounts match this filter.") }
    }

    @Test fun `a link to a removed account says so and leads back to Overview`() {
        runBlocking { account("Alpha", 60.0) }
        requested = "removed-account"
        showShell()
        compose.waitDrained { shown("This account is no longer available.") }
        assertEquals("not a spinner, not the wrong account", false, shown("Remove account"))
        compose.onNodeWithText("Back to Overview").performClick()
        compose.waitDrained { shown("All") && shown("Needs attention") }
    }

    @Test fun `removing an account from its detail returns to Overview without it`() {
        val alpha = runBlocking { account("Alpha", 60.0) }
        runBlocking { account("Bravo", 40.0) }
        requested = alpha.localId
        showShell()
        compose.waitDrained { shown("Remove account") }
        compose.onNodeWithText("Remove account").performClick()
        compose.waitDrained { shown("Remove") && shown("Remove account?") }
        compose.onNodeWithText("Remove").performClick()
        compose.waitDrained { shown("Needs attention") && runBlocking { app.container.repository.account(alpha.localId) } == null }
        assertEquals("back on Overview, not on an unavailable detail", false, shown("This account is no longer available."))
    }

    @Test fun `choosing another tab and coming back restores All`() {
        runBlocking { account("Alpha", 60.0) }
        showShell()
        compose.waitDrained { shown("Resets today") }
        compose.onNode(hasText("Resets today")).performClick()
        compose.waitDrained { compose.onAllNodes(hasText("Resets today") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Resets") and tab).performClick()
        compose.waitDrained { shown("No upcoming resets") || shown("1 upcoming") }
        compose.onNode(hasText("Overview") and tab).performClick()
        compose.waitDrained { compose.onAllNodes(hasText("All") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .fetchSemanticsNodes().isNotEmpty() }
    }
}
