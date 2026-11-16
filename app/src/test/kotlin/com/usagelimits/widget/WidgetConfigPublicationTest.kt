package com.usagelimits.widget

import android.Manifest
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.usagelimits.R
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
class WidgetConfigPublicationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private var activity: ActivityController<WidgetConfigActivity>? = null

    @Before fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val account = app.container.repository.upsertFromLogin(ProviderId.CODEX, "config", "test@example.com",
            null, "Plus", emptyMap())
        val now = System.currentTimeMillis()
        app.container.repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.OK,
            windows = listOf(UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR,
                usedPercent = 95.0, resetAt = now + 3_600_000, periodSeconds = 18_000, exhausted = false))))
    }

    @After fun tearDown() {
        activity?.pause()?.stop()?.destroy()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
    }

    @Test fun `saving widget configuration reschedules presentation without consuming an alert`() = runBlocking {
        val controller = Robolectric.buildActivity(WidgetConfigActivity::class.java,
            Intent(app, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 42))
            .setup()
        activity = controller
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(app.getString(R.string.widget_config_save)))
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(app.getString(R.string.widget_config_save)).performClick()
        compose.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            controller.get().isFinishing
        }

        val pending = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(WidgetPresentationWorker.WORK_NAME).get()
        assertTrue(pending.any { it.state == WorkInfo.State.ENQUEUED })
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertEquals(0, notifications.allNotifications.size)
        app.container.publication.cacheMoved()
        assertEquals(1, notifications.allNotifications.size)
    }
}
