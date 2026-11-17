package com.usagelimits.core.sync

import android.Manifest
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import org.robolectric.Shadows.shadowOf
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.widget.WidgetPresentationWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Cache changes schedule the next repaint and evaluate alerts. Presentation changes schedule
 * the same repaint without consuming an alert, including when a time-boundary worker fires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
class PublicationSchedulingTest {

    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun quietStartupWork() {
        runBlocking { app.startupJob?.join() }
        WorkManager.getInstance(app).cancelAllWork().result.get()
    }

    /** Nothing this class started may still be running when the next class sets up. */
    @After
    fun drain() {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `a time boundary repaints without consuming an alert`() = runBlocking {
        val repository = app.container.repository
        val account = repository.upsertFromLogin(ProviderId.CODEX, "boundary", "test@example.com",
            null, "Plus", emptyMap())
        val now = System.currentTimeMillis()
        repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.OK,
            windows = listOf(UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR,
                usedPercent = 95.0, resetAt = now + 3_600_000, periodSeconds = 18_000, exhausted = false))))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        val worker = TestListenableWorkerBuilder<WidgetPresentationWorker>(app).build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())

        val pending = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(WidgetPresentationWorker.WORK_NAME).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, pending.size)
        assertEquals(0, notifications.allNotifications.size)
        app.container.publication.cacheMoved()
        assertEquals(1, notifications.allNotifications.size)
    }

    @Test
    fun `presentation changed schedules a boundary without consuming an alert`() = runBlocking {
        val repository = app.container.repository
        val account = repository.upsertFromLogin(ProviderId.CODEX, "presentation", "test@example.com",
            null, "Plus", emptyMap())
        val now = System.currentTimeMillis()
        repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.OK,
            windows = listOf(UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR,
                usedPercent = 95.0, resetAt = now + 3_600_000, periodSeconds = 18_000, exhausted = false))))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))

        app.container.publication.presentationChanged()

        val pending = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(WidgetPresentationWorker.WORK_NAME).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, pending.size)
        assertEquals(0, notifications.allNotifications.size)

        // A repaint must not claim the edge either: the next cache event still announces it.
        app.container.publication.cacheMoved()
        assertEquals(1, notifications.allNotifications.size)
    }

    @Test
    fun `cache moved schedules the next presentation boundary and publishes alerts`() = runBlocking {
        val repository = app.container.repository
        val account = repository.upsertFromLogin(ProviderId.CODEX, "fixture", "test@example.com",
            null, "Plus", emptyMap())
        val now = System.currentTimeMillis()
        repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.OK,
            windows = listOf(UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR,
                usedPercent = 95.0, resetAt = now + 3_600_000, periodSeconds = 18_000, exhausted = false))))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        app.container.publication.cacheMoved()

        val pending = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(WidgetPresentationWorker.WORK_NAME).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, pending.size)
        assertEquals(1, shadowOf(
            app.getSystemService(NotificationManager::class.java)).allNotifications.size)
    }
}
