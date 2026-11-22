package com.usagelimits.feature.settings

import android.Manifest
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.sync.resetTestWorkManager
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import org.robolectric.shadows.ShadowLooper
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.feature.UsageUiState
import com.usagelimits.ui.theme.UsageLimitsTheme
import com.usagelimits.feature.waitDrained
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34])
class NotificationPermissionSettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule
    val testName = org.junit.rules.TestName()
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()

    @Before fun prepare(): Unit = runBlocking {
        boundary("prepare.startup") { app.startupJob?.join() }
        boundary("prepare.cancelWork") { WorkManager.getInstance(app).cancelAllWork().result.get() }
        boundary("prepare.settings") {
            app.container.settingsStore.setStatusIconsEnabled(false)
            app.container.settingsStore.setPinnedAccounts(emptyList())
            app.container.settingsStore.recordStatusIconEligibility(true)
        }
    }

    @After fun drain() {
        lateinit var barrier: kotlinx.coroutines.Job
        boundary("drain.observeEligibility") {
            compose.runOnUiThread { barrier = app.observeStatusIconEligibility() }
        }
        boundary("drain.awaitEligibility") { compose.waitDrained { barrier.isCompleted } }
        boundary("drain.cancelWork") { WorkManager.getInstance(app).cancelAllWork().result.get() }
        boundary("drain.idleMain") { ShadowLooper.idleMainLooper() }
        boundary("drain.resetWorkManager") { resetTestWorkManager() }
    }

    // The CI test START event precedes setup; paired markers locate its first unfinished wait.
    private inline fun <T> boundary(name: String, action: () -> T): T {
        System.err.println("NotificationPermissionSettingsTest ${testName.methodName} START $name nanoTime=${System.nanoTime()}")
        return action().also {
            System.err.println("NotificationPermissionSettingsTest ${testName.methodName} END $name nanoTime=${System.nanoTime()}")
        }
    }

    @Test
    fun `blocked notifications explain why alerts are silent without disabling preferences`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var below20: Boolean? = null
        render(onNotifyBelow20 = { below20 = it })
        scrollTo("Notifications not allowed")
        compose.onNodeWithText("Notifications not allowed").assertIsDisplayed()
        compose.onNodeWithText("No alerts will be shown until notifications are allowed. Your alert preferences are kept.")
            .assertIsDisplayed()
        compose.onNodeWithText("Allow notifications").assertIsEnabled()
        scrollTo("Below 20% left")
        compose.onNodeWithContentDescription("Below 20% left").assertIsEnabled().performClick()
        assertEquals(false, below20)
    }

    @Test
    fun `returning from system settings rereads both grants and revocations`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(notificationPermissionHandled = true))
        scrollTo("Notifications not allowed")
        compose.onNodeWithText("Open notification settings").assertIsEnabled().performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Notifications allowed").assertIsDisplayed().assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite,
            ),
        )
        compose.onNodeWithText("Open notification settings").assertDoesNotExist()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Notifications not allowed").assertIsDisplayed()
    }

    @Test
    fun `an earlier denial can be allowed through the system dialog from Settings`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, true)
        var permissionAsked = false
        render(AppSettings(notificationPermissionHandled = true), onNotificationPermissionHandled = { permissionAsked = true })
        scrollTo("Allow notifications")
        boundary("request.click") { compose.onNodeWithText("Allow notifications").performClick() }
        org.junit.Assert.assertTrue(permissionAsked)
        boundary("request.awaitDialog") { compose.waitDrained { shadowOf(compose.activity).lastRequestedPermission != null } }
        val request = shadowOf(compose.activity).lastRequestedPermission
        boundary("request.assertPending") { compose.onNodeWithText("Allow notifications").assertIsNotEnabled() }
        org.junit.Assert.assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
        boundary("answer.runOnIdle") {
            compose.runOnIdle {
                shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
                boundary("answer.permissionCallback") {
                    compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                        intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
                }
            }
        }
        boundary("answer.assertAllowed") { compose.onNodeWithText("Notifications allowed").assertIsDisplayed() }
        boundary("answer.assertNoRequest") { compose.onNodeWithText("Allow notifications").assertDoesNotExist() }
    }

    @Test
    fun `a second denial replaces the dialog action with app notification settings`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, true)
        render(AppSettings(notificationPermissionHandled = true))
        scrollTo("Allow notifications")
        compose.onNodeWithText("Allow notifications").performClick()
        compose.waitDrained { shadowOf(compose.activity).lastRequestedPermission != null }
        val request = shadowOf(compose.activity).lastRequestedPermission
        compose.runOnIdle {
            shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, false)
            compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        }
        compose.onNodeWithText("Notifications not allowed").assertIsDisplayed()
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
        compose.onNodeWithText("Open notification settings").assertIsEnabled().performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `a first request made from Settings does not offer an unavailable dialog again`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render()
        scrollTo("Allow notifications")
        compose.onNodeWithText("Allow notifications").performClick()
        compose.waitDrained { shadowOf(compose.activity).lastRequestedPermission != null }
        val request = shadowOf(compose.activity).lastRequestedPermission
        compose.runOnIdle {
            shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, false)
            compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        }
        compose.onNodeWithText("Open notification settings").assertIsEnabled()
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
    }

    @Test
    fun `missing notification settings falls back to this app's details page`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val details = android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", app.packageName, null))
        val resolution = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "android.settings"
                name = "AppDetails"
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(details, resolution)
        shadowOf(app).checkActivities(true)
        render(AppSettings(notificationPermissionHandled = true))
        scrollTo("Open notification settings")
        compose.onNodeWithText("Open notification settings").performClick()
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals("package:${app.packageName}", opened.data.toString())
    }

    @Test
    @Config(sdk = [32])
    fun `Android before 13 has no permission to deny, so notifications left on read as allowed`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render()
        scrollTo("Notifications allowed")
        compose.onNodeWithText("Notifications allowed").assertIsDisplayed()
        compose.onNodeWithText("Notifications not allowed").assertDoesNotExist()
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
        org.junit.Assert.assertNull(shadowOf(compose.activity).lastRequestedPermission)
    }

    @Test
    @Config(sdk = [30])
    fun `Android before 13 with notifications switched off says so and leads to the app's notification settings, never a dialog`() {
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        render()
        scrollTo("Notifications not allowed")
        compose.onNodeWithText("Notifications not allowed").assertIsDisplayed()
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
        compose.onNodeWithText("Open notification settings").assertIsEnabled().performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        org.junit.Assert.assertNull(shadowOf(compose.activity).lastRequestedPermission)

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Notifications allowed").assertIsDisplayed()
    }

    @Test
    fun `a granted permission with notifications switched off leads to the app's notification settings, not the dialog`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        render()
        scrollTo("Notifications not allowed")
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
        compose.onNodeWithText("Open notification settings").performClick()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
        org.junit.Assert.assertNull(shadowOf(compose.activity).lastRequestedPermission)
    }

    @Test
    @Config(qualifiers = "de")
    fun `German Settings explains denied notifications and offers a way to allow them`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render()
        scrollTo("Benachrichtigungen nicht erlaubt")
        compose.onNodeWithText("Benachrichtigungen nicht erlaubt").assertIsDisplayed()
        compose.onNodeWithText("Es werden keine Hinweise angezeigt, bis Benachrichtigungen erlaubt sind. Deine Auswahl unten bleibt gespeichert.")
            .assertIsDisplayed()
        compose.onNodeWithText("Benachrichtigungen erlauben").assertIsEnabled()
    }

    private fun render(
        settings: AppSettings = AppSettings(),
        onNotifyBelow20: (Boolean) -> Unit = {},
        onNotificationPermissionHandled: () -> Unit = {},
    ) {
        boundary("render.setContent") {
            compose.setContent {
                UsageLimitsTheme {
                    SettingsScreen(
                        state = UsageUiState(settings = settings),
                        onSyncIntervalChange = {},
                        onNotificationPermissionHandled = onNotificationPermissionHandled, onAppThemeChange = {},
                        onNotifyBelow20 = onNotifyBelow20,
                        onNotifyBelow10 = {},
                        onNotifyExhausted = {},
                        onNotifyResetCredit = {},
                        onNotifyAuthExpired = {},
                        onNotifyResetApproaching = {},
                        onNotifyCreditExpiring = {},
                        onShowTier = {},
                        onOverviewLayoutChange = {},
                        onPercentDisplayChange = {},
                    )
                }
            }
        }
    }

    private fun scrollTo(text: String) {
        boundary("scrollTo") { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text)) }
    }
}
