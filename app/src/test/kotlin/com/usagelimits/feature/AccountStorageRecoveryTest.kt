package com.usagelimits.feature

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.database.ControlledAccountReads
import com.usagelimits.core.database.ControlledSnapshotReads
import com.usagelimits.core.database.CreditAccountEntity
import com.usagelimits.core.database.CreditDao
import com.usagelimits.core.database.CreditRepository
import com.usagelimits.core.database.RoomTransactionRunner
import com.usagelimits.core.database.UsageLimitsDatabase
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderId
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountStorageRecoveryTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val store = ViewModelStore()
    private val reported = mutableListOf<Throwable>()
    private val viewModelScope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> reported += error })
    private var savedDisplay = PercentDisplay.LEFT
    private lateinit var db: UsageLimitsDatabase
    private lateinit var accounts: ControlledAccountReads
    private lateinit var snapshots: ControlledSnapshotReads
    private lateinit var vm: UsageViewModel
    private lateinit var container: AppContainer
    private val creditReads = Channel<Result<List<CreditAccountEntity>>>(Channel.UNLIMITED)
    private var collection: Job? = null

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        app.deleteDatabase("usage_limits.db")
        db = UsageLimitsDatabase.build(app)
        accounts = ControlledAccountReads(db.accountDao())
        snapshots = ControlledSnapshotReads(db.usageSnapshotDao())
        container = object : AppContainer(app) {
            override val repository = UsageRepository(accounts, snapshots, transactions = RoomTransactionRunner(db))
            override val creditRepository = CreditRepository(object : CreditDao by db.creditDao() {
                override fun observeAccounts() = flow {
                    for (read in creditReads) emit(read.getOrThrow())
                }
            }, transactions = RoomTransactionRunner(db))
        }
        savedDisplay = runBlocking { container.settingsStore.settings.first().percentDisplay }
        vm = UsageViewModel(container, app, viewModelScope)
        store.put("usage", vm)
        collection = scope.backgroundScope.launch { vm.state.collect {} }
    }

    @After fun tearDown() {
        val job = vm.viewModelScope.coroutineContext[Job]!!
        runTest(dispatcher) {
            store.clear()
            collection?.cancelAndJoin()
            job.join()
            container.settingsStore.setPercentDisplay(savedDisplay)
        }
        db.close()
        app.deleteDatabase("usage_limits.db")
        Dispatchers.resetMain()
    }

    private suspend fun readCredits() { creditReads.send(Result.success(db.creditDao().getAccounts())) }

    @Test fun `an unreadable cold store finishes loading and a retry reads the same live consumer`() = scope.runTest {
        container.settingsStore.setPercentDisplay(PercentDisplay.USED)
        readCredits()
        snapshots.succeed()
        accounts.fail()
        val failed = vm.state.first { it.accountStorageUnreadable }
        assertTrue("a failed first read is not an endless skeleton", failed.loaded)
        assertTrue(failed.accountStorageUnreadable)
        assertEquals(PercentDisplay.USED, failed.settings.percentDisplay)

        val account = container.repository.upsertFromLogin(ProviderId.CODEX, "restored", null, "Restored", null, emptyMap())
        vm.retryAccountStorage()
        snapshots.succeed()
        accounts.succeed()
        val recovered = vm.state.first { !it.accountStorageUnreadable && it.accounts.isNotEmpty() }
        assertFalse("success clears the error on the original subscription", recovered.accountStorageUnreadable)
        assertEquals(listOf(account.localId), recovered.accounts.map { it.account.localId })
    }

    @Test fun `a later account read failure retains known accounts settings and credits until retry succeeds`() = scope.runTest {
        val account = container.repository.upsertFromLogin(ProviderId.CODEX, "known", null, "Known", null, emptyMap())
        container.settingsStore.setPercentDisplay(PercentDisplay.USED)
        container.creditRepository.upsert(CreditAccount("credit", CreditProviderId.FAL, "Known credit", "synthetic", createdAt = 0L))
        readCredits()
        accounts.succeed()
        snapshots.succeed()
        val known = vm.state.first { it.loaded && it.accounts.isNotEmpty() && it.credits.isNotEmpty() && it.settings.percentDisplay == PercentDisplay.USED }
        assertEquals(listOf(account.localId), known.accounts.map { it.account.localId })
        assertEquals(listOf("credit"), known.credits.map { it.id })

        accounts.fail()
        val failed = vm.state.first { it.accountStorageUnreadable }
        assertTrue(failed.accountStorageUnreadable)
        assertEquals(known.accounts, failed.accounts)
        assertEquals(known.settings, failed.settings)
        assertEquals(known.credits, failed.credits)

        vm.retryAccountStorage()
        accounts.fail()
        runCurrent()
        assertEquals("an unsuccessful retry keeps the last readable state", failed, vm.state.value)

        vm.refresh()
        accounts.succeed()
        snapshots.succeed()
        val recovered = vm.state.first { !it.accountStorageUnreadable }
        assertEquals(known.accounts, recovered.accounts)
    }

    @Test fun `a snapshot read failure also recovers without replacing the known account list`() = scope.runTest {
        val account = container.repository.upsertFromLogin(ProviderId.CODEX, "known", null, "Known", null, emptyMap())
        readCredits()
        accounts.succeed()
        snapshots.succeed()
        vm.state.first { it.loaded && it.accounts.isNotEmpty() }
        snapshots.fail()
        val failed = vm.state.first { it.accountStorageUnreadable }
        assertEquals(account.localId, failed.accounts.single().account.localId)
        vm.retryAccountStorage()
        accounts.succeed()
        snapshots.succeed()
        assertFalse(vm.state.first { !it.accountStorageUnreadable }.accountStorageUnreadable)
    }

    @Test fun `a credit flow failure is not an account storage error and does not replace known state`() = scope.runTest {
        container.repository.upsertFromLogin(ProviderId.CODEX, "known", null, "Known", null, emptyMap())
        container.settingsStore.setPercentDisplay(PercentDisplay.USED)
        readCredits()
        accounts.succeed()
        snapshots.succeed()
        val known = vm.state.first { it.loaded && it.accounts.isNotEmpty() && it.settings.percentDisplay == PercentDisplay.USED }
        val failure = IllegalStateException("Synthetic credit read failure")
        creditReads.send(Result.failure(failure))
        runCurrent()
        val reportedFailure = reported.single()
        assertEquals(failure.javaClass, reportedFailure.javaClass)
        assertEquals(failure.message, reportedFailure.message)
        // Coroutine stack-trace recovery can copy the exception, keeping the original as its cause.
        assertSame("the independent failure is not swallowed", failure,
            generateSequence(reportedFailure) { it.cause }.last())
        assertFalse(vm.state.value.accountStorageUnreadable)
        assertEquals(known, vm.state.value)
    }
}
