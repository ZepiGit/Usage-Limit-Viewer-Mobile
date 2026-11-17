package com.usagelimits.feature.settings

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.feature.UsageViewModel
import com.usagelimits.statusicon.StatusIconPublisher
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Granting the notification permission shows the icon of the account already chosen, from the
 * cache, without a sync: through the dialog Settings opens, and on return from the system's
 * notification settings. Real screen, real view model, real publication, Robolectric's
 * notification manager; no provider is ever asked for data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusIconPermissionGrantTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var vm: UsageViewModel

    @Before
    fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = app.container.settingsStore
        store.setStatusIconsEnabled(false)
        val claude = account()
        store.setPinnedAccounts(listOf(claude.localId))
        vm = UsageViewModel(app.container, app)
    }

    @After
    fun drain() {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
    }

    private suspend fun account(): ProviderAccount {
        val repository = app.container.repository
        val account = repository.upsertFromLogin(ProviderId.CLAUDE, "grant-${System.nanoTime()}", null, "Claude", null, emptyMap())
        val now = System.currentTimeMillis()
        repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.OK, windows = listOf(
            UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, usedPercent = 58.0, periodSeconds = 18_000,
                resetAt = now + 3_600_000, exhausted = false))))
        return account
    }

    private fun statusRecords() = manager.activeNotifications.filter { it.notification.channelId == StatusIconPublisher.CHANNEL_ID }

    private fun render() {
        compose.setContent {
            val state by vm.state.collectAsState()
            UsageLimitsTheme {
                SettingsScreen(
                    state = state,
                    onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {}, onNotifyExhausted = {},
                    onNotifyResetCredit = {}, onNotifyAuthExpired = {}, onNotifyResetApproaching = {},
                    onNotifyCreditExpiring = {}, onShowTier = {}, onShowRenewal = {}, onPercentDisplayChange = {},
                    onNotificationPermissionAsked = vm::recordNotificationPermissionAsked,
                    onStatusIconsChange = vm::setStatusIcons,
                    onPinnedAccountChange = vm::setPinnedAccount,
                    onNotificationsAllowed = vm::notificationsAllowed,
                )
            }
        }
        compose.waitUntil(10_000) { vm.state.value.loaded && vm.state.value.accounts.isNotEmpty() }
    }

    /**
     * Waits for [condition] while running what the view model posted to the main looper, which
     * Robolectric keeps paused and the Compose clock does not drain.
     */
    private fun settle(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            ShadowLooper.idleMainLooper()
            condition()
        }
    }

    private fun turnOn() {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Show status bar icons"))
        compose.onNodeWithContentDescription("Show status bar icons").performClick()
        settle { runBlocking { app.container.settingsStore.settings.first().statusIconsEnabled } }
        assertEquals("nothing can be posted before the grant", 0, statusRecords().size)
    }

    private fun assertIconShownAndAnnounced() {
        settle { statusRecords().size == 2 }
        val records = statusRecords()
        assertEquals("one summary", 1, records.count { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 })
        assertEquals("one child", 1, records.count { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 })
        assertEquals(setOf("Claude"), records.map { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() }.toSet())
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Status bar icons can be shown."))
        compose.onNodeWithText("Status bar icons can be shown.").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("Status bar icons cannot be shown until notifications are allowed.").assertDoesNotExist()
    }

    @Test
    fun `granting the dialog shows the chosen account's icon without a sync`() {
        runBlocking { app.container.settingsStore.recordNotificationPermissionAsked() }
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, true)
        render()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Status bar icons cannot be shown until notifications are allowed."))
        compose.onNodeWithText("Status bar icons cannot be shown until notifications are allowed.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        turnOn()
        compose.waitUntil(10_000) { shadowOf(compose.activity).lastRequestedPermission != null }
        val request = shadowOf(compose.activity).lastRequestedPermission
        compose.runOnIdle {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(PackageManager.PERMISSION_GRANTED))
        }

        assertIconShownAndAnnounced()
    }

    @Test
    fun `allowing notifications in system settings shows the chosen account's icon on return`() {
        runBlocking { app.container.settingsStore.recordNotificationPermissionAsked() }
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, false)
        render()

        turnOn()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        assertIconShownAndAnnounced()
    }
}
