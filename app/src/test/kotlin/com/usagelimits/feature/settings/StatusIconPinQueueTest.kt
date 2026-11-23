package com.usagelimits.feature.settings

import android.Manifest
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
import com.usagelimits.feature.OwnedViewModel
import com.usagelimits.feature.UsageViewModel
import com.usagelimits.feature.waitDrained
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
 * Pin toggles tapped while earlier pin changes are still being published, through the real
 * Settings screen, view model and publication. Each publication is held at its own gate, before
 * its status step, and let go one at a time; nothing waits on time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusIconPinQueueTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    /** One gate per publication while [holding]: published in the order they reached them. */
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private var holding = false

    private lateinit var container: AppContainer
    private lateinit var owned: OwnedViewModel
    private val vm: UsageViewModel get() = owned.vm
    private lateinit var a: ProviderAccount
    private lateinit var b: ProviderAccount
    private lateinit var c: ProviderAccount
    private lateinit var d: ProviderAccount

    @Before fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        container = object : AppContainer(app) {
            override val publication: Publication by lazy {
                OutOfAppPublication(app, repository, creditRepository, settingsStore,
                    notificationPublisher, statusIconPublisher,
                    // The step before the status icons; while holding, each publication waits here.
                    scheduleBoundary = {
                        if (holding) CompletableDeferred<Unit>().also { synchronized(gates) { gates += it } }.await()
                    })
            }
        }
        a = account("Alpha")
        b = account("Beta")
        c = account("Gamma")
        d = account("Delta")
        container.settingsStore.setStatusIconsEnabled(true)
        container.settingsStore.setPinnedAccounts(listOf(a.localId))
        container.publication.presentationChanged()
        owned = OwnedViewModel(container, app)
    }

    @After fun tearDown() {
        // Also on a failed test: let every held publication go, then end the view model's jobs
        // before the infrastructure under them is torn down.
        holding = false
        synchronized(gates) { gates.forEach { it.complete(Unit) } }
        owned.clear()
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
        .map { checkNotNull(shadowOf(it.notification.contentIntent).savedIntent.getStringExtra("accountId")) }
        .toSet()

    private fun stored(): List<String> = runBlocking { container.settingsStore.settings.first().pinnedAccountIds }

    private fun toggle(name: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
        compose.onNodeWithContentDescription("Pin $name").performClick()
    }

    private fun gatesReached() = synchronized(gates) { gates.size }

    /** Lets the oldest held publication go and waits until the next one is held or none waits. */
    private fun releaseNext(expectHeldAfter: Int) {
        synchronized(gates) { gates.first { !it.isCompleted } }.complete(Unit)
        compose.waitDrained { gatesReached() >= expectHeldAfter }
    }

    private fun render() {
        compose.setContent {
            val state by vm.state.collectAsState()
            UsageLimitsTheme {
                SettingsScreen(
                    state = state,
                    onSyncIntervalChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {}, onNotifyExhausted = {},
                    onNotifyResetCredit = {}, onNotifyAuthExpired = {}, onNotifyResetApproaching = {},
                    onNotifyCreditExpiring = {}, onShowTier = {}, onOverviewLayoutChange = {}, onPercentDisplayChange = {},
                    onNotificationPermissionHandled = {}, onAppThemeChange = vm::setAppTheme,
                    onStatusIconsChange = vm::setStatusIcons,
                    onPinnedChange = vm::setPinned,
                )
            }
        }
        compose.waitDrained { vm.state.value.loaded && vm.state.value.accounts.size == 4 && vm.state.value.settings.statusIconsEnabled }
    }

    @Test fun `pinning C and unpinning A while pinning B is published leaves B and C`() {
        render()
        assertEquals(setOf(a.localId), showing())

        holding = true
        toggle("Beta")
        compose.waitDrained { gatesReached() == 1 }
        toggle("Gamma")
        toggle("Alpha")
        // The screen shows the changes at once, not the stored pins of a moment ago.
        compose.onNodeWithContentDescription("Pin Gamma").assertIsOn()
        compose.onNodeWithContentDescription("Pin Alpha").assertIsOff()
        compose.onNodeWithContentDescription("Pin Beta").assertIsOn()

        holding = false
        releaseNext(expectHeldAfter = 1)
        compose.waitDrained { vm.state.value.pendingPins.isEmpty() }

        assertEquals("independent changes are all kept", listOf(b.localId, c.localId), stored())
        assertEquals(setOf(b.localId, c.localId), showing())
        compose.onNodeWithContentDescription("Pin Gamma").assertIsOn()
        compose.onNodeWithContentDescription("Pin Alpha").assertIsOff()
    }

    /**
     * Gamma pinned, unpinned and pinned again while the first of those is published. Each tap is
     * its own request: finishing the first must not take the last one's wish off the screen.
     */
    @Test fun `the last of several taps on one account stays shown until its own turn has come`() {
        runBlocking { container.settingsStore.setPinnedAccounts(listOf(a.localId, b.localId)) }
        render()

        holding = true
        toggle("Gamma")                                     // request 1: pin, held at its gate
        compose.waitDrained { gatesReached() == 1 }
        toggle("Gamma")                                     // request 2: unpin, queued
        toggle("Gamma")                                     // request 3: pin, queued
        compose.onNodeWithContentDescription("Pin Gamma").assertIsOn()

        // Request 1 completes; request 2 runs and is held at the next gate. Gamma's last wish is
        // still "pinned", so Gamma stays on, three count as pinned, and Delta is not offered.
        releaseNext(expectHeldAfter = 2)
        compose.onNodeWithText("3 of 3 pinned").assertExists()
        compose.onNodeWithContentDescription("Pin Gamma").assertIsOn().assertIsEnabled()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Delta"))
        compose.onNodeWithContentDescription("Pin Delta. 3 of 3 are pinned; unpin one first.").assertIsNotEnabled()

        // Request 2 completes; request 3 runs and is held.
        releaseNext(expectHeldAfter = 3)
        compose.onNodeWithContentDescription("Pin Gamma").assertIsOn()

        holding = false
        releaseNext(expectHeldAfter = 3)
        compose.waitDrained { vm.state.value.pendingPins.isEmpty() }
        assertEquals(listOf(a.localId, b.localId, c.localId), stored())
        assertEquals(setOf(a.localId, b.localId, c.localId), showing())
    }

    /** A pin the queue refuses when its turn comes is told to the person, not silently dropped. */
    @Test fun `a pin refused when its turn comes is reported`() {
        render()

        holding = true
        toggle("Gamma")
        compose.waitDrained { gatesReached() == 1 }
        // Delta is offered (two would be pinned) and tapped; then it is removed from the cache
        // while its pin waits behind Gamma's.
        toggle("Delta")
        runBlocking { container.repository.deleteAccount(d.localId) }

        holding = false
        releaseNext(expectHeldAfter = 1)
        compose.waitDrained { vm.state.value.pendingPins.isEmpty() }

        assertEquals(listOf(a.localId, c.localId), stored())
        assertEquals("Could not pin the account: it has been removed.", vm.state.value.message)
    }
}
