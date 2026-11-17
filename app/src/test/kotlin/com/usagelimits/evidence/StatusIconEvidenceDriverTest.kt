package com.usagelimits.evidence

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.sync.NotificationPublisher
import com.usagelimits.core.sync.resetTestWorkManager
import com.usagelimits.statusicon.StatusIconPublisher
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusIconEvidenceDriverTest {
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val driver by lazy { StatusIconEvidenceDriver(app, prepareEmptyInstall = {}) }
    private var sequence = 0

    @Before
    fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun drain() {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
    }

    private suspend fun command(name: String, account: String? = null): JSONObject {
        val token = "driver-test-${sequence++}"
        driver.execute(name, token, account)
        return JSONObject(File(app.filesDir, "status-icon-evidence.json").readText()).also {
            assertEquals(token, it.getString("token"))
            assertEquals(name, it.getString("command"))
        }
    }

    private fun statusRecords() = manager.activeNotifications.filter {
        it.notification.channelId == StatusIconPublisher.CHANNEL_ID
    }

    @Test
    fun `recipes change only while off and real publication uses quiet default and child promotion controls`() = runBlocking {
        assertTrue(command("seed").getBoolean("success"))
        val selected = command("recipe", "default")
        assertTrue(selected.optString("error"), selected.getBoolean("success"))
        assertEquals("default", selected.getString("recipe"))
        assertEquals("prototype_status_default", selected.getString("channel"))
        assertTrue(selected.getBoolean("grouped"))
        assertEquals(0, manager.activeNotifications.size)
        for (alias in listOf("evidence-claude", "evidence-codex", "evidence-gemini")) {
            assertTrue(command("pin", alias).getBoolean("success"))
        }
        assertTrue(command("enable").getBoolean("success"))
        val defaults = manager.activeNotifications.toList()
        assertEquals(6, defaults.size)
        assertTrue(defaults.all { it.notification.channelId == "prototype_status_default" })
        assertEquals(setOf(2001, 2002), defaults.map { it.id }.toSet())
        val channel = manager.getNotificationChannel("prototype_status_default")
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        org.junit.Assert.assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.shouldShowLights())
        assertFalse(channel.canShowBadge())
        assertFalse(command("recipe", "promoted").getBoolean("success"))
        assertEquals("default", command("presentation").getString("recipe"))

        assertTrue(command("disable").getBoolean("success"))
        assertEquals(0, manager.activeNotifications.size)
        assertTrue(command("recipe", "promoted").getBoolean("success"))
        assertEquals(0, manager.activeNotifications.size)
        val promoted = command("enable")
        assertTrue(promoted.getBoolean("success"))
        assertTrue(promoted.has("promotionAllowed"))
        val paired = manager.activeNotifications.toList()
        assertEquals(6, paired.size)
        assertTrue(paired.all { it.notification.channelId == "prototype_status_promoted" })
        for (record in paired) {
            val summary = record.id == 2001
            assertEquals(!summary, record.notification.extras.getBoolean("android.requestPromotedOngoing"))
            if (!summary) {
                assertEquals("android.app.Notification\$ProgressStyle", record.notification.extras.getString(Notification.EXTRA_TEMPLATE))
                val figure = when (record.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()) {
                    "Claude (synthetic)" -> "42%"
                    "OpenAI Codex (synthetic)" -> "5%"
                    else -> "100%"
                }
                assertEquals(figure, record.notification.extras.getCharSequence("android.shortCriticalText").toString())
            }
        }

        assertTrue(command("disable").getBoolean("success"))
        assertEquals(0, manager.activeNotifications.size)
        val ungrouped = command("recipe", "promoted-ungrouped")
        assertTrue(ungrouped.getBoolean("success"))
        assertFalse(ungrouped.getBoolean("grouped"))
        assertTrue(command("enable").getBoolean("success"))
        val singles = manager.activeNotifications.toList()
        assertEquals(3, singles.size)
        assertTrue(singles.all { it.id == 2002 && it.notification.group == null })
        assertTrue(singles.all { it.notification.extras.getBoolean("android.requestPromotedOngoing") })
        assertTrue(command("disable").getBoolean("success"))
        assertEquals(0, manager.activeNotifications.size)
        assertFalse(command("recipe", "unknown").getBoolean("success"))
        for ((recipe, count) in listOf("promoted16-colorized" to 6, "promoted16-colorized-ungrouped" to 3)) {
            assertTrue(command("recipe", recipe).getBoolean("success"))
            assertTrue(command("enable").getBoolean("success"))
            val legacy = manager.activeNotifications.toList()
            assertEquals(count, legacy.size)
            assertTrue(legacy.all { it.notification.channelId == "prototype_status_promoted16_colorized" })
            assertTrue(legacy.filter { it.id == 2002 }.all { it.notification.extras.getBoolean(Notification.EXTRA_COLORIZED) })
            assertTrue(legacy.filter { it.id == 2001 }.none { it.notification.extras.getBoolean("android.requestPromotedOngoing") })
            assertTrue(command("disable").getBoolean("success"))
            assertEquals(0, manager.activeNotifications.size)
        }
        assertTrue(command("seed").getBoolean("success"))
        assertEquals("low", command("presentation").getString("recipe"))
    }

    @Test
    fun `driver commands seed real accounts publish six records update readings and report rejection`() = runBlocking {
        val seeded = command("seed")
        assertTrue(seeded.getBoolean("success"))
        assertFalse(seeded.getBoolean("enabled"))
        assertEquals(0, seeded.getJSONArray("pins").length())
        assertEquals(0, statusRecords().size)
        assertEquals(listOf("evidence-claude", "evidence-codex", "evidence-gemini", "evidence-xai", "evidence-copilot", "evidence-cursor"),
            (0 until seeded.getJSONArray("accounts").length()).map { seeded.getJSONArray("accounts").getString(it) })
        assertEquals(listOf(ProviderId.CLAUDE, ProviderId.CODEX, ProviderId.ANTIGRAVITY, ProviderId.XAI, ProviderId.KIMI, ProviderId.DEVIN),
            app.container.repository.accounts().map { it.provider })
        val localIds = seeded.getJSONObject("localIds")
        assertEquals(app.container.repository.accounts().first().localId, localIds.getString("evidence-claude"))
        assertTrue(command("enable").getBoolean("success"))
        for (alias in listOf("evidence-gemini", "evidence-claude", "evidence-codex")) {
            assertTrue(command("pin", alias).getBoolean("success"))
        }
        val records = statusRecords()
        assertEquals(6, records.size)
        assertEquals(3, records.groupBy { it.notification.group }.size)
        records.groupBy { it.notification.group }.values.forEach { group ->
            assertEquals(2, group.size)
            assertEquals(1, group.count { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 })
        }
        assertTrue(records.any { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("42% left") })

        assertTrue(command("update").getBoolean("success"))
        assertTrue(statusRecords().any { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("87% left") })
        assertEquals(13.0, app.container.repository.snapshot(localIds.getString("evidence-claude"))!!.windows.single().usedPercent!!, 0.0)
        assertEquals(0, manager.activeNotifications.count { it.notification.channelId == NotificationPublisher.CHANNEL_ID })

        val reordered = command("reorder")
        assertTrue(reordered.getBoolean("success"))
        assertEquals("evidence-gemini", reordered.getJSONArray("accounts").getString(0))
        assertTrue(command("states").getBoolean("success"))
        assertTrue(app.container.repository.snapshot(localIds.getString("evidence-claude"))!!.windows.single().exhausted)
        assertTrue(app.container.repository.snapshot(localIds.getString("evidence-codex"))!!.isStaleAt(System.currentTimeMillis()))
        assertEquals(com.usagelimits.core.model.SnapshotStatus.FAILED,
            app.container.repository.snapshot(localIds.getString("evidence-gemini"))!!.status)
        val readingsReset = command("reset-readings")
        assertTrue(readingsReset.getBoolean("success"))
        assertEquals("evidence-gemini", readingsReset.getJSONArray("accounts").getString(0))
        assertEquals(3, readingsReset.getJSONArray("pins").length())
        assertTrue(statusRecords().any { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("42% left") })
        assertTrue(command("presentation").getBoolean("success"))
        assertTrue(command("cache").getBoolean("success"))

        val full = command("pin", "evidence-xai")
        assertFalse(full.getBoolean("success"))
        assertTrue(full.getString("error").isNotBlank())
        assertEquals(6, statusRecords().size)
        assertFalse(command("pin", "not-an-account").getBoolean("success"))
        assertFalse(command("not-a-command").getBoolean("success"))
        driver.execute("cache", null)
        assertFalse(JSONObject(File(app.filesDir, "status-icon-evidence.json").readText()).getBoolean("success"))
        assertTrue(command("unpin", "evidence-codex").getBoolean("success"))
        assertEquals(4, statusRecords().size)

        val reset = command("seed")
        assertEquals(localIds.toString(), reset.getJSONObject("localIds").toString())
        assertFalse(reset.getBoolean("enabled"))
        assertEquals(0, statusRecords().size)
    }
}
