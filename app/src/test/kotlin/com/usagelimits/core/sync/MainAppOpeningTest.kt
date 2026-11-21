package com.usagelimits.core.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.database.*
import com.usagelimits.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@OptIn(ExperimentalCoroutinesApi::class)
class MainAppOpeningTest {
    private lateinit var db: UsageLimitsDatabase
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private fun repository(accounts: AccountDao = db.accountDao()) =
        UsageRepository(accounts, db.usageSnapshotDao(), transactions = RoomTransactionRunner(db))
    @Before fun open() { db = UsageLimitsDatabase.build(context) }
    @After fun close() { db.close(); context.deleteDatabase("usage_limits.db") }
    private fun reading(id: String, fetch: Long, reset: Long) = UsageSnapshot(id, fetch, SnapshotStatus.OK,
        listOf(UsageWindow("five-hour", "5h limit", WindowCategory.FIVE_HOUR, 20.0, 18_000, reset, false)))
    private suspend fun account(): String = repository().upsertFromLogin(ProviderId.CLAUDE, "claude", null, null, null, emptyMap()).localId
    private suspend fun confirm(id: String) {
        repository().saveSnapshot(reading(id, 1000, 2000))
        repository().saveSnapshot(reading(id, 2500, 4000))
    }

    @Test fun `either callback order opens once and recreation or resume does not reopen`() = runTest {
        for (processFirst in listOf(true, false)) {
            var openings = 0
            val opening = MainAppOpening(this, { emptySet() }, {}, { openings++ }, {})
            if (processFirst) { opening.processStarted(); opening.activityStarted(true) }
            else { opening.activityStarted(true); opening.processStarted() }
            opening.activityStarted(true)
            opening.job?.join()
            assertEquals("one opening despite activity recreation ($processFirst)", 1, openings)
            opening.processStopped()
            opening.activityStarted(true); opening.processStarted()
            opening.job?.join()
            assertEquals("background return is a new opening ($processFirst)", 2, openings)
        }
    }

    @Test fun `widget configuration does not claim or enqueue and cannot lend its interval to main`() = runTest {
        val id = account()
        confirm(id)
        for (processFirst in listOf(true, false)) {
            val claimed = mutableListOf<Set<String>>()
            var syncs = 0
            val opening = MainAppOpening(this, { repository().claimPendingResetConfirmations() }, { claimed += it }, { syncs++ }, {})
            if (processFirst) { opening.processStarted(); opening.activityStarted(false) }
            else { opening.activityStarted(false); opening.processStarted() }
            opening.activityStarted(true)
            runCurrent()
            assertEquals("non-main interval enqueues no on-open sync ($processFirst)", 0, syncs)
            assertEquals("widget configuration leaves the reset pending ($processFirst)", 2500L,
                db.accountDao().getById(id)!!.pendingResetConfirmationAt)
            opening.processStopped()
            opening.processStarted(); opening.activityStarted(true)
            opening.job?.join()
            assertEquals(listOf(setOf(id)), claimed)
            assertEquals("next genuine main opening enqueues one sync", 1, syncs)
            confirm(id)
        }
    }

    @Test fun `claim finishes before sync and that sync reset waits through recreation to next opening`() = runTest {
        val id = account()
        repository().saveSnapshot(reading(id, 1000, 2000))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val accounts = object : AccountDao by db.accountDao() {
            override suspend fun getAll(): List<AccountEntity> {
                entered.complete(Unit); release.await()
                return db.accountDao().getAll()
            }
        }
        val claimed = mutableListOf<Set<String>>()
        var syncs = 0
        val syncJobs = mutableListOf<Job>()
        val opening = MainAppOpening(this, { repository(accounts).claimPendingResetConfirmations() }, { claimed += it }, {
            syncs++
            syncJobs += launch { repository().saveSnapshot(reading(id, 2500, 4000)) }
        }, {})
        opening.processStarted(); opening.activityStarted(true)
        entered.await()
        try {
            assertEquals("on-open sync cannot enqueue while claim is held", 0, syncs)
            release.complete(Unit)
            opening.job?.join()
            syncJobs.forEach { it.join() }
            opening.activityStarted(true)
            opening.job?.join()
            assertEquals("activity recreation must not consume opening-sync reset", 2500L,
                db.accountDao().getById(id)!!.pendingResetConfirmationAt)
            assertEquals("recreation must not enqueue another sync", 1, syncs)
            opening.processStopped(); opening.activityStarted(true); opening.processStarted()
            opening.job?.join()
            syncJobs.forEach { it.join() }
            assertEquals("opening-sync confirmation belongs to the following opening", listOf(emptySet<String>(), setOf(id)), claimed)
        } finally { release.complete(Unit); opening.job?.cancelAndJoin(); syncJobs.forEach { it.cancelAndJoin() } }
    }

    @Test fun `scheduled commit and claim use the same transaction order`() = runTest {
        val id = account()
        repository().saveSnapshot(reading(id, 1000, 2000))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val snapshots = object : UsageSnapshotDao by db.usageSnapshotDao() {
            override suspend fun upsert(snapshot: UsageSnapshotEntity) {
                entered.complete(Unit); release.await(); db.usageSnapshotDao().upsert(snapshot)
            }
        }
        val scheduled = async { UsageRepository(db.accountDao(), snapshots, transactions = RoomTransactionRunner(db))
            .saveSnapshot(reading(id, 2500, 4000)) }
        entered.await()
        val claim = async { repository().claimPendingResetConfirmations() }
        try {
            release.complete(Unit)
            scheduled.await()
            assertEquals("a scheduled reading committed before the claim belongs to this opening", setOf(id), claim.await())
        } finally { release.complete(Unit); scheduled.cancelAndJoin(); claim.cancelAndJoin() }
    }
}
