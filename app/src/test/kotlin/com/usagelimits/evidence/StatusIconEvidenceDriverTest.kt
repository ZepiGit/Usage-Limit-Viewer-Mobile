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
