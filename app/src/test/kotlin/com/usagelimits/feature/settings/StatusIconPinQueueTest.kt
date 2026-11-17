package com.usagelimits.feature.settings

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.sync.OutOfAppPublication
import com.usagelimits.core.sync.Publication
import com.usagelimits.core.sync.resetTestWorkManager
import com.usagelimits.feature.UsageViewModel
import com.usagelimits.statusicon.StatusIconPublisher
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlinx.coroutines.CompletableDeferred
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
 * Pin toggles tapped while an earlier pin change is still being published, through the real
 * Settings screen, view model and publication: each tap is one account's change, applied to the
 * pins stored when its turn comes, and the screen shows the changes waiting their turn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusIconPinQueueTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    private val held = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()
    private var holdNext = false

    private lateinit var container: AppContainer
    private lateinit var vm: UsageViewModel
    private lateinit var a: ProviderAccount
    private lateinit var b: ProviderAccount
    private lateinit var c: ProviderAccount

    @Before fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        container = object : AppContainer(app) {
            override val publication: Publication by lazy {
                OutOfAppPublication(app, repository, creditRepository, settingsStore,
                    notificationPublisher, statusIconPublisher,
                    // The step before the status icons; the first publication after holdNext waits here.
                    scheduleBoundary = {
                        if (holdNext) {
                            holdNext = false
                            held.complete(Unit)
                            gate.await()
                        }
                    })
            }
        }
        a = account("Alpha")
        b = account("Beta")
        c = account("Gamma")
        container.settingsStore.setStatusIconsEnabled(true)
        container.settingsStore.setPinnedAccounts(listOf(a.localId))
        container.publication.presentationChanged()
        vm = UsageViewModel(container, app)
    }

    @After fun tearDown() {
        gate.complete(Unit)
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
    }

    private suspend fun account(name: String): ProviderAccount {
        val account = container.repository.upsertFromLogin(ProviderId.CLAUDE, "$name-${System.nanoTime()}", null, name, null, emptyMap())
        val now = System.currentTimeMillis()
        container.repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.OK, windows = listOf(
            UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, usedPercent = 58.0, periodSeconds = 18_000,
                resetAt = now + 3_600_000, exhausted = false))))
        return account
    }

    private fun showing(): Set<String> = manager.activeNotifications
        .filter { it.notification.channelId == StatusIconPublisher.CHANNEL_ID }
        .map { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() }
        .toSet()

    private fun toggle(name: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
        compose.onNodeWithContentDescription("Pin $name").performClick()
    }

    private fun settle(condition: () -> Boolean) = compose.waitUntil(10_000) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    @Test fun `pinning C and unpinning A while pinning B is published leaves B and C`() {
        compose.setContent {
            val state by vm.state.collectAsState()
            UsageLimitsTheme {
                SettingsScreen(
                    state = state,
                    onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {}, onNotifyExhausted = {},
                    onNotifyResetCredit = {}, onNotifyAuthExpired = {}, onNotifyResetApproaching = {},
                    onNotifyCreditExpiring = {}, onShowTier = {}, onShowRenewal = {}, onPercentDisplayChange = {},
                    onNotificationPermissionHandled = {},
                    onStatusIconsChange = vm::setStatusIcons,
                    onPinnedChange = vm::setPinned,
                )
            }
        }
        settle { vm.state.value.loaded && vm.state.value.accounts.size == 3 && vm.state.value.settings.statusIconsEnabled }
        assertEquals(setOf("Alpha"), showing())

        holdNext = true
        toggle("Beta")
        settle { held.isCompleted }
        // While Beta's publication is held, Gamma is pinned and Alpha unpinned.
        toggle("Gamma")
        toggle("Alpha")
        // The screen shows the changes at once, not the stored pins of a moment ago.
        compose.onNodeWithContentDescription("Pin Gamma").assertIsOn()
        compose.onNodeWithContentDescription("Pin Alpha").assertIsOff()
        compose.onNodeWithContentDescription("Pin Beta").assertIsOn()

        gate.complete(Unit)
        // Every change has had its turn once nothing is waiting any more; then the outcome is read.
        settle { vm.state.value.pendingPins.isEmpty() }

        assertEquals("independent changes are all kept",
            listOf(b.localId, c.localId), runBlocking { container.settingsStore.settings.first().pinnedAccountIds })
        assertEquals(setOf("Beta", "Gamma"), showing())
        compose.onNodeWithContentDescription("Pin Gamma").assertIsOn()
        compose.onNodeWithContentDescription("Pin Alpha").assertIsOff()
    }
}
