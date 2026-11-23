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
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
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
 * Granting the notification permission shows the icon of the account already chosen, from the
 * cache, without a sync: through the dialog, and on return from the system's notification
 * settings. Real screen, real view model, real publication, Robolectric's notification manager;
 * no provider is ever asked for data.
 *
 * The icons are on and the account is chosen before the screen exists, and the publication made
 * while the permission was still denied is awaited to its end and has posted nothing. The grant
 * is then requested through the notification card, so the only thing left that can post the
 * icon is the grant itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34, 37])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusIconPermissionGrantTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var owned: OwnedViewModel
    private lateinit var selectedAccountId: String
    private val vm: UsageViewModel get() = owned.vm

    private lateinit var original: com.usagelimits.core.di.AppContainer
    private var eligibilityJob: kotlinx.coroutines.Job? = null
    private var observers = emptySet<kotlinx.coroutines.Job>()
    private var allowedEvents = 0

    @Before
    fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        original = app.container
        val container = object : com.usagelimits.core.di.AppContainer(app) {
            override val publication = object : com.usagelimits.core.sync.Publication by original.publication {
                override suspend fun notificationsAllowedAgain() {
                    allowedEvents++
                    original.publication.notificationsAllowedAgain()
                }
            }
        }
        org.robolectric.util.ReflectionHelpers.setField(app, "container", container)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = app.container.settingsStore
        store.recordNotificationPermissionHandled()
        store.recordStatusIconEligibility(false)
        store.setStatusIconsEnabled(true)
        selectedAccountId = account().localId
        store.setPinnedAccounts(listOf(selectedAccountId))
        owned = OwnedViewModel(app.container, app)

        // The publication of the choice, made while notifications are denied, runs to its end
        // here, and posts nothing.
        app.container.publication.presentationChanged()
        assertEquals("nothing can be posted before the grant", 0, statusRecords().size + alertRecords().size)
    }

    /** Nothing this class started may outlive it: see `resetTestWorkManager`. */
    @After
    fun drain() {
        // Both publication owners finish before the infrastructure under them is reset.
        compose.runOnUiThread { eligibilityJob = app.observeStatusIconEligibility() }
        compose.waitDrained { eligibilityJob?.isCompleted == true }
        owned.clear()
        org.robolectric.util.ReflectionHelpers.setField(app, "container", original)
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
    }

    /** Claude with 5 % left: a reading that crosses the alert threshold the first time it is evaluated. */
    private suspend fun account(): ProviderAccount {
        val repository = app.container.repository
        val account = repository.upsertFromLogin(ProviderId.CLAUDE, "grant-${System.nanoTime()}", null, "Claude", null, emptyMap())
        val now = System.currentTimeMillis()
        repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.OK, windows = listOf(
            UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, usedPercent = 95.0, periodSeconds = 18_000,
                resetAt = now + 3_600_000, exhausted = false))))
        return account
    }

    private fun statusRecords() = manager.activeNotifications.filter { it.notification.channelId == StatusIconPublisher.CHANNEL_ID }

    private fun alertRecords() = manager.activeNotifications.filter { it.notification.channelId == NotificationPublisher.CHANNEL_ID }

    private fun render() {
        compose.setContent {
            val state by vm.state.collectAsState()
            UsageLimitsTheme {
                SettingsScreen(
                    state = state,
                    onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {}, onNotifyExhausted = {},
                    onNotifyResetCredit = {}, onNotifyAuthExpired = {}, onNotifyResetApproaching = {},
                    onNotifyCreditExpiring = {}, onShowTier = {}, onOverviewLayoutChange = {}, onPercentDisplayChange = {},
                    onNotificationPermissionHandled = vm::recordNotificationPermissionHandled, onAppThemeChange = vm::setAppTheme,
                    onStatusIconsChange = vm::setStatusIcons,
                    onPinnedChange = vm::setPinned,
                )
            }
        }
        // Room/DataStore results resume on the paused Android looper, not the Compose clock.
        compose.waitDrained { vm.state.value.loaded && vm.state.value.accounts.isNotEmpty() }
        compose.runOnUiThread {
            compose.activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                    eligibilityJob = app.observeStatusIconEligibility()
                }
            })
        }
        compose.waitDrained { eligibilityJob?.isCompleted == true }
        observers = vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.children.toSet()
    }

    /**
     * Whether [condition] holds within ten seconds, running meanwhile what the view model posted
     * to the main looper, which Robolectric keeps paused and the Compose clock does not drain.
     */
    private fun eventually(condition: () -> Boolean): Boolean = try {
        compose.waitUntil(10_000) {
            ShadowLooper.idleMainLooper()
            condition()
        }
        true
    } catch (_: ComposeTimeoutException) {
        condition()
    }

    private fun clickCardButton(label: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
    }

    private fun resumeAgain() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.waitDrained { eligibilityJob?.isCompleted == true }
    }

    /**
     * After the grant: the icon, as one summary and one child, posted by the grant alone; the
     * status announced; no alert evaluated by it; and the alert still there for the next real
     * cache event, so the grant consumed nothing.
     */
    private fun assertGrantPublishedOnlyTheIcon() {
        assertTrue("the grant must post the chosen account's icon", eventually { statusRecords().size == 2 })
        compose.runOnUiThread { eligibilityJob = app.observeStatusIconEligibility() }
        compose.waitDrained { eligibilityJob?.isCompleted == true }
        val records = statusRecords()
        assertEquals("one summary", 1, records.count { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 })
        assertEquals("one child", 1, records.count { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 })
        assertEquals(setOf(selectedAccountId), records.map { shadowOf(it.notification.contentIntent).savedIntent.getStringExtra("accountId") }.toSet())
        assertEquals("one transition, one publication", 1, allowedEvents)
        assertEquals("the grant evaluates no alert", 0, alertRecords().size)

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Status bar icons can be shown."))
        compose.onNodeWithText("Status bar icons can be shown.").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("Status bar icons cannot be shown until notifications are allowed.").assertDoesNotExist()

        resumeAgain()
        assertEquals("coming back while already allowed publishes nothing more", 1, allowedEvents)

        val viewModelJob = vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!
        compose.waitDrained { viewModelJob.children.none { it !in observers } && eligibilityJob?.isCompleted != false }
        runBlocking { app.container.publication.cacheMoved() }
        assertEquals("the next cache event still alerts", 1, alertRecords().size)
    }

    @Test
    fun `granting the dialog shows the chosen account's icon without a sync`() {
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, true)
        render()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Status bar icons cannot be shown until notifications are allowed."))
        compose.onNodeWithText("Status bar icons cannot be shown until notifications are allowed.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        clickCardButton("Allow notifications")
        compose.waitDrained { shadowOf(compose.activity).lastRequestedPermission != null }
        val request = shadowOf(compose.activity).lastRequestedPermission
        compose.runOnIdle {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(PackageManager.PERMISSION_GRANTED))
        }

        assertGrantPublishedOnlyTheIcon()
    }

    @Test
    fun `allowing notifications in system settings shows the chosen account's icon on return`() {
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, false)
        render()

        clickCardButton("Open notification settings")
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        assertGrantPublishedOnlyTheIcon()
    }
}
