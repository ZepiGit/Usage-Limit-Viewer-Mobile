package com.usagelimits.navigation

import android.Manifest
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.OverviewCreditLayout
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import android.content.Context
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
import com.usagelimits.core.settings.OverviewLayout
import com.usagelimits.core.sync.resetTestWorkManager
import com.usagelimits.feature.UsageViewModel
import com.usagelimits.feature.waitDrained
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
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
    private lateinit var initialSettings: AppSettings
    private val controllers = mutableListOf<ActivityController<UsageLimitsActivity>>()
    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
    private val compactCredits = hasContentDescription("Credits · 1 balance", substring = true)

    /** Account rows wait for [gate]; snapshots and writes do not. */
    private class GatedAccounts(private val real: AccountDao, private val gate: CompletableDeferred<Unit>) : AccountDao by real {
        override fun observeAll() = real.observeAll().onStart { gate.await() }
    }

    @Before fun setUp() {
        runBlocking { app.startupJob?.join() }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        resetTestWorkManager()
        // Resuming the real activity requests a sync; keep that path offline in navigation tests.
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                    if (workerClassName == com.usagelimits.core.sync.SyncWorker::class.java.name) object : CoroutineWorker(appContext, workerParameters) {
                        override suspend fun doWork() = Result.success()
                    } else null
            }).build())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        original = app.container
        container = object : AppContainer(app) {
            override val repository: UsageRepository by lazy {
                UsageRepository(GatedAccounts(database.accountDao(), gate), database.usageSnapshotDao(), transactions = transactions)
            }
        }
        ReflectionHelpers.setField(app, "container", container)
        initialSettings = runBlocking { container.settingsStore.settings.first() }
        runBlocking { container.settingsStore.recordNotificationPermissionHandled() }
    }

    @After fun tearDown() {
        gate.complete(Unit)
        try {
            // The activities end first: their view model's jobs are cancelled and run out on this
            // thread, so the looper is pumped until they have.
            for (controller in controllers.asReversed()) stop(controller)
        } finally {
            runBlocking {
                container.settingsStore.setOverviewCreditLayout(initialSettings.overviewCreditLayout)
                container.settingsStore.setOverviewShowFirst(initialSettings.overviewShowFirst)
                container.settingsStore.setOverviewShowCredits(initialSettings.overviewShowCredits)
                container.creditRepository.remove("credit-fal-test")
            }
            ReflectionHelpers.setField(app, "container", original)
            WorkManager.getInstance(app).cancelAllWork().result.get()
            ShadowLooper.idleMainLooper()
            resetTestWorkManager()
        }
    }

    private fun stop(controller: ActivityController<UsageLimitsActivity>) {
        val activity = controller.get()
        if (activity.isDestroyed) return
        val jobs = listOfNotNull(viewModelJob(activity), activity.notificationPermissionCheck)
        controller.pause().stop().destroy()
        val deadline = System.currentTimeMillis() + 10_000
        while (jobs.any { !it.isCompleted }) {
            check(System.currentTimeMillis() < deadline) { "the activity's jobs did not end" }
            ShadowLooper.idleMainLooper()
            runBlocking { kotlinx.coroutines.yield() }
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

    private suspend fun credit(name: String = "Synthetic credit") = CreditAccount(
        id = "credit-fal-test", provider = CreditProviderId.FAL, label = name, credentialReference = "credit:test",
        createdAt = System.currentTimeMillis(),
    ).also { account ->
        val now = System.currentTimeMillis()
        container.creditRepository.upsert(
            account,
            com.usagelimits.core.model.CreditSnapshot(
                account.id,
                listOf(com.usagelimits.core.model.CreditBalance("USD", BigDecimal("24.50"), BigDecimal("100"))),
                now,
            ),
        )
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
    private fun onCreditManagement() = !onOverview() && !onSettings() && shown("Manage credit accounts") && shown("Add credit balance")
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

    @Test fun `selecting Dials in Settings survives a fresh activity launch`() {
        gate.complete(Unit)
        runBlocking {
            container.settingsStore.setOverviewLayout(OverviewLayout.LIST)
            account("Alpha")
        }
        val first = start(launcher())
        try {
            waitFor("List filters") { shown("Needs attention") }
            compose.onNode(hasText("Settings") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
                .performClick()
            waitFor("Settings tab") { onSettings() }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Dials"))
            compose.onNode(hasText("Dials") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
            waitFor("Dials selected") { compose.onAllNodes(hasText("Dials") and selected).fetchSemanticsNodes().isNotEmpty() }
            stop(first)
            start(launcher())
            waitFor("Dials on the new Overview") { onOverview() && shown("Each dial shows the account’s headline. Tap one for every window and its resets.") }
            assertFalse(shown("Needs attention"))
            compose.onNode(hasContentDescription("OpenAI Codex", substring = true)).performClick()
            waitFor("account detail from the dial") { onDetailOf("Alpha") }
        } finally {
            runBlocking { container.settingsStore.setOverviewLayout(OverviewLayout.LIST) }
        }
    }

    @Test fun `credit-only Overview tracks from C1 and both Manage routes return compact after leaving`() {
        gate.complete(Unit)
        val controller = start(launcher())
        waitFor("credit-only C1 Track action") { shown("Track a prepaid credit balance") }
        compose.onNode(hasText("Track a prepaid credit balance") and hasClickAction()).performClick()
        waitFor("Add credit balance") { shown("Add credit balance") }
        back(controller)
        waitFor("credit-only C1 Overview") { onOverview() && shown("Track a prepaid credit balance") }
        runBlocking { credit() }
        waitFor("credit account in Overview") { shown("Synthetic credit") }

        compose.onNode(hasText("Manage credit accounts") and hasClickAction()).performClick()
        waitFor("Credit accounts from Normal Manage") { onCreditManagement() }
        back(controller)
        waitFor("Normal Overview after Manage") { onOverview() && shown("Manage credit accounts") }

        runBlocking { container.settingsStore.setOverviewCreditLayout(OverviewCreditLayout.COMPACT) }
        waitFor("compact balance summary") { compose.onAllNodes(compactCredits).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(compactCredits and hasClickAction()).performClick()
        waitFor("expanded credits") { shown("Collapse") }
        compose.onNode(hasText("Manage credit accounts") and hasClickAction()).performClick()
        waitFor("Credit accounts from expanded Manage") { onCreditManagement() }
        back(controller)
        waitFor("compact Overview after leaving expanded Manage") { onOverview() && compose.onAllNodes(compactCredits).fetchSemanticsNodes().isNotEmpty() && !shown("Collapse") }
    }

    @Test fun `hidden credits remain manageable and saved preferences restore in a new activity`() {
        gate.complete(Unit)
        val before = runBlocking { container.settingsStore.settings.first() }
        runBlocking {
            account("Usage account")
            credit()
            container.repository.accountUsageOnce()
        }
        try {
            val first = start(launcher())
            waitFor("initial Overview") { onOverview() }
            compose.onNode(hasText("Settings") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
            waitFor("Settings tab") { onSettings() }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Compact"))
            compose.onNode(hasText("Compact") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
            waitFor("Compact selected") { compose.onAllNodes(hasText("Compact") and selected).fetchSemanticsNodes().isNotEmpty() }
            val creditFirst = hasText("Credit accounts") and hasClickAction()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(creditFirst)
            compose.onNode(creditFirst).performSemanticsAction(SemanticsActions.OnClick) { it() }
            waitFor("Credit accounts first") { compose.onAllNodes(hasText("Credit accounts") and selected).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Show credits"))
            compose.onNode(hasContentDescription("Show credits") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNode(hasText("Overview") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
            waitFor("Overview with credits hidden") { onOverview() && !shown("Manage credit accounts") }
            compose.onNode(hasText("Settings") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
            waitFor("Settings with credits hidden") { onSettings() }
            val manageCredits = hasText("Manage credit accounts") and hasClickAction()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(manageCredits)
            compose.onNode(manageCredits).performSemanticsAction(SemanticsActions.OnClick) { it() }
            waitFor("Credit accounts from Settings while hidden") { onCreditManagement() }
            compose.onNode(hasText("Synthetic credit") and hasClickAction()).performClick()
            waitFor("synthetic credit detail") { shown("Remove credit account") && shown("Synthetic credit") }
            compose.onNode(hasContentDescription("Name") and hasSetTextAction()).performTextReplacement("Synthetic credit edited")
            compose.onNode(hasText("Save") and hasClickAction()).performClick()
            waitFor("saved credit edit") { onCreditManagement() && shown("Synthetic credit edited") }

            stop(first)
            val restored = start(launcher())
            waitFor("new activity restored hidden credits") { onOverview() && !shown("Synthetic credit edited") }
            compose.onNode(hasText("Settings") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
            waitFor("restored Settings") { onSettings() }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Show credits"))
            val showCredits = compose.onNode(hasContentDescription("Show credits") and hasClickAction())
            showCredits.assertIsOff()
            showCredits.performSemanticsAction(SemanticsActions.OnClick) { it() }
            waitFor("restored Compact selection") {
                compose.onAllNodes(hasText("Compact") and selected).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText("Overview") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
            waitFor("restored compact and credit-first preferences") {
                onOverview() && compose.onAllNodes(compactCredits).fetchSemanticsNodes().isNotEmpty() && shown("Usage account")
            }
            val creditTop = compose.onNode(compactCredits).fetchSemanticsNode().boundsInRoot.top
            val usageTop = compose.onNode(hasText("Usage account"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            org.junit.Assert.assertTrue("credit block did not precede usage providers", creditTop < usageTop)

            compose.onNode(hasText("Settings") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
            waitFor("Settings before removal") { onSettings() }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(manageCredits)
            compose.onNode(manageCredits).performSemanticsAction(SemanticsActions.OnClick) { it() }
            waitFor("Credit accounts after restore") { onCreditManagement() && shown("Synthetic credit edited") }
            compose.onNode(hasText("Synthetic credit edited") and hasClickAction()).performClick()
            waitFor("edited credit detail") { shown("Remove credit account") && shown("Synthetic credit edited") }
            compose.onNode(hasText("Remove credit account") and hasClickAction()).performClick()
            waitFor("credit removal confirmation") { shown("Remove credit account?") }
            compose.onNode(hasText("Remove") and hasClickAction()).performClick()
            waitFor("credit removed") { onCreditManagement() && !shown("Synthetic credit edited") }
            stop(restored)
        } finally {
            runBlocking {
                container.settingsStore.setOverviewCreditLayout(before.overviewCreditLayout)
                container.settingsStore.setOverviewShowFirst(before.overviewShowFirst)
                container.settingsStore.setOverviewShowCredits(before.overviewShowCredits)
            }
        }
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
