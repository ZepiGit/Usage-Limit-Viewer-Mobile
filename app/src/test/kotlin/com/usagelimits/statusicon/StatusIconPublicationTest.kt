package com.usagelimits.statusicon

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.graphics.drawable.BitmapDrawable
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.R
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
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.sync.NotificationPublisher
import com.usagelimits.core.sync.resetTestWorkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import kotlin.time.Duration.Companion.seconds

/**
 * The status icon as a person sees it, through the publication module callers use: which
 * accounts have an icon in the status bar, what it shows, that a sync updates it in place, that
 * tapping opens the account, and that switching off or choosing another account removes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36, 37])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    /** Nothing this class started may outlive it: see `resetTestWorkManager`. */
    @After
    fun drain() {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
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

    private suspend fun assertStatusRows(german: Boolean) {
        val personal = account(ProviderId.CLAUDE, "Personal", left = 42.0)
        val work = account(ProviderId.CLAUDE, "Work", left = 87.0)
        val other = account(ProviderId.CODEX, "Other", left = 5.0)
        app.container.settingsStore.setProviderIcon(ProviderId.CLAUDE.id, "claude-color")
        pin(personal, other)
        app.container.publication.presentationChanged()

        fun assertRows(expected: Map<String, Pair<String, String?>>, artwork: Int) {
            val records = statusRecords()
            assertEquals(expected.size * 2, records.size)
            for ((accountId, row) in expected) {
                val members = records.filter { shadowOf(it.notification.contentIntent).savedIntent.getStringExtra("accountId") == accountId }
                assertEquals("summary and child for $accountId", 2, members.size)
                assertEquals(1, members.count { it.isSummary() })
                for (record in members) {
                    val notification = record.notification
                    assertEquals("the reading or state is the title", row.first, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                    val details = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
                    assertTrue("the detail line starts with the window, not the figure", details.startsWith(if (german) "5‑Std.-Limit · Reset" else "5h limit · Resets"))
                    assertEquals("the header identifies the provider and names only shared-provider accounts", row.second, notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
                    assertNull("standard template, no custom content", notification.contentView)
                    assertNull("standard template, no custom expanded content", notification.bigContentView)
                    if (accountId != other.localId) {
                        val actual = notification.getLargeIcon()
                        assertEquals("provider artwork stays resource-backed", Icon.TYPE_RESOURCE, actual.type)
                        assertEquals("provider artwork comes from this app", app.packageName, actual.resPackage)
                        assertEquals("both records use the selected provider artwork", artwork, actual.resId)
                    }
                }
            }
        }
        val firstTitle = if (german) "42 % übrig" else "42% left"
        val secondTitle = if (german) "87 % übrig" else "87% left"
        val otherTitle = if (german) "5 % übrig" else "5% left"
        val ordinaryRows = mapOf(personal.localId to (firstTitle to "Claude"), other.localId to (otherTitle to "OpenAI Codex"))
        assertRows(ordinaryRows, R.drawable.icon_claude_color)
        val identities = statusRecords().map { it.tag to it.id }.toSet()

        pin(personal, work, other)
        app.container.publication.presentationChanged()
        assertRows(ordinaryRows + mapOf(personal.localId to (firstTitle to "Claude · Personal"), work.localId to (secondTitle to "Claude · Work")), R.drawable.icon_claude_color)
        assertTrue("existing groups keep their identities", statusRecords().map { it.tag to it.id }.toSet().containsAll(identities))

        pin(personal, other)
        app.container.settingsStore.setProviderIcon(ProviderId.CLAUDE.id, "claudecode-color")
        app.container.publication.presentationChanged()
        assertRows(ordinaryRows, R.drawable.icon_claudecode_color)
        assertEquals(identities, statusRecords().map { it.tag to it.id }.toSet())
    }

    @Test
    @Config(sdk = [30, 36, 37])
    fun `standard rows lead with the reading and selected artwork, naming only shared providers`() = runBlocking {
        assertStatusRows(german = false)
    }

    @Test
    @Config(sdk = [30, 36, 37], qualifiers = "de")
    fun `German standard rows lead with the reading and identify shared providers`() = runBlocking {
        assertStatusRows(german = true)
    }

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
            assertEquals("42% left", record.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue(record.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("5h limit · Resets "))
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
        assertTrue(updated.all { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().startsWith("41% left") })
        assertEquals("a figure changing is not an alert", 0, alertRecords().size)
    }

    @Test
    @Config(sdk = [30, 36, 37])
    fun `default importance status groups stay quiet and update in place without app alerts`() = runBlocking {
        val codex = account(ProviderId.CODEX, "Codex", left = 42.0)
        pin(codex)

        fun assertQuietPublication(figure: Int): Set<Pair<String?, Int>> {
            val channel = manager.getNotificationChannel(StatusIconPublisher.CHANNEL_ID)
            assertEquals("status icons use the normal importance section", NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
            assertNull("the channel has no sound", channel.sound)
            assertFalse("the channel does not vibrate", channel.shouldVibrate())
            assertFalse("the channel does not light up", channel.shouldShowLights())
            assertFalse("the channel has no badge", channel.canShowBadge())

            val records = statusRecords()
            assertEquals("one summary and one child", 2, records.size)
            assertEquals(1, records.count { it.isSummary() })
            assertEquals("both records belong to the account group", 1, records.map { it.notification.group }.toSet().size)
            assertEquals("no additional app notification records", 2, manager.activeNotifications.size)
            assertEquals("a status publication does not add an app alert", 0, alertRecords().size)
            for (record in records) {
                val notification = record.notification
                val flags = notification.flags
                assertTrue("both records stay ongoing", flags and Notification.FLAG_ONGOING_EVENT != 0)
                assertTrue("both records only alert once", flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                assertTrue("both records stay local", flags and Notification.FLAG_LOCAL_ONLY != 0)
                assertEquals(Notification.PRIORITY_DEFAULT, notification.priority)
                assertEquals("both records retain normal lock-screen visibility", Notification.VISIBILITY_PRIVATE, notification.visibility)
                assertEquals("neither record requests silent group suppression", Notification.GROUP_ALERT_ALL, notification.groupAlertBehavior)
                assertNull("neither record requests sound", notification.sound)
                assertNull("neither record requests vibration", notification.vibrate)
                assertEquals("neither record requests default alerts", 0,
                    notification.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE or Notification.DEFAULT_LIGHTS))
                assertEquals("neither record requests lights", 0, flags and Notification.FLAG_SHOW_LIGHTS)
                assertTrue("both records show the current figure",
                    expectedIcon(ProviderId.CODEX, StatusIconContent.Figure(figure)).sameAs(record.iconPixels()))
                assertTrue("both rows describe the current reading",
                    notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().startsWith("$figure% left"))
            }
            return records.map { it.tag to it.id }.toSet()
        }

        app.container.publication.presentationChanged()
        val identities = assertQuietPublication(42)

        reading(codex, left = 87.0)
        app.container.publication.cacheMoved()

        assertEquals("the update replaces both records at the same identities", identities, assertQuietPublication(87))
    }

    @Test
    fun `the status notifications are silent, ongoing, local and on their own quiet channel`() = runBlocking {
        pin(account(ProviderId.CODEX, "Codex", left = 87.0))

        app.container.publication.presentationChanged()

        val channel = manager.getNotificationChannel(StatusIconPublisher.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
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
        assertTrue(codexRecords.all { shadowOf(it.notification.contentIntent).savedIntent.getStringExtra("accountId") == codex.localId })

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
     * Holds an older publication inside its cache read, applies [change] to the settings, then
     * lets a newer publication run as far as the publisher allows before the older one resumes.
     *
     * Every suspending read of the publisher goes through its own seams: the settings are a value
     * this test holds, the accounts are read from the repository once, beforehand, and only the
     * older publication's account read waits on a gate. Both publications run on one test
     * scheduler, advanced until nothing more can run. A newer publication that is serialised
     * behind the older one therefore stays parked; one that is not reaches its end, cleanup
     * included, before the gate opens. That is the order in which an older publication used to
     * post the old choice over the newer cleanup, and the final records tell the two apart. No
     * sleep and no dispatcher luck decides it, and the time is bounded.
     */
    private fun interleaved(change: (AppSettings) -> AppSettings) = runBlocking {
        var settings = app.container.settingsStore.settings.first()
        var clock = System.currentTimeMillis()
        val accounts = app.container.repository.accountUsageOnce()
        val olderInRead = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val publisher = StatusIconPublisher(
            app, app.container.settingsStore,
            readAccounts = {
                if (!olderInRead.isCompleted) {
                    olderInRead.complete(Unit)
                    release.await()
                }
                accounts
            },
            readSettings = { settings },
            // Time on the test scheduler: the clock moves when the publisher waits for it.
            now = { clock },
            nextTick = { delay(1); clock += 1 },
            shown = object : ShownSelection {
                private var ids = emptyList<String>()
                override fun read() = ids
                override fun write(accountIds: List<String>) { ids = accountIds }
            },
        )

        runTest(timeout = 30.seconds) {
            val older = launch { publisher.publish(restoreDismissed = true) }
            advanceUntilIdle()
            assertTrue("the older publication is held inside its account read", olderInRead.isCompleted && !older.isCompleted)

            settings = change(settings)
            val newer = launch { publisher.publish(restoreDismissed = true) }
            advanceUntilIdle()
            release.complete(Unit)
            advanceUntilIdle()

            assertTrue("both publications ran to their end", older.isCompleted && newer.isCompleted)
        }
    }

    @Test
    fun `an older publication that resumes after Off does not bring the icon back`() {
        pinBlocking(ProviderId.CLAUDE, "Claude", 42.0)

        interleaved { it.copy(statusIconsEnabled = false) }

        assertEquals("the switch says Off, so no icon may remain", 0, statusRecords().size)
    }

    @Test
    fun `an older publication that resumes after another choice does not restore the previous account`() {
        pinBlocking(ProviderId.CLAUDE, "Claude", 42.0)
        val codex = runBlocking { account(ProviderId.CODEX, "Codex", left = 87.0) }

        interleaved { it.copy(pinnedAccountIds = listOf(codex.localId)) }

        val accountIds = statusRecords().map { shadowOf(it.notification.contentIntent).savedIntent.getStringExtra("accountId") }
        assertEquals("only the latest choice shows", listOf(codex.localId, codex.localId), accountIds)
    }

    private fun pinBlocking(provider: ProviderId, name: String, left: Double) = runBlocking {
        pin(account(provider, name, left))
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
        assertTrue(records.all { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "Reconnect required" })
    }

    @Test
    @Config(qualifiers = "de")
    fun `the shade row speaks German in German`() = runBlocking {
        pin(account(ProviderId.CLAUDE, "Claude", left = 42.0))

        app.container.publication.cacheMoved()

        assertTrue(statusRecords().all { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "42 % übrig" })
        assertEquals("Statusleisten-Symbole", manager.getNotificationChannel(StatusIconPublisher.CHANNEL_ID).name.toString())
    }
}
