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
import kotlinx.coroutines.runBlocking
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
class StatusIconLifecycleTest {

    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val publication get() = app.container.publication
    private val store get() = app.container.settingsStore
    private val now = System.currentTimeMillis()

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

    private fun StatusBarNotification.title() = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun StatusBarNotification.text() = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun StatusBarNotification.isSummary() = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    /** The accounts that have an icon, by title, each counted once. */
    private fun showing(): Set<String> = statusRecords().map { it.title() }.toSet()

    /** Active-list scenario: removes an account's records the way a swipe on Android 14+ would. */
    private fun dismiss(name: String) {
        statusRecords().filter { it.title() == name }.forEach { manager.cancel(it.tag, it.id) }
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
            assertEquals("$group belongs to one account", 1, members.map { it.title() }.toSet().size)
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
        fun alpha() = statusRecords().filter { it.title() == "Alpha" }

        reading(a, 41.0)
        publication.cacheMoved()
        assertTrue(alpha().all { it.text().startsWith("41% left") })

        store.setPercentDisplay(PercentDisplay.USED)
        publication.presentationChanged()
        assertTrue(alpha().all { it.text().startsWith("59% used") })

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
     * account must therefore carry the latest real time, and a reorder must reverse the times with
     * the next publication. Whether the device draws them in that order is ticket 04's evidence.
     */
    @Test
    fun `the icons are posted so that the account order leads, and a reorder is followed`() = runBlocking {
        val a = account("Alpha", 42.0)
        val b = account("Beta", 87.0)
        val c = account("Gamma", 5.0)
        val before = System.currentTimeMillis()
        turnOn(a, b, c)
        val after = System.currentTimeMillis()

        fun times(): Map<String, Long> = statusRecords().groupBy { it.title() }.mapValues { (_, records) ->
            assertEquals("summary and child share their account's time", 1, records.map { it.notification.`when` }.toSet().size)
            records.first().notification.`when`
        }
        val first = times()
        assertTrue("real clock readings, nothing invented", first.values.all { it in before..after })
        assertTrue("Alpha leads, then Beta, then Gamma: $first", first.getValue("Alpha") > first.getValue("Beta") && first.getValue("Beta") > first.getValue("Gamma"))

        app.container.repository.reorderAccounts(listOf(c.localId, a.localId, b.localId))
        publication.presentationChanged()

        val second = times()
        assertTrue("Gamma leads after the reorder, then Alpha, then Beta: $second",
            second.getValue("Gamma") > second.getValue("Alpha") && second.getValue("Alpha") > second.getValue("Beta"))
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
