package com.usagelimits.statusicon

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.usagelimits.UsageLimitsActivity
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.sync.NotificationPublisher
import com.usagelimits.core.sync.Publication
import com.usagelimits.core.sync.resetTestWorkManager
import com.usagelimits.feature.UsageViewModel
import com.usagelimits.feature.waitDrained
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

/** The real main window stays on Overview, with WorkManager's network constraint unmet. */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34], qualifiers = "w393dp-h1600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusIconOverviewReturnTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var original: AppContainer
    private var activity: ActivityController<UsageLimitsActivity>? = null
    private var restorations = 0
    private var cacheEvents = 0
    private lateinit var accountId: String

    @Before fun setUp() {
        runBlocking { app.startupJob?.join() }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        resetTestWorkManager()
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        // The test driver never marks the connected constraint met; no provider request can run.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        original = app.container
        val container = object : AppContainer(app) {
            override val publication = object : Publication by original.publication {
                override suspend fun notificationsAllowedAgain() {
                    restorations++
                    original.publication.notificationsAllowedAgain()
                }
                override suspend fun cacheMoved() {
                    cacheEvents++
                    original.publication.cacheMoved()
                }
            }
        }
        ReflectionHelpers.setField(app, "container", container)
        runBlocking {
            val store = container.settingsStore
            store.recordNotificationPermissionHandled()
            store.setNotifyBelow20Percent(true)
            store.setNotifyBelow10Percent(true)
            store.recordStatusIconEligibility(true)
            store.setStatusIconsEnabled(true)
            val account = container.repository.upsertFromLogin(ProviderId.CLAUDE, "overview-return", null, "Offline Claude", null, emptyMap())
            accountId = account.localId
            store.setAccountNotifications(accountId, true)
            val now = System.currentTimeMillis()
            container.repository.saveSnapshot(UsageSnapshot(accountId, now, SnapshotStatus.OK, listOf(
                UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, 95.0, 18_000, now + 3_600_000, false))))
            store.setPinnedAccounts(listOf(accountId))
            container.publication.presentationChanged()
        }
        assertEquals(2, statusRecords().size)
    }

    @After fun tearDown() {
        try {
            val window = activity?.get()
            val jobs = window?.viewModelStore?.keys()?.mapNotNull { window.viewModelStore[it] as? UsageViewModel }
                .orEmpty().mapNotNull { it.viewModelScope.coroutineContext[Job] } + listOfNotNull(app.mainOpening.job, window?.notificationPermissionCheck)
            activity?.pause()?.stop()?.destroy()
            compose.waitDrained { jobs.all { it.isCompleted } }
            lateinit var barrier: Job
            compose.runOnUiThread { barrier = app.observeStatusIconEligibility() }
            compose.waitDrained { barrier.isCompleted }
        } finally {
            ReflectionHelpers.setField(app, "container", original)
            WorkManager.getInstance(app).cancelAllWork().result.get()
            ShadowLooper.idleMainLooper()
            resetTestWorkManager()
        }
    }

    private fun statusRecords() = manager.activeNotifications.filter { it.notification.channelId == StatusIconPublisher.CHANNEL_ID }
    private fun alerts() = manager.activeNotifications.filter { it.notification.channelId == NotificationPublisher.CHANNEL_ID }
    private fun overview() = compose.onAllNodes(hasText("Overview") and
        SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().isNotEmpty()

    private fun start() {
        activity = Robolectric.buildActivity(UsageLimitsActivity::class.java)
        activity!!.create().start().resume().visible()
        compose.waitDrained { overview() && app.mainOpening.job?.isCompleted != false }
    }

    /** Pause/resume is enough for Android's settings and never requests a second opening sync. */
    private fun returnAfter(change: () -> Unit) {
        activity!!.pause()
        change()
        activity!!.resume()
    }

    private enum class Block { APP, PERMISSION, CATEGORY }

    @Test fun `an offline Overview return restores only a known effective eligibility edge, not an ordinary resume`() {
        start()
        val store = app.container.settingsStore
        for (block in Block.entries) {
            val before = restorations
            returnAfter {
                when (block) {
                    Block.APP -> shadowOf(manager).setNotificationsEnabled(false)
                    Block.PERMISSION -> shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
                    Block.CATEGORY -> manager.getNotificationChannel(StatusIconPublisher.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_NONE
                }
                // Android removes blocked notifications; Robolectric's manager does not.
                statusRecords().forEach { manager.cancel(it.tag, it.id) }
            }
            compose.waitDrained { runBlocking { store.statusIconEligibility.first() } == false }
            assertEquals("$block: no restore while blocked", before, restorations)
            returnAfter {
                when (block) {
                    Block.APP -> shadowOf(manager).setNotificationsEnabled(true)
                    Block.PERMISSION -> shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
                    Block.CATEGORY -> manager.getNotificationChannel(StatusIconPublisher.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_DEFAULT
                }
            }
            compose.waitDrained { restorations == before + 1 && statusRecords().size == 2 }
            assertTrue("$block: the user never left Overview for the app's Settings", overview())
            assertEquals(setOf(accountId), statusRecords().map { shadowOf(it.notification.contentIntent).savedIntent.getStringExtra("accountId") }.toSet())
            assertEquals("the group has one summary", 1, statusRecords().count { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 })
            assertEquals("restoration is not an alert", 0, alerts().size)

            statusRecords().forEach { manager.cancel(it.tag, it.id) }
            returnAfter { }
            lateinit var barrier: Job
            compose.runOnUiThread { barrier = app.observeStatusIconEligibility() }
            compose.waitDrained { barrier.isCompleted }
            assertEquals("$block: ordinary resume must respect the swipe", 0, statusRecords().size)
            assertEquals("$block: no duplicate edge publication", before + 1, restorations)
        }
        assertEquals("no cache event or provider sync restored these icons", 0, cacheEvents)
        val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork("usage_sync_now").get()
        assertTrue("the ordinary opening sync stays offline behind its constraint", work.isNotEmpty() && work.all { it.state == WorkInfo.State.ENQUEUED })
        runBlocking { app.container.publication.cacheMoved() }
        assertEquals("restoration did not consume the pending usage alert", 1, alerts().size)
    }
}
