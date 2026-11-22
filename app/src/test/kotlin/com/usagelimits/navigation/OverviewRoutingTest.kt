package com.usagelimits.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import kotlinx.coroutines.Job
import com.usagelimits.feature.UsageViewModel
import com.usagelimits.core.sync.Publication
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.credits.MemoryCredentialStore
import com.usagelimits.core.database.ControlledAccountReads
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import com.usagelimits.core.settings.OverviewCreditLayout
import java.math.BigDecimal
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.ProviderRegistry
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import androidx.lifecycle.viewModelScope
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.SynchronousExecutor
import androidx.work.Configuration
import com.usagelimits.UsageLimitsApp
import com.usagelimits.R
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.sync.resetTestWorkManager
import com.usagelimits.feature.waitDrained
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The app shell with the real Room store and view model: three tabs, account links that resolve
 * cold and warm, an unavailable account with a way back, removal, and the filter that goes back to
 * All when another tab is chosen. Motion is off so each step lands on its frame.
 *
 * The publication is a recorder that says when it was asked to publish, so a removal is awaited
 * to its end (the account row gone, then its pin released and the presentation published). The
 * view model lives in a store this test owns; its jobs end, also when an assertion failed, before
 * the test WorkManager is reset.
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
    private var request by mutableStateOf<ExternalRequest?>(null)
    private val tab = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
    private lateinit var container: AppContainer
    private lateinit var accountReads: ControlledAccountReads
    private var controlAccountReads = false
    private val signInBegins = java.util.concurrent.atomic.AtomicInteger()
    private val loginChallenge: LoginChallenge = LoginChallenge.ApiKey("https://example.test", "Synthetic key")
    private lateinit var initialSettings: com.usagelimits.core.settings.AppSettings

    private fun provider(id: ProviderId) = object : UsageProvider {
        override val providerId = id
        override suspend fun beginLogin(): LoginChallenge {
            signInBegins.incrementAndGet()
            return loginChallenge
        }
        override suspend fun completeLogin(challenge: LoginChallenge, userInput: String?) = OAuthCredentials("synthetic", null, null, null)
        override suspend fun refresh(credentials: OAuthCredentials) = credentials
        override suspend fun fetchProfile(credentials: OAuthCredentials) = ProviderProfile("different-identity", null, "Second identity", "Plus")
        override suspend fun fetchUsage(account: ProviderAccount, credentials: OAuthCredentials) = UsageResult(emptyList())
    }
    /** Presentation publications asked for since the test began. */
    private val presentations = java.util.concurrent.atomic.AtomicInteger()

    @Before fun setUp() {
        runBlocking { app.startupJob?.join() }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        resetTestWorkManager()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        container = object : AppContainer(app) {
            override val repository by lazy {
                accountReads = ControlledAccountReads(database.accountDao())
                UsageRepository(if (controlAccountReads) accountReads else database.accountDao(), database.usageSnapshotDao(), transactions = transactions)
            }
            override val credentialStore = MemoryCredentialStore()
            override val providerRegistry = ProviderRegistry(listOf(ProviderId.CODEX, ProviderId.ZAI, ProviderId.OPENCODE)
                .associateWith { provider(it) })
            override val publication: Publication = object : Publication {
                override suspend fun cacheMoved() = Unit
                override suspend fun presentationChanged() { presentations.incrementAndGet() }
                override suspend fun notificationsAllowedAgain() = Unit
            }
        }
        initialSettings = runBlocking { container.settingsStore.settings.first() }
    }

    @After fun tearDown() {
        try {
            // The navigation's view model ends with the store; its cancelled jobs finish on this
            // thread, so the looper is pumped until they have, before WorkManager goes.
            val jobs = store.keys().mapNotNull { store[it] }.map { it.viewModelScope.coroutineContext[Job]!! }
            compose.runOnUiThread { store.clear() }
            val deadline = System.currentTimeMillis() + 10_000
            while (jobs.any { !it.isCompleted }) {
                check(System.currentTimeMillis() < deadline) { "the view model's jobs did not end" }
                ShadowLooper.idleMainLooper()
                runBlocking { kotlinx.coroutines.yield() }
            }
        } finally {
            lateinit var observer: Job
            compose.runOnUiThread { observer = app.observeStatusIconEligibility() }
            compose.waitDrained { observer.isCompleted }
            runBlocking {
                container.settingsStore.setOverviewLayout(initialSettings.overviewLayout)
                container.settingsStore.setSyncInterval(initialSettings.syncIntervalMinutes)
                container.settingsStore.setOverviewCreditLayout(initialSettings.overviewCreditLayout)
                container.settingsStore.setOverviewShowCredits(initialSettings.overviewShowCredits)
            }
            WorkManager.getInstance(app).cancelAllWork().result.get()
            ShadowLooper.idleMainLooper()
            resetTestWorkManager()
        }
    }

    private fun open(accountId: String) {
        request = ExternalRequest.OpenAccount(accountId)
    }

    private suspend fun account(name: String, left: Double, provider: ProviderId = ProviderId.CODEX, expired: Boolean = false) = container.repository
        .upsertFromLogin(provider, name, null, name, "Plus", emptyMap()).also {
            val now = System.currentTimeMillis()
            container.repository.saveSnapshot(UsageSnapshot(it.localId, now, SnapshotStatus.OK, listOf(
                UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 100.0 - left, 604_800, now + 3_600_000, false)),
                connectionStatus = if (expired) ConnectionStatus.RECONNECT_REQUIRED else ConnectionStatus.CONNECTED))
        }

    private fun showShell(now: () -> Long = System::currentTimeMillis) {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalMotionEnabled provides false) {
                UsageLimitsTheme {
                    UsageLimitsNavigation(container, WindowSizeClass.calculateFromSize(DpSize(393.dp, 800.dp)),
                        request = request, onRequestHandled = { handled -> if (request === handled) request = null },
                        now = now)
                }
            }
        }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Drains main-looper results and draws controlled frames; real time only bounds a hung read. */
    private fun settle(condition: () -> Boolean = { true }) {
        val deadline = System.currentTimeMillis() + 10_000
        do {
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeByFrame()
            if (condition()) return
        } while (System.currentTimeMillis() < deadline)
        throw AssertionError("not reached")
    }

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
        open(alpha.localId)
        showShell()
        compose.waitDrained { shown("Remove account") }
        assertEquals("the request is consumed once", null, request)
    }

    @Test fun `a warm link opens the account over a filter that hides it, and back keeps that filter`() {
        val alpha = runBlocking { account("Alpha", 60.0) }
        showShell()
        compose.waitDrained { shown("Needs attention") }
        compose.onNode(hasText("Needs attention")).performClick()
        compose.waitDrained { shown("No accounts match this filter.") }
        compose.runOnUiThread { open(alpha.localId) }
        compose.waitDrained { shown("Remove account") }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitDrained { shown("No accounts match this filter.") }
    }

    @Test fun `a link to a removed account says so and leads back to Overview`() {
        runBlocking { account("Alpha", 60.0) }
        open("removed-account")
        showShell()
        compose.waitDrained { shown("This account is no longer available.") }
        assertEquals("not a spinner, not the wrong account", false, shown("Remove account"))
        compose.onNodeWithText("Back to Overview").performClick()
        compose.waitDrained { shown("All") && shown("Needs attention") }
    }

    @Test fun `removing an account from its detail returns to Overview without it`() {
        val alpha = runBlocking { account("Alpha", 60.0) }
        runBlocking { account("Bravo", 40.0) }
        open(alpha.localId)
        showShell()
        compose.waitDrained { shown("Remove account") }
        val before = presentations.get()
        compose.onNodeWithText("Remove account").performClick()
        compose.waitDrained { shown("Remove") && shown("Remove account?") }
        compose.onNodeWithText("Remove").performClick()
        // The whole removal: the row is gone, and its pin was released and the presentation
        // published, which is the operation's last step.
        compose.waitDrained {
            runBlocking { container.repository.account(alpha.localId) } == null && presentations.get() > before
        }
        compose.waitDrained { shown("Needs attention") && !shown("Alpha") }
        assertEquals("back on Overview, not on an unavailable detail", false, shown("This account is no longer available."))
    }

    @Test fun `a shorter sync interval re-plans the clock to the new stale boundary, before the next tick`() {
        // The wall clock is the compose main clock plus a base, so the test moves both together.
        // The reading was fetched 44 min 50 s before the base. At the default 30-minute interval it
        // turns stale after 60 minutes, so the clock plans the 30 s tick. 5 s in, the interval drops
        // to 15 minutes: stale after 45, which is 5 s later. Only a re-plan wakes the clock then;
        // without it the screen turns stale at the tick, 25 s later.
        compose.mainClock.autoAdvance = false
        val fetched = 1_800_000_000_000L
        val base = fetched + 45 * 60_000L - 10_000L
        val start = compose.mainClock.currentTime
        val alpha = runBlocking {
            container.settingsStore.setSyncInterval(30)
            container.repository.upsertFromLogin(ProviderId.CODEX, "Alpha-${System.nanoTime()}", null, "Alpha", "Plus", emptyMap())
                .also {
                    container.repository.saveSnapshot(UsageSnapshot(it.localId, fetched, SnapshotStatus.OK, listOf(
                        UsageWindow("week", "Weekly", WindowCategory.WEEKLY, 40.0, 604_800, fetched + 7 * 86_400_000L, false))))
                }
        }
        val clockThreshold = java.util.concurrent.atomic.AtomicLong(-1)
        showShell {
            store.keys().mapNotNull { store[it] as? UsageViewModel }.singleOrNull()?.let {
                clockThreshold.set(it.state.value.staleAfterMs)
            }
            base + compose.mainClock.currentTime - start
        }

        fun advance(ms: Long) {
            compose.mainClock.advanceTimeBy(ms)
            ShadowLooper.idleMainLooper()
        }

        settle { shown("Weekly") && clockThreshold.get() == 60 * 60_000L }
        val loaded = compose.mainClock.currentTime - start
        assertEquals("fresh under the 60-minute threshold", false, shown("Stale"))
        advance(5_000 - loaded)

        // No tick is due yet: another read of the injected clock acknowledges the shell's
        // threshold-change effect, not merely the write reaching DataStore.
        runBlocking { container.settingsStore.setSyncInterval(15) }
        settle {
            check(compose.mainClock.currentTime - start < 10_000) { "the shell did not re-plan before the boundary" }
            clockThreshold.get() == 45 * 60_000L
        }
        settle()
        assertTrue("the clock acknowledged the change before its new boundary", compose.mainClock.currentTime - start < 10_000)
        assertEquals("not stale before the new boundary", false, shown("Stale"))
        advance(10_000 - (compose.mainClock.currentTime - start) + 200)
        settle()
        assertTrue("stale at the new boundary, not at the tick 25 s later", shown("Stale"))
    }

    @Test fun `compact credits drop the low balance hint at the re-planned stale boundary without a sync`() {
        compose.mainClock.autoAdvance = false
        val fetched = 1_800_000_000_000L
        val base = fetched + 45 * 60_000L - 10_000L
        val start = compose.mainClock.currentTime
        val creditId = "credit-stale-boundary-test"
        runBlocking {
            container.settingsStore.setSyncInterval(30)
            container.settingsStore.setOverviewCreditLayout(OverviewCreditLayout.COMPACT)
            container.settingsStore.setOverviewShowCredits(true)
            container.creditRepository.upsert(
                CreditAccount(creditId, CreditProviderId.FAL,
                    "Boundary credit", "credit:synthetic", createdAt = fetched),
                CreditSnapshot(creditId,
                    listOf(CreditBalance("USD", BigDecimal("1"))), fetched),
            )
        }
        val clockThreshold = java.util.concurrent.atomic.AtomicLong(-1)
        val lowHint = hasContentDescription("Low balance: Boundary credit", substring = true)
        fun hasLowHint() = compose.onAllNodes(lowHint).fetchSemanticsNodes().isNotEmpty()
        try {
            showShell {
                store.keys().mapNotNull { store[it] as? UsageViewModel }.singleOrNull()?.let {
                    clockThreshold.set(it.state.value.staleAfterMs)
                }
                base + compose.mainClock.currentTime - start
            }
            settle { hasLowHint() && clockThreshold.get() == 60 * 60_000L }
            compose.mainClock.advanceTimeBy(5_000 - (compose.mainClock.currentTime - start))
            runBlocking { container.settingsStore.setSyncInterval(15) }
            settle {
                check(compose.mainClock.currentTime - start < 10_000) { "credit boundary was not re-planned in time" }
                clockThreshold.get() == 45 * 60_000L
            }
            settle()
            assertTrue("the current low balance remains visible before the new boundary", hasLowHint())
            compose.mainClock.advanceTimeBy(10_000 - (compose.mainClock.currentTime - start) + 200)
            settle()
            assertEquals("the hint expires at the credit boundary, before the old countdown tick", false, hasLowHint())
            assertTrue("the credit account remains counted", compose.onAllNodes(
                hasContentDescription("Credits · 1 balance", substring = true)).fetchSemanticsNodes().isNotEmpty())
        } finally {
            runBlocking { container.creditRepository.remove(creditId) }
        }
    }

    @Test fun `Overview says the account store is unreadable and its retry resumes the live account flow`() {
        controlAccountReads = true
        val alpha = runBlocking { account("Alpha", 60.0) }
        runBlocking { accountReads.fail() }
        showShell()
        compose.waitDrained { shown("Can't read accounts") }
        assertEquals(false, shown("Nothing to monitor yet"))
        assertEquals(false, shown("No accounts yet"))
        compose.onNodeWithText("Try again").performClick()
        runBlocking { accountReads.succeed() }
        compose.waitDrained { shown("Alpha") && shown("Needs attention") }
        assertEquals(false, shown("Can't read accounts"))
        compose.runOnUiThread { open(alpha.localId) }
        compose.waitDrained { shown("Remove account") }
        assertEquals(alpha.localId, runBlocking { container.repository.accounts().single().localId })
    }

    @Test fun `a cold account link shows storage failure not removal and recovers in the same detail`() {
        controlAccountReads = true
        val alpha = runBlocking { account("Alpha", 60.0) }
        runBlocking { accountReads.fail() }
        open(alpha.localId)
        showShell()
        compose.waitDrained { shown("Can't read accounts") && shown("Back to Overview") }
        assertEquals("the unreadable request is handled once", null, request)
        assertEquals(false, shown("This account is no longer available."))
        compose.onNodeWithText("Try again").performClick()
        runBlocking { accountReads.succeed() }
        compose.waitDrained { shown("Remove account") && shown("Alpha") }
        assertEquals(false, shown("Can't read accounts"))
    }

    @Test fun `ordinary Add account still opens the provider picker`() {
        showShell()
        val add = app.getString(R.string.overview_add_account)
        compose.waitDrained { shown(add) }
        compose.onNodeWithText(add).performClick()
        compose.waitDrained { shown(app.getString(R.string.add_pick_intro)) }
        assertEquals(0, signInBegins.get())
    }

    @Test fun `Reconnect starts the provider key step and a different identity adds a second account`() = reconnectWithKey()

    @Test
    @Config(qualifiers = "de-w393dp-h1600dp-xhdpi")
    fun `Reconnect has the same provider route in German and leaves the old identity intact`() = reconnectWithKey()

    private fun reconnectWithKey() {
        val original = runBlocking { account("Original identity", 60.0, ProviderId.OPENCODE, expired = true) }
        open(original.localId)
        showShell()
        val reconnect = app.getString(R.string.account_reconnect)
        compose.waitDrained { shown(reconnect) }
        compose.onNodeWithText(reconnect).performClick()
        val keyTitle = app.getString(R.string.add_paste_key_title, ProviderId.OPENCODE.displayName)
        compose.waitDrained { shown(keyTitle) }
        assertEquals("Reconnect must not show the provider picker", false, shown(app.getString(R.string.add_pick_intro)))
        assertEquals(1, signInBegins.get())
        compose.onNode(hasSetTextAction()).performTextReplacement("synthetic-key")
        compose.onNodeWithText(app.getString(R.string.add_connect)).performClick()
        compose.waitDrained { shown(app.getString(R.string.add_connected)) }
        val accounts = runBlocking { container.repository.accounts() }
        assertEquals("a different sign-in is a second account, not a replacement by provider", 2, accounts.size)
        val kept = accounts.single { it.localId == original.localId }
        assertEquals(original.externalAccountId, kept.externalAccountId)
        assertEquals(original.credentialReference, kept.credentialReference)
        assertEquals(original.createdAt, kept.createdAt)
        assertEquals(setOf(original.externalAccountId, "different-identity"), accounts.map { it.externalAccountId }.toSet())
        assertEquals(setOf(ProviderId.OPENCODE), accounts.map { it.provider }.toSet())
        compose.onNodeWithText(app.getString(R.string.action_done)).performClick()
        compose.waitDrained { shown(reconnect) }
    }

    @Test fun `Reconnect starts a regional provider at its region choice not the provider picker`() {
        val original = runBlocking { account("Regional identity", 60.0, ProviderId.ZAI, expired = true) }
        open(original.localId)
        showShell()
        compose.waitDrained { shown("Reconnect account") }
        compose.onNodeWithText("Reconnect account").performClick()
        compose.waitDrained { shown(app.getString(R.string.add_region_title, ProviderId.ZAI.displayName)) }
        assertEquals(0, signInBegins.get())
        assertEquals(false, shown(app.getString(R.string.add_pick_intro)))
        compose.onNodeWithText("International (z.ai)").performClick()
        compose.waitDrained { shown(app.getString(R.string.add_paste_key_title, ProviderId.ZAI.displayName)) }
        assertEquals(1, signInBegins.get())
        compose.onNodeWithText("Cancel").performClick()
        compose.waitDrained { shown("Reconnect account") }
    }

    @Test fun `changing the saved Overview layout List to Dials to List resets a filtered list to All`() {
        runBlocking { account("Alpha", 60.0) }
        showShell()
        compose.waitDrained { shown("Needs attention") }
        compose.onNodeWithText("Needs attention").performClick()
        compose.waitDrained { shown("No accounts match this filter.") }
        compose.onNode(hasText("Settings") and tab).performClick()
        compose.waitDrained { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Dials"))
        compose.onNode(hasText("Dials") and hasClickAction()).performClick()
        compose.waitDrained { compose.onAllNodes(hasText("Dials") and
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Overview") and tab).performClick()
        compose.waitDrained { shown("Each dial shows the account’s headline. Tap one for every window and its resets.") }
        assertEquals(false, shown("Needs attention"))
        compose.onNode(hasText("Settings") and tab).performClick()
        compose.waitDrained { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("List"))
        compose.onNode(hasText("List") and hasClickAction()).performClick()
        compose.waitDrained { compose.onAllNodes(hasText("List") and
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Overview") and tab).performClick()
        compose.waitDrained { shown("Alpha") && compose.onAllNodes(hasText("All") and
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(false, shown("No accounts match this filter."))
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
