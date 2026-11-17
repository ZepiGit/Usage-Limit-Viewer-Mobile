package com.usagelimits.widget

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
class PresentationSchedulingTest {
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val manager: WorkManager get() = WorkManager.getInstance(app)

    @Before fun setUp() = runBlocking {
        app.startupJob?.join()
        manager.cancelAllWork().result.get()
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val account = app.container.repository.upsertFromLogin(ProviderId.CODEX, "schedule", "test@example.com",
            null, "Plus", emptyMap())
        val now = System.currentTimeMillis()
        app.container.repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.OK,
            windows = listOf(UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR,
                usedPercent = 25.0, resetAt = now + 3_600_000, periodSeconds = 18_000, exhausted = false))))
    }

    @After fun tearDown() {
        manager.cancelAllWork().result.get()
        com.usagelimits.core.sync.resetTestWorkManager()
    }

    @Test fun `scheduling outside a running boundary replaces the pending repaint`() = runBlocking {
        app.container.publication.presentationChanged()
        val first = manager.getWorkInfosForUniqueWork(WidgetPresentationWorker.WORK_NAME).get().single().id

        app.container.publication.presentationChanged()

        val work = manager.getWorkInfosForUniqueWork(WidgetPresentationWorker.WORK_NAME).get()
        val previous = manager.getWorkInfoById(first).get()
        org.junit.Assert.assertTrue(previous == null || previous.state == WorkInfo.State.CANCELLED)
        assertNotEquals(first, work.single { it.state == WorkInfo.State.ENQUEUED }.id)
    }

    @Test fun `a running boundary worker finishes instead of cancelling itself while scheduling its successor`() = runBlocking {
        val request = OneTimeWorkRequestBuilder<WidgetPresentationWorker>()
            .setInitialDelay(1, TimeUnit.HOURS).build()
        manager.enqueueUniqueWork(WidgetPresentationWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request).await()

        WorkManagerTestInitHelper.getTestDriver(app)!!.setInitialDelayMet(request.id)

        val finished = withTimeout(10_000) {
            manager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }!!
        }
        assertEquals(WorkInfo.State.SUCCEEDED, finished.state)
        val work = manager.getWorkInfosForUniqueWork(WidgetPresentationWorker.WORK_NAME).get()
        assertEquals(1, work.count { it.state == WorkInfo.State.ENQUEUED })
    }
}
