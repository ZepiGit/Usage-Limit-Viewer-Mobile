package com.usagelimits

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
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

    @After
    fun drainStartupWork() {
        ShadowLooper.idleMainLooper()
        runBlocking { app.startupJob?.join() }
        ShadowLooper.idleMainLooper()
        WorkManager.getInstance(app).cancelAllWork().result.get()
    }

    @Test
    fun `first launch persists its request before recreation and later launches`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildActivity(UsageLimitsActivity::class.java).create().start().resume()
        try {
            await { shadowOf(controller.get()).lastRequestedPermission != null }
            assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                shadowOf(controller.get()).lastRequestedPermission.requestedPermissions)
            assertTrue(runBlocking { app.container.settingsStore.settings.first().notificationPermissionAsked })
            controller.recreate()
            ShadowLooper.idleMainLooper()
            assertNull(shadowOf(controller.get()).lastRequestedPermission)
        } finally {
            controller.pause().stop().destroy()
        }
        val later = Robolectric.buildActivity(UsageLimitsActivity::class.java).create().start().resume()
        try {
            ShadowLooper.idleMainLooper()
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
            ShadowLooper.idleMainLooper()
            assertNull(shadowOf(controller.get()).lastRequestedPermission)
            org.junit.Assert.assertFalse(runBlocking { app.container.settingsStore.settings.first().notificationPermissionAsked })
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
