package com.usagelimits

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import androidx.work.WorkManager
import com.usagelimits.core.sync.resetTestWorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34])
class NotificationPermissionActivityTest {
    private val app = ApplicationProvider.getApplicationContext<Application>() as UsageLimitsApp

    @Before
    fun startBeforeFirstLaunch() = runBlocking {
        app.container.settingsStore.clearNotificationPermissionHandled()
    }

    @After
    fun drainStartupWork() {
        ShadowLooper.idleMainLooper()
        runBlocking { app.startupJob?.join() }
        await { app.mainOpening.job?.isCompleted != false }
        lateinit var eligibilityBarrier: Job
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            eligibilityBarrier = app.observeStatusIconEligibility()
        }
        await { eligibilityBarrier.isCompleted }
        ShadowLooper.idleMainLooper()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
    }

    @Test
    fun `first launch persists its request and recreation does not ask again`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildActivity(UsageLimitsActivity::class.java).create().start().resume()
        try {
            await { controller.get().notificationPermissionCheck?.isCompleted == true }
            assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                shadowOf(controller.get()).lastRequestedPermission.requestedPermissions)
            assertTrue(runBlocking { app.container.settingsStore.settings.first().notificationPermissionHandled })
            controller.recreate()
            await { controller.get().notificationPermissionCheck?.isCompleted == true }
            assertNull(shadowOf(controller.get()).lastRequestedPermission)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `a later launch completes its decision without asking again`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val first = Robolectric.buildActivity(UsageLimitsActivity::class.java).create().start().resume()
        try {
            await { first.get().notificationPermissionCheck?.isCompleted == true }
        } finally {
            first.pause().stop().destroy()
        }
        val later = Robolectric.buildActivity(UsageLimitsActivity::class.java).create().start().resume()
        try {
            await { later.get().notificationPermissionCheck?.isCompleted == true }
            assertNull(shadowOf(later.get()).lastRequestedPermission)
        } finally {
            later.pause().stop().destroy()
        }
    }

    @Test
    fun `revoking an existing grant does not make a later launch ask automatically`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val first = Robolectric.buildActivity(UsageLimitsActivity::class.java).create().start().resume()
        try {
            await { first.get().notificationPermissionCheck?.isCompleted == true }
            assertNull(shadowOf(first.get()).lastRequestedPermission)
            assertTrue(runBlocking { app.container.settingsStore.settings.first().notificationPermissionHandled })
        } finally {
            first.pause().stop().destroy()
        }
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val later = Robolectric.buildActivity(UsageLimitsActivity::class.java).create().start().resume()
        try {
            await { later.get().notificationPermissionCheck?.isCompleted == true }
            assertNull(shadowOf(later.get()).lastRequestedPermission)
        } finally {
            later.pause().stop().destroy()
        }
    }

    @Test
    @Config(sdk = [32])
    fun `Android before 13 does not ask for notification permission or record a request`() {
        val controller = Robolectric.buildActivity(UsageLimitsActivity::class.java).create().start().resume()
        try {
            await { controller.get().notificationPermissionCheck?.isCompleted != false }
            assertNull(shadowOf(controller.get()).lastRequestedPermission)
            org.junit.Assert.assertFalse(runBlocking { app.container.settingsStore.settings.first().notificationPermissionHandled })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(10_000) {
            while (!condition()) {
                ShadowLooper.idleMainLooper()
                delay(10)
            }
        }
    }
}
