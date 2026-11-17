package com.usagelimits.statusicon

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsActivity
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.StatusIcon
import com.usagelimits.core.model.StatusIconContent
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.sync.NotificationPublisher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * The status icon as a person sees it, through the publication module callers use: which
 * accounts have an icon in the status bar, what it shows, that a sync updates it in place, that
 * tapping opens the account, and that switching off or choosing another account removes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusIconPublicationTest {

    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        // Robolectric keeps one DataStore per sandbox, so start every test from the default.
        app.container.settingsStore.setStatusIconsEnabled(false)
        app.container.settingsStore.setPinnedAccounts(emptyList())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun drain() {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
    }

    private suspend fun account(provider: ProviderId, name: String, left: Double): ProviderAccount {
        val account = app.container.repository.upsertFromLogin(provider, "$name-${System.nanoTime()}", null, name, null, emptyMap())
        reading(account, left)
        return account
    }

    private suspend fun reading(account: ProviderAccount, left: Double) {
        app.container.repository.saveSnapshot(UsageSnapshot(account.localId, System.currentTimeMillis(), SnapshotStatus.OK,
            windows = listOf(UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, usedPercent = 100.0 - left,
                periodSeconds = 18_000, resetAt = now + 3_600_000, exhausted = false))))
    }

    private suspend fun pin(vararg accounts: ProviderAccount) {
        app.container.settingsStore.setStatusIconsEnabled(true)
        app.container.settingsStore.setPinnedAccounts(accounts.map { it.localId })
    }

    private fun statusRecords(): List<StatusBarNotification> =
        manager.activeNotifications.filter { it.notification.channelId == StatusIconPublisher.CHANNEL_ID }

    private fun alertRecords(): List<StatusBarNotification> =
        manager.activeNotifications.filter { it.notification.channelId == NotificationPublisher.CHANNEL_ID }

    private fun StatusBarNotification.isSummary() = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    private fun StatusBarNotification.iconPixels(): Bitmap =
        (notification.smallIcon.loadDrawable(app) as BitmapDrawable).bitmap

    private fun expectedIcon(provider: ProviderId, content: StatusIconContent): Bitmap =
        StatusIconRenderer.render(provider, StatusIcon(content, outdated = false), app.resources.displayMetrics.density)

    @Test
    fun `a pinned account shows its figure in its own group, and a sync updates it in place`() = runBlocking {
        val claude = account(ProviderId.CLAUDE, "Claude", left = 42.0)
        pin(claude)

        app.container.publication.cacheMoved()

        val records = statusRecords()
        assertEquals("one summary and one child", 2, records.size)
        assertEquals(1, records.count { it.isSummary() })
        assertEquals("both in one group of their own", 1, records.map { it.notification.group }.toSet().size)
        for (record in records) {
            assertTrue("the small icon is the 3:2 bitmap glyph 42", expectedIcon(ProviderId.CLAUDE, StatusIconContent.Figure(42)).sameAs(record.iconPixels()))
            assertEquals("Claude", record.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue(record.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("42% left · 5h limit · "))
            val opened = shadowOf(record.notification.contentIntent).savedIntent
            assertEquals(UsageLimitsActivity::class.java.name, opened.component?.className)
            assertEquals("tapping opens this account", claude.localId, opened.getStringExtra("accountId"))
        }
        val identities = records.map { it.tag to it.id }.toSet()

        reading(claude, left = 41.0)
        app.container.publication.cacheMoved()

        val updated = statusRecords()
        assertEquals("updated in place, not added", identities, updated.map { it.tag to it.id }.toSet())
        assertTrue(updated.all { expectedIcon(ProviderId.CLAUDE, StatusIconContent.Figure(41)).sameAs(it.iconPixels()) })
        assertTrue(updated.all { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("41% left") })
        assertEquals("a figure changing is not an alert", 0, alertRecords().size)
    }

    @Test
    fun `the status notifications are silent, ongoing, local and on their own quiet channel`() = runBlocking {
        pin(account(ProviderId.CODEX, "Codex", left = 87.0))

        app.container.publication.presentationChanged()

        val channel = manager.getNotificationChannel(StatusIconPublisher.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
        val records = statusRecords()
        assertEquals(2, records.size)
        for (record in records) {
            val flags = record.notification.flags
            assertTrue(flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertTrue(flags and Notification.FLAG_LOCAL_ONLY != 0)
            assertTrue("the fallback glyph for a provider without its own", expectedIcon(ProviderId.CODEX, StatusIconContent.Figure(87)).sameAs(record.iconPixels()))
        }
    }

    @Test
    fun `choosing another account, clearing the choice or switching off removes the previous icon at once`() = runBlocking {
        val claude = account(ProviderId.CLAUDE, "Claude", left = 42.0)
        val codex = account(ProviderId.CODEX, "Codex", left = 87.0)
        pin(claude)
        app.container.publication.presentationChanged()
        val claudeGroup = statusRecords().map { it.notification.group }.toSet()
        assertEquals(1, claudeGroup.size)

        pin(codex)
        app.container.publication.presentationChanged()
        val codexRecords = statusRecords()
        assertEquals(2, codexRecords.size)
        assertFalse("the previous account's group is gone", codexRecords.any { it.notification.group in claudeGroup })
        assertTrue(codexRecords.all { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "Codex" })

        app.container.settingsStore.setPinnedAccounts(emptyList())
        app.container.publication.presentationChanged()
        assertEquals(0, statusRecords().size)

        pin(codex)
        app.container.publication.presentationChanged()
        assertEquals(2, statusRecords().size)
        app.container.settingsStore.setStatusIconsEnabled(false)
        app.container.publication.presentationChanged()
        assertEquals(0, statusRecords().size)
    }

    /**
     * A publisher whose first cache read waits for [gate], so a test can hold an older
     * publication between its read and its posts while a newer choice is published.
     */
    private fun pausingPublisher(entered: CompletableDeferred<Unit>, gate: CompletableDeferred<Unit>): StatusIconPublisher {
        var first = true
        return StatusIconPublisher(app, app.container.settingsStore, readAccounts = {
            val accounts = app.container.repository.accountUsageOnce()
            if (first) {
                first = false
                entered.complete(Unit)
                gate.await()
            }
            accounts
        })
    }

    @Test
    fun `an older publication that resumes after Off does not bring the icon back`() = runBlocking {
        val claude = account(ProviderId.CLAUDE, "Claude", left = 42.0)
        pin(claude)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val publisher = pausingPublisher(entered, gate)

        val older = launch(Dispatchers.Default) { publisher.publish() }
        entered.await()
        app.container.settingsStore.setStatusIconsEnabled(false)
        val newer = launch(Dispatchers.Default) { publisher.publish() }
        gate.complete(Unit)
        joinAll(older, newer)

        assertEquals("the switch says Off, so no icon may remain", 0, statusRecords().size)
    }

    @Test
    fun `an older publication that resumes after another choice does not restore the previous account`() = runBlocking {
        val claude = account(ProviderId.CLAUDE, "Claude", left = 42.0)
        val codex = account(ProviderId.CODEX, "Codex", left = 87.0)
        pin(claude)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val publisher = pausingPublisher(entered, gate)

        val older = launch(Dispatchers.Default) { publisher.publish() }
        entered.await()
        pin(codex)
        val newer = launch(Dispatchers.Default) { publisher.publish() }
        gate.complete(Unit)
        joinAll(older, newer)

        val titles = statusRecords().map { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() }
        assertEquals("only the latest choice shows", listOf("Codex", "Codex"), titles)
    }

    @Test
    fun `without notification permission nothing is posted`() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        pin(account(ProviderId.CLAUDE, "Claude", left = 42.0))

        app.container.publication.cacheMoved()

        assertEquals(0, statusRecords().size)
    }

    @Test
    fun `an account that needs signing in again shows the error sign instead of its old number`() = runBlocking {
        val claude = account(ProviderId.CLAUDE, "Claude", left = 42.0)
        app.container.repository.saveSnapshot(UsageSnapshot(claude.localId, now, SnapshotStatus.FAILED,
            windows = listOf(UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, usedPercent = 58.0,
                periodSeconds = 18_000, resetAt = now + 3_600_000, exhausted = false)),
            errorMessage = "sign-in expired", connectionStatus = com.usagelimits.core.model.ConnectionStatus.RECONNECT_REQUIRED))
        pin(claude)

        app.container.publication.cacheMoved()

        val records = statusRecords()
        assertEquals(2, records.size)
        assertTrue(records.all { expectedIcon(ProviderId.CLAUDE, StatusIconContent.Error).sameAs(it.iconPixels()) })
        assertTrue(records.all { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString() == "Reconnect required" })
    }

    @Test
    @Config(qualifiers = "de")
    fun `the shade row speaks German in German`() = runBlocking {
        pin(account(ProviderId.CLAUDE, "Claude", left = 42.0))

        app.container.publication.cacheMoved()

        assertTrue(statusRecords().all { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("42 % übrig · ") })
        assertEquals("Statusleisten-Symbole", manager.getNotificationChannel(StatusIconPublisher.CHANNEL_ID).name.toString())
    }
}
