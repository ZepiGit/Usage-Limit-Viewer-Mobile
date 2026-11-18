package com.usagelimits.statusicon

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import androidx.core.graphics.ColorUtils
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
import com.usagelimits.ui.IconStyle
import com.usagelimits.ui.ProviderIconCatalog
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

    private companion object {
        const val ARTWORK_SIZE = 64
    }

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

    @Test
    @Config(sdk = [37], qualifiers = "xxhdpi")
    fun `published white artwork fills the 48 dp slot at xxhdpi`() = runBlocking {
        pin(account(ProviderId.CODEX, "Codex", left = 42.0))
        app.container.settingsStore.setProviderIcon(ProviderId.CODEX.id, "openai-text")
        app.container.publication.presentationChanged()

        val records = statusRecords()
        assertEquals(2, records.size)
        for (record in records) {
            val icon = record.notification.getLargeIcon()
            assertEquals(Icon.TYPE_BITMAP, icon.type)
            val artwork = icon.loadDrawable(app) as BitmapDrawable
            assertEquals("48 dp at xxhdpi", 144, artwork.bitmap.width)
        }
    }

    @Test
    @Config(sdk = [37], qualifiers = "mdpi")
    fun `published provider artwork contrasts on both shade surfaces`() = runBlocking {
        val failures = mutableListOf<String>()
        val surfaces = mapOf("light" to 0xFFE8E7EF.toInt(), "dark" to 0xFF202024.toInt())
        for (provider in ProviderId.entries) {
            pin(account(provider, provider.displayName, left = 42.0))
            for (choice in ProviderIconCatalog.choices(provider)) {
                app.container.settingsStore.setProviderIcon(provider.id, choice.id)
                app.container.publication.presentationChanged()
                val records = statusRecords()
                assertEquals("summary and child for ${choice.id}", 2, records.size)
                assertEquals("summary for ${choice.id}", 1, records.count { it.isSummary() })
                val backedResource = choice.backedShadeArtwork
                val needsGround = choice.whiteInk || backedResource != null
                val source = requireNotNull(app.getDrawable(choice.drawable))
                // Rasterize the source alone at the 48dp bitmap slot before displaying it at 64px.
                val sourcePixels = if (choice.whiteInk) {
                    val span = if (choice.style == IconStyle.WORDMARK) 72 else 36
                    val reference = drawArtwork(source, Color.TRANSPARENT, 48, span)
                    artworkPixels(BitmapDrawable(app.resources, reference), Color.TRANSPARENT)
                } else {
                    artworkPixels(source, Color.TRANSPARENT, if (needsGround) 48 else ARTWORK_SIZE)
                }
                if (choice.style == IconStyle.WORDMARK) {
                    assertTrue("${choice.id} ink stays inside the ground after removing transparent padding",
                        sourcePixels.indices.none { index ->
                            val x = index % ARTWORK_SIZE
                            val y = index / ARTWORK_SIZE
                            (x !in 7..56 || y !in 7..56) && Color.alpha(sourcePixels[index]) >= 8
                        })
                }
                // Include the cores of thin lettering without treating faint antialiasing as ink.
                val minAlpha = if (choice.whiteInk) 128 else 245
                val mark = sourcePixels.indices.filter { Color.alpha(sourcePixels[it]) >= minAlpha }
                if (mark.isEmpty()) failures += "${choice.id} has no core mark pixels at shade size"
                for (record in records) {
                    val role = if (record.isSummary()) "summary" else "child"
                    val description = "${provider.id}/${choice.id} $role"
                    val icon = record.notification.getLargeIcon()
                    if (choice.whiteInk) {
                        assertEquals(description, Icon.TYPE_BITMAP, icon.type)
                    } else {
                        assertResourceArtwork(icon, backedResource ?: choice.drawable)
                    }
                    val artwork = requireNotNull(icon.loadDrawable(app))
                    for ((surfaceName, surface) in surfaces) {
                        val pixels = artworkPixels(artwork, surface)
                        // Sample the backing outside the inset, not a source-mark pixel or the shade.
                        val ground = if (needsGround) pixels[4 * ARTWORK_SIZE + ARTWORK_SIZE / 2] else surface
                        if (needsGround && ground != 0xFF1A1918.toInt()) {
                            failures += "$description $surfaceName: missing dark ground, got #${Integer.toHexString(ground)}"
                        }
                        val visible = mark.count { ColorUtils.calculateContrast(pixels[it], ground) >= 3.0 }
                        if (visible * 10 < mark.size * 9) {
                            failures += "$description $surfaceName: $visible/${mark.size} core mark pixels reach 3:1 against #${Integer.toHexString(ground)}"
                        }
                    }
                }
            }
        }
        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
    }

    @Test
    @Config(sdk = [37])
    fun `near-white provider PNGs are classified as white ink`() {
        for (provider in ProviderId.entries) {
            for (choice in ProviderIconCatalog.choices(provider)) {
                val bitmap = requireNotNull(BitmapFactory.decodeResource(app.resources, choice.drawable))
                val row = IntArray(bitmap.width)
                var brightness = 0.0
                var count = 0
                for (y in 0 until bitmap.height) {
                    bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
                    for (pixel in row) {
                        if (Color.alpha(pixel) < 245) continue
                        brightness += 0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)
                        count++
                    }
                }
                assertTrue("${choice.id} has near-opaque source pixels", count > 0)
                assertEquals("${choice.id}: mean source brightness ${brightness / count}", brightness / count >= 230, choice.whiteInk)
            }
        }
    }

    private fun assertResourceArtwork(icon: Icon, resource: Int) {
        assertEquals("provider artwork stays resource-backed", Icon.TYPE_RESOURCE, icon.type)
        assertEquals("provider artwork comes from this app", app.packageName, icon.resPackage)
        assertEquals("both records use the selected provider artwork", resource, icon.resId)
    }

    private fun artworkPixels(artwork: Drawable, surface: Int, span: Int = ARTWORK_SIZE): IntArray {
        val size = ARTWORK_SIZE
        val bitmap = drawArtwork(artwork, surface, size, span)
        return IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
    }

    private fun drawArtwork(artwork: Drawable, surface: Int, size: Int, span: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(surface)
        val width = artwork.intrinsicWidth
        val height = artwork.intrinsicHeight
        val scale = span.toFloat() / maxOf(width, height)
        val canvas = Canvas(bitmap)
        canvas.translate((size - width * scale) / 2, (size - height * scale) / 2)
        canvas.scale(scale, scale)
        artwork.setBounds(0, 0, width, height)
        artwork.draw(canvas)
        return bitmap
    }

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
                    val largeIcon = notification.getLargeIcon()
                    if (accountId == other.localId) {
                        assertEquals("both Codex rows show backed OpenAI artwork", Icon.TYPE_BITMAP, largeIcon.type)
                    } else {
                        assertResourceArtwork(largeIcon, artwork)
                    }
                }
            }
        }
        val firstTitle = if (german) "42 % übrig" else "42% left"
        val secondTitle = if (german) "87 % übrig" else "87% left"
        val otherTitle = if (german) "5 % übrig" else "5% left"
        val ordinaryRows = mapOf(personal.localId to (firstTitle to "Claude"), other.localId to (otherTitle to "OpenAI Codex"))
        assertRows(ordinaryRows, R.drawable.status_icon_artwork_claude_color)
        val identities = statusRecords().map { it.tag to it.id }.toSet()

        pin(personal, work, other)
        app.container.publication.presentationChanged()
        assertRows(ordinaryRows + mapOf(personal.localId to (firstTitle to "Claude · Personal"), work.localId to (secondTitle to "Claude · Work")), R.drawable.status_icon_artwork_claude_color)
        assertTrue("existing groups keep their identities", statusRecords().map { it.tag to it.id }.toSet().containsAll(identities))

        pin(personal, other)
        app.container.settingsStore.setProviderIcon(ProviderId.CLAUDE.id, "claudecode-color")
        app.container.publication.presentationChanged()
        assertRows(ordinaryRows, R.drawable.status_icon_artwork_claudecode_color)
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
                val groupAlertRole = if (record.isSummary()) Notification.GROUP_ALERT_CHILDREN else Notification.GROUP_ALERT_SUMMARY
                assertEquals("the ${if (record.isSummary()) "summary" else "child"} requests suppression of interruption on a dozing screen",
                    groupAlertRole, notification.groupAlertBehavior)
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
