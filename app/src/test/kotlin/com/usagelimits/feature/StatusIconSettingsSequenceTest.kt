package com.usagelimits.feature

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
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
import com.usagelimits.statusicon.StatusIconPublisher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * A person's quick off→on, or unpin→pin, must each be published in turn, through the real view
 * model and the real publication module. The first change's publication is held at its
 * presentation boundary, before its status icon step, while the second change is made; then it
 * is let go. If the second change's setting could be written before the first change's status
 * step read the setting, no status step would ever see "off", and an icon the person had swiped
 * away would not come back, against the spec's dismissal contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatusIconSettingsSequenceTest {
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val viewModels = ViewModelStore()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    /** Completed when the first held publication reaches its boundary; [release] lets it go on. */
    private val held = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private var holdNext = false

    private lateinit var container: AppContainer
    private lateinit var vm: UsageViewModel

    @Before fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        container = object : AppContainer(app) {
            override val publication: Publication by lazy {
                OutOfAppPublication(app, repository, creditRepository, settingsStore,
                    notificationPublisher, statusIconPublisher,
                    scheduleBoundary = {
                        if (holdNext) {
                            holdNext = false
                            held.complete(Unit)
                            release.await()
                        }
                    })
            }
        }
        container.settingsStore.setStatusIconsEnabled(false)
        container.settingsStore.setPinnedAccounts(emptyList())
        vm = UsageViewModel(container, app)
        viewModels.put("usage", vm)
    }

    @After fun tearDown() {
        release.complete(Unit)
        viewModels.clear()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        Dispatchers.resetMain()
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

    /** Waits for every job the view model has started since [before], bounded. */
    private suspend fun settle(before: Set<Job>) = withTimeout(10_000) {
        vm.viewModelScope.coroutineContext[Job]!!.children.filterNot { it in before }.toList().joinAll()
    }

    /**
     * Alpha (and Beta) are pinned and showing; Alpha is swiped away (active-list scenario: its
     * records are cancelled in the notification manager). Then [first] is made with its
     * publication held, [second] is made, and the held publication is let go.
     */
    private fun quickChange(first: () -> Unit, second: () -> Unit) = runBlocking {
        val a = account("Alpha")
        val b = account("Beta")
        val background = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        vm.setPinnedAccounts(listOf(a.localId, b.localId))
        vm.setStatusIcons(true)
        settle(background)
        assertEquals(setOf("Alpha", "Beta"), showing())
        manager.activeNotifications.filter { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == "Alpha" }
            .forEach { manager.cancel(it.tag, it.id) }
        assertEquals(setOf("Beta"), showing())

        holdNext = true
        first()
        withTimeout(10_000) { held.await() }
        val afterFirst = container.settingsStore.settings.first()
        second()
        // Let the second change go as far as it may while the first publication is held. If
        // its setting can be stored now, wait until it is, so the held status step can only
        // read the second value; if it is held back behind the first change, it never will be.
        val secondWritten = withTimeoutOrNull(2_000) { container.settingsStore.settings.first { it != afterFirst } } != null
        release.complete(Unit)
        settle(background)
        assertTrue("the second change is stored in the end", container.settingsStore.settings.first() != afterFirst)
        System.out.println("second change written while the first publication was held: $secondWritten")

        assertTrue("the dismissed icon comes back: ${showing()}", "Alpha" in showing())
        assertEquals(setOf("Alpha", "Beta"), showing())
    }

    @Test fun `switching off and quickly on again shows a dismissed icon again`() =
        quickChange(first = { vm.setStatusIcons(false) }, second = { vm.setStatusIcons(true) })

    @Test fun `unpinning and quickly pinning a dismissed account again shows it again`() {
        lateinit var a: String
        lateinit var b: String
        quickChange(
            first = {
                val pinned = runBlocking { container.settingsStore.settings.first().pinnedAccountIds }
                a = pinned[0]; b = pinned[1]
                vm.setPinnedAccounts(listOf(b))
            },
            second = { vm.setPinnedAccounts(listOf(a, b)) },
        )
    }
}
