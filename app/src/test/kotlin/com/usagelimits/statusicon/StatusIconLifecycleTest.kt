package com.usagelimits.statusicon

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.sync.NotificationPublisher
import com.usagelimits.core.sync.resetTestWorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
 * The life of up to three status icons, through the publication module's two events, read back
 * from Robolectric's notification manager.
 *
 * Robolectric cannot swipe a notification. Where a test needs a dismissed icon it cancels that
 * account's records from the notification manager itself: an active-list scenario standing in
 * for a swipe on Android 14 and later. Android 11 keeps an ongoing notification and cannot
 * dismiss it at all; ticket 04's device run records that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatusIconLifecycleTest {

    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val publication get() = app.container.publication
    private val store get() = app.container.settingsStore
    private val now = System.currentTimeMillis()
    private val accountNames = mutableMapOf<String, String>()

    @Before
    fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        // Robolectric keeps one DataStore per sandbox, so start every test from the default.
        store.setStatusIconsEnabled(false)
        store.setPinnedAccounts(emptyList())
        store.setPercentDisplay(PercentDisplay.LEFT)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Nothing this class started may outlive it: see `resetTestWorkManager`. */
    @After
    fun drain() {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
    }

    private suspend fun account(name: String, left: Double, provider: ProviderId = ProviderId.CLAUDE): ProviderAccount {
        val account = app.container.repository.upsertFromLogin(provider, "$name-${System.nanoTime()}", null, name, null, emptyMap())
        reading(account, left)
        accountNames[account.localId] = name
        return account
    }

    private suspend fun reading(account: ProviderAccount, left: Double, fetchedAt: Long = System.currentTimeMillis(), resetAt: Long = now + 3_600_000) {
        app.container.repository.saveSnapshot(UsageSnapshot(account.localId, fetchedAt, SnapshotStatus.OK,
            windows = listOf(UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, usedPercent = 100.0 - left,
                periodSeconds = 18_000, resetAt = resetAt, exhausted = false))))
    }

    private suspend fun turnOn(vararg pinned: ProviderAccount) {
        store.setPinnedAccounts(pinned.map { it.localId })
        store.setStatusIconsEnabled(true)
        publication.presentationChanged()
    }

    private suspend fun pinned(vararg accounts: ProviderAccount) {
        store.setPinnedAccounts(accounts.map { it.localId })
        publication.presentationChanged()
    }

    private fun statusRecords(): List<StatusBarNotification> =
        manager.activeNotifications.filter { it.notification.channelId == StatusIconPublisher.CHANNEL_ID }

    private fun alertRecords(): List<StatusBarNotification> =
        manager.activeNotifications.filter { it.notification.channelId == NotificationPublisher.CHANNEL_ID }

    private fun StatusBarNotification.accountName() =
        accountNames.getValue(checkNotNull(shadowOf(notification.contentIntent).savedIntent.getStringExtra("accountId")))
    private fun StatusBarNotification.title() = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun StatusBarNotification.isSummary() = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    /** The accounts that have an icon, identified by their tap destination, each counted once. */
    private fun showing(): Set<String> = statusRecords().map { it.accountName() }.toSet()

    /** Active-list scenario: removes an account's records the way a swipe on Android 14+ would. */
    private fun dismiss(name: String) {
        statusRecords().filter { it.accountName() == name }.forEach { manager.cancel(it.tag, it.id) }
        assertTrue("$name is gone before the next step", name !in showing())
    }

    @Test
    fun `three pinned accounts are six records in three groups of one summary and one child`() = runBlocking {
        val a = account("Alpha", 42.0)
        val b = account("Beta", 87.0, ProviderId.CODEX)
        val c = account("Gamma", 5.0, ProviderId.XAI)
        turnOn(a, b, c)

        val records = statusRecords()
        assertEquals(6, records.size)
        val groups = records.groupBy { it.notification.group }
        assertEquals(3, groups.size)
        for ((group, members) in groups) {
            assertEquals("$group: one summary", 1, members.count { it.isSummary() })
            assertEquals("$group: one child", 1, members.count { !it.isSummary() })
            assertEquals("$group belongs to one account", 1, members.map { it.accountName() }.toSet().size)
        }
        val identities = records.map { it.tag to it.id }.toSet()

        publication.cacheMoved()
        assertEquals("identities are stable per account", identities, statusRecords().map { it.tag to it.id }.toSet())
    }

    @Test
    fun `unpinning, switching off and missing permission remove icons or post none`() = runBlocking {
        val a = account("Alpha", 42.0)
        val b = account("Beta", 87.0)
        val c = account("Gamma", 5.0)
        turnOn(a, b, c)

        pinned(a, c)
        assertEquals("unpinning one leaves four records", 4, statusRecords().size)
        assertEquals(setOf("Alpha", "Gamma"), showing())

        store.setStatusIconsEnabled(false)
        publication.presentationChanged()
        assertEquals(0, statusRecords().size)

        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store.setStatusIconsEnabled(true)
        publication.presentationChanged()
        publication.cacheMoved()
        assertEquals("nothing is posted without permission", 0, statusRecords().size)
    }

    @Test
    fun `a dismissed icon stays away until the next sync, and pinning another account does not bring it back`() = runBlocking {
        val a = account("Alpha", 42.0)
        val b = account("Beta", 87.0)
        turnOn(a)
        dismiss("Alpha")

        pinned(a, b)
        assertEquals("pinning Beta shows Beta only", setOf("Beta"), showing())

        // A time boundary, as the presentation worker publishes it.
        publication.presentationChanged()
        assertEquals("a boundary does not restore the dismissed icon", setOf("Beta"), showing())

        publication.cacheMoved()
        assertEquals("the next sync brings it back", setOf("Alpha", "Beta"), showing())
        assertEquals(4, statusRecords().size)
    }

    @Test
    fun `a swipe after the active read stays dismissed during an in-flight repaint`() = runBlocking {
        val a = account("Alpha", 42.0)
        val b = account("Beta", 87.0)
        turnOn(a, b)
        reading(a, 41.0)
        reading(b, 86.0)
        var clock = now
        val accounts = app.container.repository.accountUsageOnce()
        val settings = store.settings.first()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val publisher = StatusIconPublisher(app, store, readAccounts = { accounts }, readSettings = { settings },
            now = { clock }, nextTick = {
                if (!entered.isCompleted) {
                    entered.complete(Unit)
                    release.await()
                }
                clock++
            }, monotonic = { clock })

        runTest {
            val repaint = launch { publisher.publish(restoreDismissed = false) }
            try {
                runCurrent()
                assertTrue("the repaint read active groups and is waiting before its first post", entered.isCompleted && !repaint.isCompleted)
                dismiss("Alpha")
                release.complete(Unit)
                repaint.join()
            } finally {
                release.complete(Unit)
                repaint.cancelAndJoin()
            }
        }

        assertEquals("the late active-group check must not undo the swipe", setOf("Beta"), showing())
        assertEquals("the surviving group keeps both records", 2, statusRecords().size)
        assertTrue("the group still showing receives its new reading", statusRecords().all { it.title().startsWith("86% left") })
        publication.presentationChanged()
        assertEquals("a later non-sync repaint also respects the dismissal", setOf("Beta"), showing())
        publication.cacheMoved()
        assertEquals("a real cache event restores the dismissed group", setOf("Alpha", "Beta"), showing())
    }

    @Test
    fun `switching on restores dismissed icons, an unchanged switch restores nothing`() = runBlocking {
        val a = account("Alpha", 42.0)
        val b = account("Beta", 87.0)
        turnOn(a, b)
        dismiss("Alpha")

        store.setStatusIconsEnabled(true)
        publication.presentationChanged()
        assertEquals("setting the same value restores nothing", setOf("Beta"), showing())
        store.setPinnedAccounts(listOf(a.localId, b.localId))
        publication.presentationChanged()
        assertEquals("setting the same pins restores nothing", setOf("Beta"), showing())

        store.setStatusIconsEnabled(false)
        publication.presentationChanged()
        store.setStatusIconsEnabled(true)
        publication.presentationChanged()
        assertEquals("off then on posts every pinned account", setOf("Alpha", "Beta"), showing())
    }

    @Test
    fun `unpinning and pinning a dismissed account again posts it again`() = runBlocking {
        val a = account("Alpha", 42.0)
        val b = account("Beta", 87.0)
        turnOn(a, b)
        dismiss("Alpha")

        pinned(b)
        pinned(a, b)

        assertEquals(setOf("Alpha", "Beta"), showing())
    }

    @Test
    fun `after every record is lost, as after a reboot, only the next sync brings the icons back`() = runBlocking {
        val a = account("Alpha", 42.0)
        val b = account("Beta", 87.0)
        turnOn(a, b)

        manager.cancelAll()
        publication.presentationChanged()
        assertEquals("a repaint cannot tell a reboot from a dismissal", 0, statusRecords().size)

        publication.cacheMoved()
        assertEquals(setOf("Alpha", "Beta"), showing())
        assertEquals(4, statusRecords().size)
    }

    @Test
    fun `a publisher started after a reboot does not take the pins for new ones`() = runBlocking {
        val a = account("Alpha", 42.0)
        val b = account("Beta", 87.0)
        turnOn(a, b)
        manager.cancelAll()

        // A fresh publisher, as the restarted process builds it, over the same stored state.
        val restarted = StatusIconPublisher(app, store, app.container.repository::accountUsageOnce)
        restarted.publish(restoreDismissed = false)
        assertEquals("what was shown before the reboot is remembered", 0, statusRecords().size)

        restarted.publish(restoreDismissed = true)
        assertEquals(setOf("Alpha", "Beta"), showing())
    }

    @Test
    fun `a new reading, the display mode and a stale boundary update the icons in place without an alert`() = runBlocking {
        val a = account("Alpha", 42.0)
        val b = account("Beta", 87.0)
        turnOn(a, b)
        val identities = statusRecords().map { it.tag to it.id }.toSet()
        fun alpha() = statusRecords().filter { it.accountName() == "Alpha" }

        reading(a, 41.0)
        publication.cacheMoved()
        assertTrue(alpha().all { it.title().startsWith("41% left") })

        store.setPercentDisplay(PercentDisplay.USED)
        publication.presentationChanged()
        assertTrue(alpha().all { it.title().startsWith("59% used") })

        // The reading ages past the stale threshold without a sync; the boundary repaint underlines it.
        val before = alpha().map { it.notification.smallIcon.loadDrawable(app) }.first()
        reading(a, 41.0, fetchedAt = now - 24L * 60 * 60 * 1000)
        publication.presentationChanged()
        val after = alpha().map { it.notification.smallIcon.loadDrawable(app) }.first()
        assertTrue("the stale icon is redrawn", !sameBitmap(before, after))

        assertEquals("updated in place", identities, statusRecords().map { it.tag to it.id }.toSet())
        assertEquals("no alert from any of it", 0, alertRecords().size)
    }

    private fun sameBitmap(a: android.graphics.drawable.Drawable?, b: android.graphics.drawable.Drawable?): Boolean =
        (a as android.graphics.drawable.BitmapDrawable).bitmap.sameAs((b as android.graphics.drawable.BitmapDrawable).bitmap)

    /**
     * What Robolectric can prove about order: Android ranks separate groups of one importance by
     * ranking time, newest first, and a `when` that is not in the future is that time. The first
     * account must therefore carry the latest clock reading, and a reorder must reverse the times with
     * the next publication. Whether the device draws them in that order is ticket 04's evidence.
     */
    @Test
    fun `the icons are posted so that the account order leads, and a reorder is followed`() = runBlocking {
        val a = account("Alpha", 42.0)
        val b = account("Beta", 87.0)
        val c = account("Gamma", 5.0)
        store.setPinnedAccounts(listOf(a.localId, b.localId, c.localId))
        store.setStatusIconsEnabled(true)
        var clock = now
        val publisher = StatusIconPublisher(app, store, app.container.repository::accountUsageOnce,
            now = { clock }, nextTick = { clock++ }, monotonic = { clock })
        publisher.publish(restoreDismissed = false)

        fun times(): Map<String, Long> = statusRecords().groupBy { it.accountName() }.mapValues { (_, records) ->
            assertEquals("summary and child share their account's time", 1, records.map { it.notification.`when` }.toSet().size)
            records.first().notification.`when`
        }
        val first = times()
        assertEquals("each ranking stamp is the controlled clock reading before its next tick",
            mapOf("Gamma" to now, "Beta" to now + 1, "Alpha" to now + 2), first)
        assertTrue("Alpha leads, then Beta, then Gamma: $first", first.getValue("Alpha") > first.getValue("Beta") && first.getValue("Beta") > first.getValue("Gamma"))

        app.container.repository.reorderAccounts(listOf(c.localId, a.localId, b.localId))
        publisher.publish(restoreDismissed = false)

        val second = times()
        assertEquals("the reordered groups use the next actual controlled readings",
            mapOf("Beta" to now + 3, "Alpha" to now + 4, "Gamma" to now + 5), second)
        assertTrue("Gamma leads after the reorder, then Alpha, then Beta: $second",
            second.getValue("Gamma") > second.getValue("Alpha") && second.getValue("Alpha") > second.getValue("Beta"))
    }

    /**
     * Android 16 with `sortSectionByTime` ranks by an account's `when` only if the platform takes
     * it as set by the app, that is, if it is not the `creationTime` the platform constructor
     * stamps from the clock when the notification is built. On a device the publisher's clock and
     * the constructor's are the same `System.currentTimeMillis()`, so the publisher must not build
     * an account's notifications until that clock has moved past the account's `when`.
     *
     * Robolectric cannot show this on the platform's own field: inside the framework classes its
     * clock is a separate fake that starts at 100, while app code reads the host's clock. So the
     * test gives the publisher a clock it records, and checks the order of events on that clock:
     * each account's `when` is followed by a strictly later reading before the next account's
     * notifications go out. The two notifications of one account are built and posted at once.
     */
    @Test
    fun `each account's time has passed on the clock before its notifications are built`() = runBlocking {
        val a = account("Alpha", 42.0)
        val b = account("Beta", 87.0)
        val c = account("Gamma", 5.0)
        store.setPinnedAccounts(listOf(a.localId, b.localId, c.localId))
        store.setStatusIconsEnabled(true)

        // Each controlled reading carries the record count from before the synchronous post.
        var time = now
        val readings = mutableListOf<Pair<Long, Int>>()
        val clock = { time.also { readings += it to statusRecords().size } }
        val publisher = StatusIconPublisher(app, store, app.container.repository::accountUsageOnce, now = clock,
            nextTick = { time++ }, monotonic = { time }, shown = object : ShownSelection {
                override fun read() = emptyList<String>()
                override fun write(accountIds: List<String>) = Unit
            })
        publisher.publish(restoreDismissed = true)

        // Accounts are posted Gamma, Beta, Alpha; each adds two records.
        val order = listOf("Gamma", "Beta", "Alpha")
        val whenOf = statusRecords().groupBy { it.accountName() }.mapValues { (_, r) -> r.first().notification.`when` }
        for ((index, name) in order.withIndex()) {
            val time = whenOf.getValue(name)
            val before = index * 2
            val readingsBeforeBuild = readings.filter { (_, posted) -> posted == before }.map { it.first }
            assertTrue("$name's when $time is a reading taken before its notifications were built: $readings", time in readingsBeforeBuild)
            assertTrue("$name: the clock read later than its when, still before its build: $readings",
                readingsBeforeBuild.any { it > time })
        }
    }

    @Test
    fun `removing an account releases its pin and removes its icon`() = runBlocking {
        val a = account("Alpha", 42.0)
        val b = account("Beta", 87.0)
        turnOn(a, b)

        app.container.repository.deleteAccount(a.localId)
        publication.presentationChanged()

        assertEquals(setOf("Beta"), showing())
    }
}
