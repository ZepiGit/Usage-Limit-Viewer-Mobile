package com.usagelimits.feature.settings

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.usagelimits.core.sync.NotificationPublisher
import com.usagelimits.core.sync.resetTestWorkManager
import com.usagelimits.feature.OwnedViewModel
import com.usagelimits.feature.UsageViewModel
import com.usagelimits.feature.waitDrained
import com.usagelimits.statusicon.StatusIconPublisher
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * Notifications switched off in Android, for the whole app or for the "Status bar icons"
 * category alone, on a level without the runtime permission (30) and one with it (34).
 *
 * Real screen, real view model, real publication, Robolectric's notification manager. That
 * manager posts into a blocked category as if it were open and keeps the records of an app whose
 * notifications are switched off, which Android does neither; so a record posted while blocked
 * is the publication claiming to show what Android would not, and the tests remove the records
 * where Android would have removed them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [30, 34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusIconBlockedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var owned: OwnedViewModel
    private val vm: UsageViewModel get() = owned.vm

    @Before
    fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = app.container.settingsStore
        store.recordNotificationPermissionHandled()
        store.setStatusIconsEnabled(true)
        store.setPinnedAccounts(listOf(account().localId))
        owned = OwnedViewModel(app.container, app)
    }

    /** Nothing this class started may outlive it: see `resetTestWorkManager`. */
    @After
    fun drain() {
        owned.clear()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
    }

    /** Claude with 5 % left: a reading that crosses the alert threshold the first time it is evaluated. */
    private suspend fun account(): ProviderAccount {
        val repository = app.container.repository
        val account = repository.upsertFromLogin(ProviderId.CLAUDE, "blocked-${System.nanoTime()}", null, "Claude", null, emptyMap())
        val now = System.currentTimeMillis()
        repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.OK, windows = listOf(
            UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, usedPercent = 95.0, periodSeconds = 18_000,
                resetAt = now + 3_600_000, exhausted = false))))
        return account
    }

    private fun statusRecords() = manager.activeNotifications.filter { it.notification.channelId == StatusIconPublisher.CHANNEL_ID }

    private fun alertRecords() = manager.activeNotifications.filter { it.notification.channelId == NotificationPublisher.CHANNEL_ID }

    /** The person switches the category off in Android, which removes what it showed. */
    private fun blockCategory() {
        val channel = manager.getNotificationChannel(StatusIconPublisher.CHANNEL_ID)
        if (channel == null) {
            manager.createNotificationChannel(NotificationChannel(StatusIconPublisher.CHANNEL_ID, "Status bar icons", NotificationManager.IMPORTANCE_NONE))
        } else {
            channel.importance = NotificationManager.IMPORTANCE_NONE
        }
        statusRecords().forEach { manager.cancel(it.tag, it.id) }
    }

    private fun unblockCategory() {
        manager.getNotificationChannel(StatusIconPublisher.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun render() {
        compose.setContent {
            val state by vm.state.collectAsState()
            UsageLimitsTheme {
                SettingsScreen(
                    state = state,
                    onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {}, onNotifyExhausted = {},
                    onNotifyResetCredit = {}, onNotifyAuthExpired = {}, onNotifyResetApproaching = {},
                    onNotifyCreditExpiring = {}, onShowTier = {}, onShowRenewal = {}, onPercentDisplayChange = {},
                    onNotificationPermissionHandled = vm::recordNotificationPermissionHandled,
                    onStatusIconsChange = vm::setStatusIcons,
                    onPinnedChange = vm::setPinned,
                    onNotificationsAllowed = vm::notificationsAllowed,
                )
            }
        }
        compose.waitDrained { vm.state.value.loaded && vm.state.value.accounts.isNotEmpty() }
    }

    private fun eventually(condition: () -> Boolean): Boolean = try {
        compose.waitUntil(10_000) {
            ShadowLooper.idleMainLooper()
            condition()
        }
        true
    } catch (_: ComposeTimeoutException) {
        condition()
    }

    private fun shown(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    /** Leaves for the system settings and comes back after [change] was made there. */
    private fun returnAfter(change: () -> Unit) {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        change()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test
    fun `notifications switched off for the app are explained, lead to its notification settings, and allowing them shows the icons on return`() {
        shadowOf(manager).setNotificationsEnabled(false)
        runBlocking { app.container.publication.cacheMoved() }
        assertEquals("nothing is shown while the app's notifications are off", 0, statusRecords().size)

        render()
        shown("Notifications not allowed")
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
        shown("Status bar icons cannot be shown until notifications are allowed.")
        compose.onNodeWithText("Status bar icons can be shown.").assertDoesNotExist()

        shown("Open notification settings")
        compose.onNodeWithText("Open notification settings").performClick()
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(app.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertNull("no runtime dialog for notifications that are switched off", shadowOf(compose.activity).lastRequestedPermission)

        returnAfter { shadowOf(manager).setNotificationsEnabled(true) }
        assertTrue("allowing them shows the pinned account's icon on return", eventually { statusRecords().size == 2 })
        shown("Notifications allowed")
        shown("Status bar icons can be shown.")
    }

    @Test
    fun `a switched-off status bar icons category is explained apart from alerts, leads to its own settings, and turning it on shows the icons on return`() {
        runBlocking { app.container.publication.presentationChanged() }
        assertEquals("shown while allowed", 2, statusRecords().size)
        blockCategory()
        runBlocking { app.container.publication.cacheMoved() }
        assertEquals("nothing is posted into the switched-off category", 0, statusRecords().size)
        assertEquals("alerts still arrive", 1, alertRecords().size)

        render()
        shown("Notifications allowed")
        shown("Status bar icons cannot be shown while their notification category is turned off.")
        compose.onNodeWithText("Status bar icons can be shown.").assertDoesNotExist()

        shown("Open category settings")
        compose.onNodeWithText("Open category settings").performClick()
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(app.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(StatusIconPublisher.CHANNEL_ID, opened.getStringExtra(Settings.EXTRA_CHANNEL_ID))

        returnAfter(::unblockCategory)
        assertTrue("turning the category on shows the pinned account's icon on return", eventually { statusRecords().size == 2 })
        shown("Status bar icons can be shown.")
        compose.onNodeWithText("Open category settings").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "de")
    fun `German Settings explains a switched-off category`() {
        blockCategory()
        render()
        shown("Benachrichtigungen erlaubt")
        shown("Statusleisten-Symbole können nicht angezeigt werden, solange ihre Benachrichtigungskategorie ausgeschaltet ist.")
        shown("Kategorie-Einstellungen öffnen")
    }
}
