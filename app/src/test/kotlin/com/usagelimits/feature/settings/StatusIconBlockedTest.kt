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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.usagelimits.statusicon.PreferencesShownSelection
import com.usagelimits.statusicon.StatusIconPublisher
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlinx.coroutines.Job
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

    /** How often the screen reported that status icons can be shown again. */
    private var allowedEvents = 0

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
                    onNotifyCreditExpiring = {}, onShowTier = {}, onOverviewLayoutChange = {}, onPercentDisplayChange = {},
                    onNotificationPermissionHandled = vm::recordNotificationPermissionHandled,
                    onStatusIconsChange = vm::setStatusIcons,
                    onPinnedChange = vm::setPinned,
                    onNotificationsAllowed = {
                        allowedEvents++
                        vm.notificationsAllowed()
                    },
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

    /** What a person can switch off in Android that keeps every status icon away. */
    private enum class Block(val note: String) {
        APP("Status bar icons cannot be shown until notifications are allowed."),
        PERMISSION("Status bar icons cannot be shown until notifications are allowed."),
        CATEGORY("Status bar icons cannot be shown while their notification category is turned off."),
    }

    /** The runtime permission exists from Android 13 only. */
    private fun blocks() = Block.entries.filter { it != Block.PERMISSION || android.os.Build.VERSION.SDK_INT >= 33 }

    /** Switches [block] off as Android does it: what the channel showed is removed. */
    private fun switchOff(block: Block) {
        when (block) {
            Block.APP -> shadowOf(manager).setNotificationsEnabled(false)
            Block.PERMISSION -> shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            Block.CATEGORY -> blockCategory()
        }
        statusRecords().forEach { manager.cancel(it.tag, it.id) }
    }

    private fun switchOn(block: Block) = when (block) {
        Block.APP -> shadowOf(manager).setNotificationsEnabled(true)
        Block.PERMISSION -> shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Block.CATEGORY -> unblockCategory()
    }

    private suspend fun account(name: String, provider: ProviderId, left: Double): ProviderAccount {
        val repository = app.container.repository
        val account = repository.upsertFromLogin(provider, "$name-${System.nanoTime()}", null, name, null, emptyMap())
        val now = System.currentTimeMillis()
        repository.saveSnapshot(UsageSnapshot(account.localId, now, SnapshotStatus.OK, windows = listOf(
            UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, usedPercent = 100.0 - left, periodSeconds = 18_000,
                resetAt = now + 3_600_000, exhausted = false))))
        return account
    }

    /** The accounts whose icon is in the status bar, by their tap destination. */
    private fun showing(): Set<String> =
        statusRecords().map { shadowOf(it.notification.contentIntent).savedIntent.getStringExtra("accountId")!! }.toSet()

    private fun dismiss(account: ProviderAccount) {
        statusRecords().filter { shadowOf(it.notification.contentIntent).savedIntent.getStringExtra("accountId") == account.localId }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /**
     * Alpha (5 % left, an alert the first time it is evaluated) and Beta, both pinned and both
     * showing, with no alert yet.
     */
    private fun twoPinned(): Pair<ProviderAccount, ProviderAccount> = runBlocking {
        val alpha = app.container.repository.accountUsageOnce().single().account
        val beta = account("Beta", ProviderId.CODEX, 42.0)
        app.container.settingsStore.setPinnedAccounts(listOf(alpha.localId, beta.localId))
        app.container.publication.presentationChanged()
        assertEquals(setOf(alpha.localId, beta.localId), showing())
        alpha to beta
    }

    /** Two accounts pinned and showing, on the Settings screen. */
    private fun renderTwoPinned(): Pair<ProviderAccount, ProviderAccount> {
        val pair = twoPinned()
        render()
        compose.waitDrained { vm.state.value.accounts.size == 2 }
        return pair
    }

    /**
     * Two pinned, Beta swiped away; each [Block] switched off and seen by Settings; then, while
     * the person is still in Android's settings, [during]; then switched on and back to Settings.
     */
    private fun unblockAfter(during: suspend () -> Unit) {
        val (alpha, beta) = renderTwoPinned()
        val viewModelJob = vm.viewModelScope.coroutineContext[Job]!!
        // The screen's state observer lives as long as the view model; every other job is a publication.
        val observers = viewModelJob.children.toSet()
        for (block in blocks()) {
            assertEquals("$block: both showing before", setOf(alpha.localId, beta.localId), showing())
            dismiss(beta)
            assertEquals(setOf(alpha.localId), showing())

            returnAfter { switchOff(block) }
            shown(block.note)
            assertEquals("$block: nothing shows while switched off", 0, statusRecords().size)

            var alertsBefore = 0
            returnAfter {
                runBlocking { during() }
                alertsBefore = alertRecords().size
                switchOn(block)
            }
            assertTrue("$block: switching it on brings every pinned icon back, the swiped one too",
                eventually { showing() == setOf(alpha.localId, beta.localId) && statusRecords().size == 4 })
            // Whatever the edge started has ended, alerts included, before they are counted.
            compose.waitDrained { (viewModelJob.children.toSet() - observers).isEmpty() }
            shown("Status bar icons can be shown.")
            assertEquals("$block: allowing again posts no alert", alertsBefore, alertRecords().size)
        }
    }

    /** The unblocking evaluated no alert and claimed none: the next sync still raises it. */
    private fun alertStillPending() {
        assertEquals(0, alertRecords().size)
        runBlocking { app.container.publication.cacheMoved() }
        assertEquals(1, alertRecords().size)
    }

    @Test
    fun `allowing again brings every pinned icon back when nothing was published while blocked`() {
        unblockAfter { }
        alertStillPending()
    }

    @Test
    fun `allowing again brings every pinned icon back after a presentation change while blocked`() {
        unblockAfter { app.container.publication.presentationChanged() }
        alertStillPending()
    }

    @Test
    fun `allowing again brings every pinned icon back after a sync while blocked`() {
        unblockAfter { app.container.publication.cacheMoved() }
    }

    /**
     * Both switched off; notifications switched on again while the category stays off. The icons
     * still cannot be shown, so nothing is published and what was meant to be shown is kept;
     * only switching the category on is the moment they can be shown again.
     */
    @Test
    fun `notifications allowed while the category stays off publish nothing until the category is on`() {
        val (alpha, beta) = renderTwoPinned()
        val both = listOf(alpha.localId, beta.localId)
        val selection = PreferencesShownSelection(app)
        assertEquals(both, selection.read())
        val viewModelJob = vm.viewModelScope.coroutineContext[Job]!!
        val observers = viewModelJob.children.toSet()

        returnAfter {
            shadowOf(manager).setNotificationsEnabled(false)
            blockCategory()
        }
        shown(Block.APP.note)

        returnAfter { shadowOf(manager).setNotificationsEnabled(true) }
        shown(Block.CATEGORY.note)
        // Anything the return started has ended before the selection is read.
        compose.waitDrained { (viewModelJob.children.toSet() - observers).isEmpty() }
        assertEquals("the category is still off: no event", 0, allowedEvents)
        assertEquals("the selection is untouched", both, selection.read())
        assertEquals(0, statusRecords().size)

        returnAfter(::unblockCategory)
        assertTrue("switching the category on brings every pinned icon back",
            eventually { showing() == both.toSet() && statusRecords().size == 4 })
        assertEquals("exactly one event", 1, allowedEvents)
    }

    @Test
    fun `an account unpinned while blocked stays away when allowed again`() {
        val (alpha, beta) = renderTwoPinned()
        returnAfter { switchOff(Block.CATEGORY) }
        shown(Block.CATEGORY.note)

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Pin Claude"))
        compose.onNodeWithContentDescription("Pin Claude").performClick()
        compose.waitDrained { vm.state.value.settings.pinnedAccountIds == listOf(beta.localId) && vm.state.value.pendingPins.isEmpty() }

        returnAfter(::unblockCategory)
        assertTrue("only the account still pinned returns", eventually { showing() == setOf(beta.localId) })
        assertEquals(2, statusRecords().size)
        assertTrue(alpha.localId !in showing())
    }

    @Test
    fun `the feature switched off while blocked stays off when allowed again`() {
        renderTwoPinned()
        val viewModelJob = vm.viewModelScope.coroutineContext[Job]!!
        val observers = viewModelJob.children.toSet()
        returnAfter { switchOff(Block.APP) }
        shown(Block.APP.note)

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Show status bar icons"))
        compose.onNodeWithContentDescription("Show status bar icons").performClick()
        compose.waitDrained { !vm.state.value.settings.statusIconsEnabled }

        returnAfter { shadowOf(manager).setNotificationsEnabled(true) }
        shown("Status bar icons can be shown.")
        // The switch and the return each started a publication in the view model. One still under
        // way holds the publisher's lock and resumes on the main looper, which the blocking call
        // below occupies: the two then wait for each other for ever. So they end first.
        compose.waitDrained { (viewModelJob.children.toSet() - observers).isEmpty() }
        runBlocking { app.container.publication.presentationChanged() }
        assertEquals("switched off stays off", 0, statusRecords().size)
    }

    /**
     * Allowed again in Android without coming back to the app: no edge is observed. The next sync
     * brings every pinned icon back. A presentation change before it is best effort: it brings
     * them all back if a publication noticed the block, otherwise nothing.
     */
    @Test
    fun `allowed again without returning, the next sync brings every pinned icon back and a presentation change is best effort`() = runBlocking {
        val (alpha, beta) = twoPinned()
        val both = setOf(alpha.localId, beta.localId)
        dismiss(beta)
        switchOff(Block.CATEGORY)
        unblockCategory()
        app.container.publication.presentationChanged()
        assertEquals("no publication saw the block: a presentation change brings nothing back", emptySet<String>(), showing())
        app.container.publication.cacheMoved()
        assertEquals("the next sync brings every pinned icon back", both, showing())

        dismiss(beta)
        switchOff(Block.CATEGORY)
        app.container.publication.presentationChanged()
        unblockCategory()
        app.container.publication.presentationChanged()
        assertEquals("a publication saw the block: a presentation change brings them all back", both, showing())
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
