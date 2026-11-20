package com.usagelimits.navigation

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.usagelimits.UsageLimitsActivity
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.AccountDao
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.sync.resetTestWorkManager
import com.usagelimits.feature.UsageViewModel
import com.usagelimits.feature.waitDrained
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

/**
 * The real activity's hand-over of entries from outside (#88 01b review, items 4 and 6): an
 * account link is handled once, also across recreation and process restoration; the same link
 * tapped again is a new request; an alert or a whole-widget tap opens Overview over a detail or
 * another tab; an ordinary reopen from the launcher stays where the app was.
 *
 * The container's account list starts only when [gate] opens, so a cold link meets a store that
 * has not answered yet. The system's animations are off, the switch the theme reads, so each
 * navigation lands on its frame. Its WorkManager is the test one; the activity's jobs and the view model
 * end, and the original container comes back, before WorkManager is reset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34], qualifiers = "w393dp-h1600dp-xhdpi")
class ExternalRequestActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val gate = CompletableDeferred<Unit>()
    private lateinit var original: AppContainer
    private lateinit var container: AppContainer
    private val controllers = mutableListOf<ActivityController<UsageLimitsActivity>>()
    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

    /** Account rows wait for [gate]; snapshots and writes do not. */
    private class GatedAccounts(private val real: AccountDao, private val gate: CompletableDeferred<Unit>) : AccountDao by real {
        override fun observeAll() = real.observeAll().onStart { gate.await() }
    }

    @Before fun setUp() {
        runBlocking { app.startupJob?.join() }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        resetTestWorkManager()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        original = app.container
        container = object : AppContainer(app) {
            override val repository: UsageRepository by lazy {
                UsageRepository(GatedAccounts(database.accountDao(), gate), database.usageSnapshotDao(), transactions = transactions)
            }
        }
        ReflectionHelpers.setField(app, "container", container)
        runBlocking { container.settingsStore.recordNotificationPermissionHandled() }
    }

    @After fun tearDown() {
        gate.complete(Unit)
        try {
            // The activities end first: their view model's jobs are cancelled and run out on this
            // thread, so the looper is pumped until they have.
            for (controller in controllers.asReversed()) {
                val activity = controller.get()
                if (activity.isDestroyed) continue
                val jobs = viewModelJob(activity)
                controller.pause().stop().destroy()
                val deadline = System.currentTimeMillis() + 10_000
                while (jobs?.isCompleted == false) {
                    check(System.currentTimeMillis() < deadline) { "the view model's jobs did not end" }
                    ShadowLooper.idleMainLooper()
                    runBlocking { kotlinx.coroutines.yield() }
                }
            }
        } finally {
            ReflectionHelpers.setField(app, "container", original)
            WorkManager.getInstance(app).cancelAllWork().result.get()
            ShadowLooper.idleMainLooper()
            resetTestWorkManager()
        }
    }

    private fun viewModelJob(activity: UsageLimitsActivity): Job? = activity.viewModelStore.keys()
        .mapNotNull { activity.viewModelStore[it] as? UsageViewModel }.singleOrNull()?.viewModelScope?.coroutineContext?.get(Job)

    private suspend fun account(name: String): ProviderAccount = container.repository
        .upsertFromLogin(ProviderId.CODEX, "$name-${System.nanoTime()}", null, name, "Plus", emptyMap()).also {
            val now = System.currentTimeMillis()
            container.repository.saveSnapshot(UsageSnapshot(it.localId, now, SnapshotStatus.OK, listOf(
                UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 40.0, 604_800, now + 3_600_000, false))))
        }

    private fun link(account: ProviderAccount) = Intent(app, UsageLimitsActivity::class.java)
        .putExtra(ExternalRequest.EXTRA_ACCOUNT_ID, account.localId)

    private fun launcher() = Intent(app, UsageLimitsActivity::class.java)
        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun start(intent: Intent, saved: Bundle? = null) =
        Robolectric.buildActivity(UsageLimitsActivity::class.java, intent).also { controllers += it }
            .create(saved).start().resume().visible()

    private fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun onDetailOf(name: String) = shown("Remove account") && shown(name)
    // The merged tree: a bar item is selected, and its label is a child merged into it.
    private fun onOverview() = compose.onAllNodes(hasText("Overview") and selected).fetchSemanticsNodes().isNotEmpty()
    private fun onSettings() = compose.onAllNodes(hasText("Settings") and selected).fetchSemanticsNodes().isNotEmpty()

    private fun back(controller: ActivityController<UsageLimitsActivity>) {
        controller.get().onBackPressedDispatcher.onBackPressed()
    }

    /** [waitDrained], and on a timeout the screen it saw, so a failure says where the app was. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        try {
            compose.waitDrained(condition)
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val texts = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
            throw AssertionError("$what not reached; on screen: $texts", timeout)
        }
    }

    @Test fun `the app window cannot draw before the saved appearance is ready`() {
        gate.complete(Unit)
        val store = container.settingsStore
        val saved = store.settings
        val previousTheme = runBlocking { saved.first().appTheme }
        val theme = com.usagelimits.core.settings.AppTheme.LIGHT_PAPER
        runBlocking { store.setAppTheme(theme) }
        val release = CompletableDeferred<Unit>()
        ReflectionHelpers.setField(store, "settings", saved.onStart { release.await() })
        try {
            val activity = start(launcher()).get()
            val window = activity.window
            val decor = window.decorView
            assertTrue("pre-draw must be canceled while saved settings cannot arrive",
                decor.viewTreeObserver.dispatchOnPreDraw())
            release.complete(Unit)
            waitFor("the themed root") { onOverview() }
            assertFalse("the themed window must let pre-draw through", decor.viewTreeObserver.dispatchOnPreDraw())
            val bars = androidx.core.view.WindowCompat.getInsetsController(window, decor)
            assertTrue("paper requests dark status icons", bars.isAppearanceLightStatusBars)
            assertTrue("paper requests dark navigation icons", bars.isAppearanceLightNavigationBars)
            assertEquals(0xFFF3F0E8.toInt(), (decor.background as android.graphics.drawable.ColorDrawable).color)
        } finally {
            release.complete(Unit)
            ReflectionHelpers.setField(store, "settings", saved)
            runBlocking { store.setAppTheme(previousTheme) }
        }
    }

    @Test fun `a cold link waits for the store, and Back, recreation or restoration do not open it again`() {
        val alpha = runBlocking { account("Alpha") }
        val controller = start(link(alpha))
        waitFor("shown('Usage Limits')") { shown("Usage Limits") }
        assertFalse("the detail waits for the store's first answer", shown("Remove account"))

        // Recreated while the request is still pending: it is kept, not lost and not doubled.
        controller.recreate()
        gate.complete(Unit)
        waitFor("onDetailOf('Alpha')") { onDetailOf("Alpha") }

        back(controller)
        waitFor("onOverview() && !shown('Remove account')") { onOverview() && !shown("Remove account") }

        // Recreation without replay.
        controller.recreate()
        waitFor("onOverview()") { onOverview() }
        assertFalse("recreation replayed the handled link", shown("Remove account"))

        // Process restoration: a new activity from the original intent and the saved state.
        val saved = Bundle().also { controller.saveInstanceState(it) }
        controller.pause().stop().destroy()
        start(link(alpha), saved)
        waitFor("onOverview()") { onOverview() }
        assertFalse("restoration replayed the handled link", shown("Remove account"))
    }

    // Simulated: a new activity started from the intent the activity holds after handling, which
    // handled() has rewritten. Whether the system restores a task from that intent or from the one
    // it recorded at launch is not shown here; that needs a device.
    @Test fun `a new activity from the activity's rewritten intent does not open the handled link again`() {
        gate.complete(Unit)
        val alpha = runBlocking { account("Alpha") }
        val controller = start(link(alpha))
        waitFor("onDetailOf('Alpha')") { onDetailOf("Alpha") }
        back(controller)
        waitFor("onOverview() && !shown('Remove account')") { onOverview() && !shown("Remove account") }

        val rewritten = Intent(controller.get().intent)
        controller.pause().stop().destroy()
        start(rewritten)
        waitFor("onOverview()") { onOverview() }
        assertFalse("the rewritten intent replayed the handled link", shown("Remove account"))
    }

    @Test fun `a launch from Recents with the original link intent opens nothing`() {
        gate.complete(Unit)
        val alpha = runBlocking { account("Alpha") }
        // Recents replays the task's first intent with the history flag; the activity ignores it.
        start(link(alpha).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        waitFor("onOverview()") { onOverview() }
        assertFalse("Recents replayed the link", shown("Remove account"))
    }

    @Test fun `a cold alert or whole-widget launch shows Overview`() {
        gate.complete(Unit)
        runBlocking { account("Alpha") }
        start(ExternalRequest.openOverview(app))
        waitFor("onOverview()") { onOverview() }
        assertFalse(shown("Remove account"))
    }

    @Test fun `a cold link with the store already read opens the account's detail`() {
        gate.complete(Unit)
        val alpha = runBlocking { account("Alpha") }
        // The store's first answer can be ready before the NavHost has composed its graph.
        runBlocking { container.repository.accountUsageOnce() }
        start(link(alpha))
        waitFor("onDetailOf('Alpha')") { onDetailOf("Alpha") }
    }

    @Test fun `the same link tapped again is a new request`() {
        gate.complete(Unit)
        val alpha = runBlocking { account("Alpha") }
        val controller = start(link(alpha))
        waitFor("onDetailOf('Alpha')") { onDetailOf("Alpha") }
        back(controller)
        waitFor("onOverview() && !shown('Remove account')") { onOverview() && !shown("Remove account") }

        controller.newIntent(link(alpha))
        waitFor("onDetailOf('Alpha')") { onDetailOf("Alpha") }
    }

    @Test fun `an alert or a whole-widget tap opens Overview over a detail and over another tab`() {
        gate.complete(Unit)
        val alpha = runBlocking { account("Alpha") }
        val controller = start(link(alpha))
        waitFor("onDetailOf('Alpha')") { onDetailOf("Alpha") }

        controller.newIntent(ExternalRequest.openOverview(app))
        waitFor("onOverview() && !shown('Remove account')") { onOverview() && !shown("Remove account") }

        compose.onNode(hasText("Settings") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        waitFor("onSettings()") { onSettings() }
        controller.newIntent(ExternalRequest.openOverview(app))
        waitFor("onOverview()") { onOverview() }
    }

    @Test fun `an ordinary reopen from the launcher stays where the app was`() {
        gate.complete(Unit)
        val alpha = runBlocking { account("Alpha") }
        val controller = start(link(alpha))
        waitFor("onDetailOf('Alpha')") { onDetailOf("Alpha") }

        controller.newIntent(launcher())
        waitFor("onDetailOf('Alpha')") { onDetailOf("Alpha") }
        // Nothing pending can still move it: the next frames show the same detail.
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertFalse("a launcher reopen went back to Overview", onOverview())
    }
}
