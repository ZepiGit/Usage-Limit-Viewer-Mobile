package com.usagelimits

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.usagelimits.core.model.*
import com.usagelimits.core.sync.SyncWorker
import com.usagelimits.core.sync.resetTestWorkManager
import com.usagelimits.widget.WidgetConfigActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34])
class MainAppOpeningActivityTest {
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val manager: WorkManager get() = WorkManager.getInstance(app)
    @Before fun setup() {
        runBlocking { app.startupJob?.join() }
        manager.cancelAllWork().result.get()
        resetTestWorkManager()
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        app.mainOpening.processStopped()
    }
    @After fun cleanup() {
        app.mainOpening.processStopped()
        app.mainOpening.job?.cancel()
        drainOpening()
        manager.cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
    }
    private fun drainOpening(job: Job? = app.mainOpening.job) {
        val deadline = System.currentTimeMillis() + 10_000
        while (job?.isCompleted == false) {
            check(System.currentTimeMillis() < deadline) { "opening work did not end" }
            ShadowLooper.idleMainLooper()
            runBlocking { yield() }
        }
    }
    private fun queued() = manager.getWorkInfosForUniqueWork(SyncWorker.ONE_SHOT_WORK_NAME).get().filterNot { it.state.isFinished }
    private suspend fun confirm(): String {
        val repo = app.container.repository
        val id = repo.upsertFromLogin(ProviderId.CLAUDE, "opening", null, null, null, emptyMap()).localId
        fun reading(fetch: Long, reset: Long) = UsageSnapshot(id, fetch, SnapshotStatus.OK,
            listOf(UsageWindow("five-hour", "5h limit", WindowCategory.FIVE_HOUR, 20.0, 18_000, reset, false)))
        repo.saveSnapshot(reading(1000, 2000)); repo.saveSnapshot(reading(2500, 4000))
        return id
    }

    @Test fun `launcher widget configuration leaves mark and sync untouched until a later main opening`() = runTest {
        val id = confirm()
        val config = Robolectric.buildActivity(WidgetConfigActivity::class.java,
            Intent(app, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 7))
        var main: org.robolectric.android.controller.ActivityController<UsageLimitsActivity>? = null
        try {
            app.mainOpening.processStarted()
            config.create().start()
            ShadowLooper.idleMainLooper()
            assertTrue("configuration without main must enqueue no on-open sync", queued().isEmpty())
            assertNull("configuration must not claim a presentation", app.hoorayPlayback.startedAt(id))
            // Claim only after checking the no-sync result, then put back a fresh confirmation.
            assertEquals("configuration must leave the confirmed reset pending", setOf(id), app.container.repository.claimPendingResetConfirmations())
            val repo = app.container.repository
            val stored = repo.snapshot(id)!!
            repo.saveSnapshot(stored.copy(fetchedAt = 4500, windows = stored.windows.map { it.copy(resetAt = 6000) }))
            config.stop().destroy()
            app.mainOpening.processStopped()
            main = Robolectric.buildActivity(UsageLimitsActivity::class.java).create().start()
            app.mainOpening.processStarted()
            drainOpening()
            assertEquals("next qualifying main opening enqueues sync", 1, queued().size)
            assertTrue("next main opening claimed and cleared the mark", repo.claimPendingResetConfirmations().isEmpty())
        } finally {
            val notification = main?.get()?.notificationPermissionCheck
            main?.stop()?.destroy()
            notification?.cancel()
            drainOpening(notification)
            if (!config.get().isDestroyed) config.stop().destroy()
            app.mainOpening.job?.cancel()
            drainOpening()
            app.container.repository.deleteAccount(id)
        }
    }
}
