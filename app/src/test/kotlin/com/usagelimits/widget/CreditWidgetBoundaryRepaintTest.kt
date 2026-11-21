package com.usagelimits.widget

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import com.usagelimits.core.model.Severity
import com.usagelimits.core.sync.SyncWorker
import com.usagelimits.core.sync.resetTestWorkManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.math.BigDecimal

/** A running Credits consumer ages the unchanged cache when publication repaints it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
class CreditWidgetBoundaryRepaintTest {
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After fun drain() {
        try {
            WorkManager.getInstance(app).cancelAllWork().result.get()
            ShadowLooper.idleMainLooper()
        } finally {
            resetTestWorkManager()
        }
    }

    @Test fun `a running Credits session ages and reranks at the stale threshold without a sync`() = runBlocking {
        val repository = app.container.creditRepository
        val settings = app.container.settingsStore
        val originalInterval = settings.settings.first().syncIntervalMinutes
        settings.setSyncInterval(180)
        // Keep the next real repaint distinct even if another test wrote a diagnostic timestamp.
        settings.recordWidgetRefresh(0L)
        val fetchedAt = 1_800_000_000_000L
        val staleAt = fetchedAt + 21_600_000L
        val alpha = CreditAccount("boundary-alpha", CreditProviderId.FAL, "Alpha", "credit:boundary-alpha", createdAt = fetchedAt)
        val bravo = CreditAccount("boundary-bravo", CreditProviderId.FAL, "Bravo", "credit:boundary-bravo", createdAt = fetchedAt)
        repository.upsert(alpha, CreditSnapshot(alpha.id,
            listOf(CreditBalance("USD", BigDecimal.ONE)), fetchedAt))
        repository.upsert(bravo, CreditSnapshot(bravo.id,
            listOf(CreditBalance("USD", BigDecimal(100))), fetchedAt + 60_000))
        val cached = repository.entriesOnce()

        var clock = staleAt - 1
        val snapshots = Channel<CreditWidgetSnapshot>(Channel.UNLIMITED)
        val session = launch(start = CoroutineStart.UNDISPATCHED) {
            WidgetUpdater.observeCredits(app, now = { clock }).collect { snapshots.send(it) }
        }
        try {
            val before = withTimeout(10_000) { snapshots.receive() }
            assertEquals("the low balance leads one millisecond before its age boundary",
                listOf(alpha.id, bravo.id), before.rows.map { it.accountId })
            assertEquals(listOf(Severity.LOW, Severity.HEALTHY), before.rows.map { it.severity })

            // No launcher widget is bound, so publication starts no Glance session. WorkManager's
            // test driver holds every delay and network constraint; only the cache repaint runs.
            clock = staleAt
            app.container.publication.presentationChanged()

            val after = withTimeout(10_000) { snapshots.receive() }
            assertEquals("the fresh healthy balance leads once the low reading turns stale",
                listOf(bravo.id, alpha.id), after.rows.map { it.accountId })
            assertEquals(listOf(Severity.HEALTHY, Severity.STALE), after.rows.map { it.severity })
            assertEquals("the repaint does not change any cached balance or reading timestamp",
                cached, repository.entriesOnce())
            assertTrue("no sync ran while the running Credits consumer changed",
                WorkManager.getInstance(app).getWorkInfosByTag(SyncWorker::class.java.name).get()
                    .none { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.SUCCEEDED || it.runAttemptCount > 0 })
        } finally {
            session.cancelAndJoin()
            snapshots.close()
            settings.setSyncInterval(originalInterval)
        }
    }
}
