package com.usagelimits.core.sync

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.credits.MemoryCredentialStore
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.network.ProviderException
import com.usagelimits.core.sync.SyncWorker.Companion.PERIODIC_WORK_NAME as PERIODIC
import com.usagelimits.core.sync.SyncWorker.Companion.SHORT_WORK_NAME as SHORT
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.ProviderRegistry
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * How the sync triggers land in WorkManager.
 *
 * The clock-driven trigger — the system's widget tick, the presentation repaint — must share
 * the one-shot's unique name, so a tick a moment after the user tapped refresh does not queue
 * a second pass, and a tap a moment after a tick does not either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
class SyncWorkerSchedulingTest {

    private val context: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val manager: WorkManager get() = WorkManager.getInstance(context)
    private lateinit var originalContainer: AppContainer
    private var originalInterval = 0

    @Before
    fun initWorkManager() = runBlocking {
        context.startupJob?.join()
        originalContainer = context.container
        originalInterval = originalContainer.settingsStore.settings.first().syncIntervalMinutes
        manager.cancelAllWork().result.get()
        resetTestWorkManager()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    /**
     * Nothing this class opened may still be running when the next class sets up: an open
     * WorkManager database races the native runtime set-up of the following sandbox, and the
     * symptom lands in whichever test comes next. See UsageLimitsApp.startupJob.
     */
    @After
    fun closeWorkManager() = runBlocking {
        try {
            manager.cancelAllWork().result.get()
            originalContainer.settingsStore.setSyncInterval(originalInterval)
        } finally {
            ReflectionHelpers.setField(context, "container", originalContainer)
            resetTestWorkManager()
        }
    }

    private suspend fun schedule(minutes: Int) {
        context.container.settingsStore.setSyncInterval(minutes)
        SyncWorker.scheduleBackgroundSync(context)
    }

    private fun oneShots(): List<WorkInfo> = manager.getWorkInfosForUniqueWork(SyncWorker.ONE_SHOT_WORK_NAME).get()

    private fun active(name: String) = manager.getWorkInfosForUniqueWork(name).get().filterNot { it.state.isFinished }

    private fun start(id: UUID) {
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(id)
        driver.setInitialDelayMet(id)
    }

    private suspend fun runWork(request: WorkInfo): WorkInfo {
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(request.id)
        if (request.initialDelayMillis > 0) driver.setInitialDelayMet(request.id)
        return withTimeout(10_000) {
            manager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }!!
        }
    }

    private suspend fun installProvider(
        published: CompletableDeferred<Unit>? = null,
        fetch: suspend () -> UsageResult,
    ): ProviderAccount {
        val provider = object : UsageProvider {
            override val providerId = ProviderId.CODEX
            override suspend fun beginLogin(): LoginChallenge = error("No login during sync")
            override suspend fun completeLogin(challenge: LoginChallenge, userInput: String?): OAuthCredentials =
                error("No login during sync")
            override suspend fun refresh(credentials: OAuthCredentials) = credentials
            override suspend fun fetchProfile(credentials: OAuthCredentials): ProviderProfile =
                error("No profile read during sync")
            override suspend fun fetchUsage(account: ProviderAccount, credentials: OAuthCredentials) = fetch()
        }
        val container = object : AppContainer(context) {
            override val providerRegistry = ProviderRegistry(mapOf(provider.providerId to provider))
            override val credentialStore = MemoryCredentialStore()
            override val publication = object : Publication {
                override suspend fun cacheMoved() { published?.complete(Unit) }
                override suspend fun presentationChanged() = Unit
                override suspend fun notificationsAllowedAgain() = Unit
            }
        }
        ReflectionHelpers.setField(context, "container", container)
        val account = container.repository.upsertFromLogin(provider.providerId, "short-sync", null, null, "Plus", emptyMap())
        container.credentialStore.save(account.credentialReference, OAuthCredentials("fixture", null, null, null))
        return account
    }

    @Test
    fun `cold background startup schedules one read rather than starting another pass`() = runBlocking {
        val reads = AtomicInteger()
        installProvider {
            reads.incrementAndGet()
            UsageResult(emptyList())
        }
        context.container.settingsStore.setSyncInterval(5)
        context.scheduleOnStartup()

        // Release every request that boot reserved; a process-only start must not add a sync-now.
        val scheduled = active(SHORT).single()
        for (request in active(SyncWorker.ONE_SHOT_WORK_NAME) + scheduled) runWork(request)
        assertEquals("provider reads from one cold background wakeup", 1, reads.get())

        SyncWorker.syncOnOpen(context)
        runWork(active(SyncWorker.ONE_SHOT_WORK_NAME).single())
        assertEquals("a qualifying opening still refreshes even immediately after a read", 2, reads.get())
    }

    @Test
    fun `a failed startup settings read still schedules the default interval`() = runBlocking {
        SyncWorker.scheduleBackgroundSync(context, flow { throw IOException("fixture read failure") })

        val periodic = active(PERIODIC).single()
        assertEquals(1_800_000L, periodic.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun `five minute scheduling keeps one delayed network constrained pass across reconciliation`() = runBlocking {
        schedule(5)
        val first = active(SHORT).single().id
        SyncWorker.scheduleBackgroundSync(context)

        val pending = active(SHORT).single()
        assertEquals(first, pending.id)
        assertEquals(300_000L, pending.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, pending.constraints.requiredNetworkType)
        assertTrue(active(PERIODIC).isEmpty())
    }

    @Test
    fun `a failed short pass records the failure and leaves exactly one delayed successor`() = runBlocking {
        val account = installProvider { throw ProviderException.Offline() }
        runShortPass(SnapshotStatus.FAILED, account)
    }

    @Test
    fun `a successful short pass records the reading and leaves exactly one delayed successor`() = runBlocking {
        val account = installProvider { UsageResult(emptyList()) }
        runShortPass(SnapshotStatus.OK, account)
    }

    private suspend fun runShortPass(expected: SnapshotStatus, account: ProviderAccount) {
        schedule(5)
        val first = active(SHORT).single()
        assertEquals(WorkInfo.State.SUCCEEDED, runWork(first).state)
        val snapshot = context.container.repository.accountUsageOnce().single { it.account.localId == account.localId }.snapshot
        assertEquals(expected, snapshot?.status)
        val pending = active(SHORT)
        assertEquals("one delayed successor after a $expected pass", 1, pending.size)
        assertEquals(300_000L, pending.single().initialDelayMillis)
        assertNotEquals(first.id, pending.single().id)
    }

    @Test
    fun `changing a stored short interval replaces its pending delay`() = runBlocking {
        schedule(10)
        val previous = active(SHORT).single()
        assertEquals(600_000L, previous.initialDelayMillis)

        schedule(5)
        val updated = active(SHORT).single()
        assertNotEquals(previous.id, updated.id)
        assertEquals(300_000L, updated.initialDelayMillis)
    }

    @Test
    fun `switching between short and periodic cancels every reservation in the other mode`() = runBlocking {
        schedule(30)
        assertEquals(1, active(PERIODIC).size)
        schedule(5)
        assertTrue("periodic work remains after switching to short", active(PERIODIC).isEmpty())
        assertEquals(1, active(SHORT).size)

        schedule(15)
        assertTrue("short work remains after switching to periodic", active(SHORT).isEmpty())
        val periodic = active(PERIODIC).single()
        assertEquals(900_000L, periodic.periodicityInfo!!.repeatIntervalMillis)
        schedule(30)
        val updated = active(PERIODIC).single()
        assertEquals(periodic.id, updated.id)
        assertEquals(1_800_000L, updated.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun `switching to periodic cancels a running short pass and its blocked successor`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val published = CompletableDeferred<Unit>()
        val account = installProvider(published) {
            entered.complete(Unit)
            release.await()
            UsageResult(emptyList())
        }
        schedule(5)
        val first = active(SHORT).single().id
        start(first)
        try {
            withTimeout(10_000) { entered.await() }
            val reserved = active(SHORT).associate { it.id to it.state }
            assertEquals(2, reserved.size)
            assertEquals(WorkInfo.State.RUNNING, reserved[first])
            assertEquals(WorkInfo.State.BLOCKED, reserved.entries.single { it.key != first }.value)
            SyncWorker.scheduleBackgroundSync(context)
            assertEquals(reserved, active(SHORT).associate { it.id to it.state })

            schedule(15)
            withTimeout(10_000) { published.await() }
            assertTrue(active(SHORT).isEmpty())
            assertEquals(1, active(PERIODIC).size)
            assertEquals(null, context.container.repository.accountUsageOnce().single { it.account.localId == account.localId }.snapshot)
        } finally {
            release.complete(Unit)
            if (entered.isCompleted) withTimeout(10_000) { published.await() }
        }
    }

    @Test
    fun `a stopped short request reruns without reserving a second successor`() = runBlocking {
        val entered = Channel<Unit>(capacity = 2)
        val release = CompletableDeferred<Unit>()
        installProvider {
            entered.send(Unit)
            release.await()
            UsageResult(emptyList())
        }
        schedule(5)
        val first = active(SHORT).single().id
        start(first)
        try {
            withTimeout(10_000) { entered.receive() }
            val successor = active(SHORT).single { it.state == WorkInfo.State.BLOCKED }.id
            WorkManagerTestInitHelper.getTestDriver(context)!!
                .stopRunningWorkWithReason(first, WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
            withTimeout(10_000) {
                manager.getWorkInfoByIdFlow(first).first { it?.state == WorkInfo.State.ENQUEUED }
            }

            start(first)
            withTimeout(10_000) { entered.receive() }
            assertEquals(WorkInfo.State.RUNNING, manager.getWorkInfoById(first).get()!!.state)
            assertEquals("the rerun keeps only its original blocked successor", listOf(successor),
                active(SHORT).filter { it.state == WorkInfo.State.BLOCKED }.map { it.id })
        } finally {
            release.complete(Unit)
            withTimeout(10_000) {
                manager.getWorkInfoByIdFlow(first).first { it?.state?.isFinished == true }
            }
        }
    }

    @Test
    fun `scheduled and foreground work respect cooldown but an explicit widget tap reads and repairs the chain`() = runBlocking {
        val reads = AtomicInteger()
        installProvider {
            if (reads.incrementAndGet() == 1) throw ProviderException.RateLimited(3_600_000L)
            UsageResult(emptyList())
        }
        schedule(5)
        runWork(active(SHORT).single())
        runWork(active(SHORT).single())
        assertEquals("the next short pass must honor the throttle", 1, reads.get())
        SyncWorker.syncOnOpen(context)
        runWork(active(SyncWorker.ONE_SHOT_WORK_NAME).single())
        assertEquals("foreground launch is not an explicit refresh", 1, reads.get())

        manager.cancelUniqueWork(SHORT).result.get()
        SyncWorker.syncOnOpen(context)
        val foreground = active(SyncWorker.ONE_SHOT_WORK_NAME).single()
        SyncWorker.syncNow(context)
        val manual = active(SyncWorker.ONE_SHOT_WORK_NAME).single()
        assertNotEquals("a queued automatic pass must not consume a widget tap", foreground.id, manual.id)
        runWork(manual)
        assertEquals("widget refresh tap bypasses the cooldown", 2, reads.get())
        runWork(active(SHORT).single())
        assertEquals("manual success lets the repaired short chain read again", 3, reads.get())
    }

    @Test
    fun `a widget tick enqueues one conditional sync, waiting for the network`() {
        SyncWorker.syncIfCacheAged(context)

        val infos = oneShots()
        assertEquals(1, infos.size)
        // Network-constrained, so it waits rather than failing offline.
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @Test
    fun `a tap and a tick collapse into one pass`() {
        SyncWorker.syncNow(context)
        SyncWorker.syncIfCacheAged(context)
        SyncWorker.syncNow(context)

        assertEquals(1, oneShots().size)
    }

    @Test
    fun `a tap after a tick is not swallowed by the conditional sync`() {
        // The tick's pass may skip the fetch; the user's tap must not inherit that skip.
        SyncWorker.syncIfCacheAged(context)
        SyncWorker.syncNow(context)

        val pending = oneShots().filterNot { it.state.isFinished }
        assertEquals(1, pending.size)
        assertFalse(SyncWorker.AGED_TAG in pending.single().tags)
    }
}
