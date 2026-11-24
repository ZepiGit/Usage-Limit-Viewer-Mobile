package com.usagelimits.feature

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.credits.MemoryCredentialStore
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.ProviderRegistry
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.sync.Publication
import com.usagelimits.core.sync.resetTestWorkManager
import com.usagelimits.providers.credits.CreditRegistry
import com.usagelimits.providers.scriptedHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsagePublicationRoutingTest {
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val events = Channel<String>(Channel.UNLIMITED)
    private val viewModels = ViewModelStore()
    private lateinit var container: AppContainer
    private lateinit var vm: UsageViewModel
    private var failSync = false
    /** Percent left the provider reports, by external account id; absent reports no window. */
    private val readings = mutableMapOf<String, Double>()
    private var syncEntered: CompletableDeferred<Unit>? = null
    private var continueSync: CompletableDeferred<Unit>? = null
    private val provider = object : UsageProvider {
        override val providerId = ProviderId.CODEX
        override suspend fun beginLogin(): LoginChallenge = error("No sign-in in a refresh")
        override suspend fun completeLogin(challenge: LoginChallenge, userInput: String?):
            OAuthCredentials = error("No sign-in in a refresh")
        override suspend fun refresh(credentials: OAuthCredentials) = credentials
        override suspend fun fetchProfile(credentials: OAuthCredentials):
            ProviderProfile = error("No profile read in a refresh")
        override suspend fun fetchUsage(account: ProviderAccount,
            credentials: OAuthCredentials): UsageResult {
            syncEntered?.complete(Unit)
            continueSync?.await()
            if (failSync) throw ProviderException.Offline()
            return UsageResult(listOfNotNull(readings[account.externalAccountId]?.let { left ->
                UsageWindow("weekly", "Weekly", WindowCategory.WEEKLY, 100.0 - left, 604_800, null, exhausted = false)
            }))
        }
    }

    @Before fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = object : AppContainer(app) {
            override val providerRegistry = ProviderRegistry(mapOf(ProviderId.CODEX to provider))
            override val credentialStore = MemoryCredentialStore()
            override val creditRegistry = CreditRegistry(scriptedHttp(
                200 to """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"1.00"}]}"""))
            override val publication = object : Publication {
                override suspend fun cacheMoved() { events.send("cache moved") }
                override suspend fun presentationChanged() { events.send("presentation changed") }
                override suspend fun notificationsAllowedAgain() { events.send("notifications allowed again") }
            }
        }
        vm = UsageViewModel(container, app)
        viewModels.put("usage", vm)
    }

    @After fun tearDown() {
        viewModels.clear()
        runBlocking { vm.viewModelScope.coroutineContext[Job]!!.join() }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
        Dispatchers.resetMain()
    }

    @Test fun `refresh publishes once after a successful sync`() = assertRefreshPublication(failed = false, single = false)

    @Test fun `refresh repaints cached state after a failed sync`() = assertRefreshPublication(failed = true, single = false)

    @Test fun `refresh account publishes once after a successful sync`() = assertRefreshPublication(failed = false, single = true)

    @Test fun `refresh account repaints cached state after a failed sync`() = assertRefreshPublication(failed = true, single = true)

    private fun assertRefreshPublication(failed: Boolean, single: Boolean) = runBlocking {
        failSync = failed
        val account = container.repository.upsertFromLogin(ProviderId.CODEX, "refresh", "test@example.com",
            null, "Plus", emptyMap())
        container.credentialStore.save(account.credentialReference,
            OAuthCredentials("fixture", null, null, null))
        syncEntered = CompletableDeferred()
        continueSync = CompletableDeferred()
        val observing = launch { vm.state.collect {} }
        try {
            withTimeout(5_000) {
                vm.state.first { it.loaded }
                if (single) vm.refreshAccount(account.localId) else vm.refresh()
                syncEntered!!.await()
                vm.state.first { it.isRefreshing }
                continueSync!!.complete(Unit)
                assertEquals("cache moved", events.receive())
                vm.state.first { !it.isRefreshing }
            }
            assertEquals("A completed refresh must publish once", null, events.tryReceive().getOrNull())
        } finally {
            observing.cancelAndJoin()
        }
    }

    /**
     * Overview has no writer of its own any more (ADR 0004): a new reading reaches it through the
     * one cache event, reranks it, and leaves the stored order the pins follow untouched.
     */
    @Test fun `a refresh that changes the rank publishes once and keeps the stored order`() = runBlocking {
        val roomy = container.repository.upsertFromLogin(ProviderId.CODEX, "roomy", null, null, "Plus", emptyMap())
        val tight = container.repository.upsertFromLogin(ProviderId.CODEX, "tight", null, null, "Plus", emptyMap())
        for (account in listOf(roomy, tight)) {
            container.credentialStore.save(account.credentialReference, OAuthCredentials("fixture", null, null, null))
        }
        readings += mapOf("roomy" to 80.0, "tight" to 60.0)
        val observing = launch { vm.state.collect {} }
        try {
            withTimeout(5_000) {
                vm.state.first { it.loaded && it.accounts.size == 2 }
                vm.refresh()
                assertEquals("cache moved", events.receive())
                val first = vm.state.first { state -> !state.isRefreshing && state.accounts.all { it.snapshot != null } }
                assertEquals(listOf(tight.localId, roomy.localId), first.overviewAccountsAt(RANKED_AT).map { it.account.localId })

                readings["roomy"] = 5.0
                vm.refresh()
                assertEquals("cache moved", events.receive())
                val second = vm.state.first { state -> !state.isRefreshing &&
                    state.accounts.first { it.account.localId == roomy.localId }.snapshot?.windows?.single()?.remainingPercent == 5.0 }
                assertEquals("the tighter reading leads Overview", listOf(roomy.localId, tight.localId),
                    second.overviewAccountsAt(RANKED_AT).map { it.account.localId })
                assertEquals("the stored order is untouched", listOf(roomy.localId, tight.localId),
                    container.repository.accounts().map { it.localId })
            }
            assertEquals("A reading changes the order without a second publication", null, events.tryReceive().getOrNull())
        } finally {
            observing.cancelAndJoin()
        }
    }

    private companion object {
        /** The fixture windows have no reset, so the rank does not depend on the instant. */
        const val RANKED_AT = 0L
    }

    @Test fun `removing an account releases its pin and keeps the others in order`() = runBlocking {
        val kept = container.repository.upsertFromLogin(ProviderId.CODEX, "kept", null, null, "Plus", emptyMap())
        val removed = container.repository.upsertFromLogin(ProviderId.CODEX, "removed", null, null, "Plus", emptyMap())
        val other = container.repository.upsertFromLogin(ProviderId.CODEX, "other", null, null, "Plus", emptyMap())
        container.settingsStore.setPinnedAccounts(listOf(other.localId, removed.localId, kept.localId))

        vm.removeAccount(removed.localId)
        assertEquals("presentation changed", withTimeoutOrNull(5_000) { events.receive() })

        assertEquals(listOf(other.localId, kept.localId), container.settingsStore.settings.first().pinnedAccountIds)
    }

    @Test fun `presentation edits publish only presentation changed`() = runBlocking {
        val account = container.repository.upsertFromLogin(ProviderId.CODEX, "fixture", "test@example.com",
            null, "Plus", emptyMap())
        val credit = container.creditAccounts.add(CreditProviderId.DEEPSEEK, "fixture")
        // The stateIn observer lives until the ViewModel is cleared; only join edit jobs.
        val backgroundJobs = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        val changes: List<Pair<String, () -> Unit>> = listOf(
            "provider icon" to { vm.setProviderIcon(ProviderId.CODEX, "openai") },
            "display mode" to { vm.setPercentDisplay(PercentDisplay.USED) },
            "sync interval" to { vm.setSyncInterval(60) },
            "credit edit" to { vm.updateCredit(credit.id, "Work", BigDecimal.TEN) },
            "credit removal" to { vm.removeCredit(credit.id) },
            "status icons on" to { vm.setStatusIcons(true) },
            "pinned account" to { vm.setPinned(account.localId, true) },
            "account removal" to { vm.removeAccount(account.localId) },
        )
        for ((label, change) in changes) {
            change()
            assertEquals(label, "presentation changed", withTimeoutOrNull(5_000) { events.receive() })
        }
        withTimeout(5_000) {
            vm.viewModelScope.coroutineContext[Job]!!.children
                .filterNot { it in backgroundJobs }.toList().joinAll()
        }
        assertEquals("Presentation edits must not publish an extra event", null, events.tryReceive().getOrNull())
    }
}
