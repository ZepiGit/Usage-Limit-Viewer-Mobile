package com.usagelimits.core.sync

import androidx.test.core.app.ApplicationProvider
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
 * A foreground refresh — the in-app button, a per-account retry — moves the cache without the
 * worker, so the presentation boundary must be scheduled from the shared post-sync step too.
 * Otherwise a reset passing later is never repainted on the home screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
class PostSyncSchedulingTest {

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
    fun `a foreground refresh schedules the next presentation boundary`() = runBlocking {
        val repository = app.container.repository
        val account = repository.upsertFromLogin(ProviderId.CODEX, "fixture", "test@example.com",
            null, "Plus", emptyMap())
        val now = System.currentTimeMillis()
        repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.OK,
            windows = listOf(UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR,
                usedPercent = 25.0, resetAt = now + 3_600_000, periodSeconds = 18_000, exhausted = false))))

        app.container.publishAfterSync(app)

        val pending = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(WidgetPresentationWorker.WORK_NAME).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, pending.size)
    }
}
