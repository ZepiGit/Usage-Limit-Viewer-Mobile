package com.usagelimits.evidence

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.sync.NotificationPublisher
import com.usagelimits.core.sync.SyncWorker
import com.usagelimits.core.sync.resetTestWorkManager
import com.usagelimits.statusicon.StatusIconPublisher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.UUID
import java.util.concurrent.TimeUnit

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
        try {
            WorkManager.getInstance(app).cancelAllWork().result.get(10, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
        } finally {
            resetTestWorkManager()
        }
    }

    private suspend fun withStartupSync(
        intervalMinutes: Int,
        workName: String,
        runSync: suspend CoroutineWorker.() -> ListenableWorker.Result,
        test: suspend (WorkManager, UUID) -> Unit,
    ) {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                if (workerClassName != SyncWorker::class.java.name) return null
                return object : CoroutineWorker(appContext, workerParameters) {
                    override suspend fun doWork() = runSync()
                }
            }
        }
        val settings = app.container.settingsStore
        val priorInterval = settings.settings.first().syncIntervalMinutes
        resetTestWorkManager()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
        )
        try {
            settings.setSyncInterval(intervalMinutes)
            app.scheduleOnStartup()
            val work = WorkManager.getInstance(app)
            val request = work.getWorkInfosForUniqueWorkFlow(workName).first().single()
            assertEquals(WorkInfo.State.ENQUEUED, request.state)
            assertEquals(0, request.runAttemptCount)
            test(work, request.id)
        } finally {
            withContext(NonCancellable) { settings.setSyncInterval(priorInterval) }
        }
    }

    private fun startShortSync(id: UUID) {
        val testDriver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(app))
        testDriver.setAllConstraintsMet(id)
        testDriver.setInitialDelayMet(id)
    }

    private suspend fun command(name: String, account: String? = null, driver: StatusIconEvidenceDriver = this.driver): JSONObject {
        val token = "driver-test-${sequence++}"
        val started = SystemClock.elapsedRealtime()
        driver.execute(name, token, account)
        val returned = SystemClock.elapsedRealtime()
        return JSONObject(File(app.filesDir, "status-icon-evidence.json").readText()).also {
            assertEquals(token, it.getString("token"))
            assertEquals(name, it.getString("command"))
            assertTrue("completion uses the device's elapsed clock", it.getLong("completedElapsedRealtimeMs") in started..returned)
        }
    }

    private fun statusRecords() = manager.activeNotifications.filter {
        it.notification.channelId == StatusIconPublisher.CHANNEL_ID
    }

    @Test
    fun `seed cancels the delayed five minute refresh without a launch sync`() = runBlocking {
        val settings = app.container.settingsStore
        val priorInterval = settings.settings.first().syncIntervalMinutes
        try {
            settings.setSyncInterval(5)
            app.scheduleOnStartup()
            val work = WorkManager.getInstance(app)
            assertTrue(work.getWorkInfosForUniqueWorkFlow(SyncWorker.ONE_SHOT_WORK_NAME).first().isEmpty())
            val scheduled = work.getWorkInfosForUniqueWorkFlow(SyncWorker.SHORT_WORK_NAME).first().single()
            assertEquals(WorkInfo.State.ENQUEUED, scheduled.state)

            val seeded = command("seed", driver = StatusIconEvidenceDriver(app))

            assertTrue(seeded.optString("error"), seeded.getBoolean("success"))
            assertEquals(6, seeded.getJSONArray("accounts").length())
            assertEquals(WorkInfo.State.CANCELLED, work.getWorkInfoByIdFlow(scheduled.id).first()?.state)
        } finally {
            settings.setSyncInterval(priorInterval)
        }
    }

    @Test
    fun `seed waits for a claimed startup sync to finish`() = runBlocking {
        withTimeout(10_000) {
            val publishProgress = CompletableDeferred<Unit>()
            val finishSync = CompletableDeferred<Unit>()
            withStartupSync(5, SyncWorker.SHORT_WORK_NAME, runSync = {
                publishProgress.await()
                setProgress(workDataOf("waiting" to true))
                finishSync.await()
                ListenableWorker.Result.success()
            }) { work, id ->
                startShortSync(id)
                work.getWorkInfoByIdFlow(id).first { it?.state == WorkInfo.State.RUNNING }
                val sawProgress = CompletableDeferred<Unit>()
                val driver = StatusIconEvidenceDriver.observingStartup(app) { requests ->
                    if (requests.single { it.id == id }.progress.getBoolean("waiting", false)) {
                        sawProgress.complete(Unit)
                    } else {
                        publishProgress.complete(Unit)
                    }
                }
                val seed = async { command("seed", driver = driver) }
                // The second snapshot proves seed kept observing while the sync was still held.
                val waited = select {
                    sawProgress.onAwait { true }
                    seed.onAwait { false }
                }
                assertTrue("Seed returned before the claimed sync finished", waited)
                finishSync.complete(Unit)

                val completion = seed.await()
                assertTrue(completion.optString("error"), completion.getBoolean("success"))
                assertEquals(6, completion.getJSONArray("accounts").length())
                assertEquals(WorkInfo.State.SUCCEEDED, work.getWorkInfoByIdFlow(id).first()?.state)
            }
        }
    }

    @Test
    fun `seed rejects a startup sync claimed between observation and cancellation`() = runBlocking {
        withTimeout(10_000) {
            withStartupSync(5, SyncWorker.SHORT_WORK_NAME, runSync = { awaitCancellation() }) { work, id ->
                val driver = StatusIconEvidenceDriver.observingStartup(app) { requests ->
                    val pending = requests.single { it.id == id }
                    assertEquals(WorkInfo.State.ENQUEUED, pending.state)
                    assertEquals(0, pending.runAttemptCount)
                    // Claim after this snapshot was read, before seed can cancel the request.
                    startShortSync(id)
                    val running = work.getWorkInfoByIdFlow(id).first { it?.state == WorkInfo.State.RUNNING }
                    assertEquals(1, running?.runAttemptCount)
                }

                val completion = command("seed", driver = driver)

                val cancelled = work.getWorkInfoByIdFlow(id).first()
                assertEquals(WorkInfo.State.CANCELLED, cancelled?.state)
                assertEquals(1, cancelled?.runAttemptCount)
                assertFalse("Seed succeeded after cancelling an attempted sync", completion.getBoolean("success"))
                assertEquals("An attempted sync was cancelled before it could be verified idle; clear app data and retry",
                    completion.getString("error"))
                assertEquals(0, completion.getJSONArray("accounts").length())
                assertTrue(app.container.repository.accounts().isEmpty())
            }
        }
    }

    @Test
    fun `seed waits for a due first periodic sync to finish`() = runBlocking {
        withTimeout(10_000) {
            val finishSync = CompletableDeferred<Unit>()
            withStartupSync(30, SyncWorker.PERIODIC_WORK_NAME, runSync = {
                finishSync.await()
                ListenableWorker.Result.success()
            }) { work, id ->
                val sawRunning = CompletableDeferred<Unit>()
                var firstSnapshot = true
                val driver = StatusIconEvidenceDriver.observingStartup(app) { requests ->
                    val request = requests.single { it.id == id }
                    if (firstSnapshot) {
                        firstSnapshot = false
                        assertEquals(WorkInfo.State.ENQUEUED, request.state)
                        assertEquals(0, request.runAttemptCount)
                        assertTrue("The first periodic sync is already due", request.nextScheduleTimeMillis <= System.currentTimeMillis())
                        // WorkManager claims the sync without changing the snapshot seed already read.
                        checkNotNull(WorkManagerTestInitHelper.getTestDriver(app)).setAllConstraintsMet(id)
                        val running = work.getWorkInfoByIdFlow(id).first { it?.state == WorkInfo.State.RUNNING }
                        assertEquals(1, running?.runAttemptCount)
                    } else if (request.state == WorkInfo.State.RUNNING) {
                        sawRunning.complete(Unit)
                    }
                }

                val seed = async { command("seed", driver = driver) }
                val earlyCompletion = select<JSONObject?> {
                    seed.onAwait { it }
                    sawRunning.onAwait { null }
                }
                assertTrue("Seed returned before the first periodic sync finished: $earlyCompletion", earlyCompletion == null)
                finishSync.complete(Unit)

                val completion = seed.await()
                assertTrue(completion.optString("error"), completion.getBoolean("success"))
                assertEquals(6, completion.getJSONArray("accounts").length())
                val cancelled = work.getWorkInfoByIdFlow(id).first()
                assertEquals(WorkInfo.State.CANCELLED, cancelled?.state)
                // Completing the run resets attempts for the next period.
                assertEquals(0, cancelled?.runAttemptCount)
            }
        }
    }

    @Test
    fun `seed cancels a due one shot sync whose network constraint stays unmet`() = runBlocking {
        // Seed waits out the driver's whole 15 s start-up grace for the unmet constraint first.
        withTimeout(25_000) {
            withStartupSync(5, SyncWorker.SHORT_WORK_NAME, runSync = { error("Network constraint was not released") }) { work, _ ->
                SyncWorker.syncOnResume(app)
                val pending = work.getWorkInfosForUniqueWorkFlow(SyncWorker.ONE_SHOT_WORK_NAME)
                    .first { it.isNotEmpty() }.single()
                assertEquals(WorkInfo.State.ENQUEUED, pending.state)
                assertEquals(0, pending.runAttemptCount)
                assertEquals(NetworkType.CONNECTED, pending.constraints.requiredNetworkType)
                assertTrue("The one-shot sync is already due", pending.nextScheduleTimeMillis <= System.currentTimeMillis())

                val completion = command("seed", driver = StatusIconEvidenceDriver(app))

                assertTrue(completion.optString("error"), completion.getBoolean("success"))
                assertEquals(6, completion.getJSONArray("accounts").length())
                val cancelled = work.getWorkInfoByIdFlow(pending.id).first()
                assertEquals(WorkInfo.State.CANCELLED, cancelled?.state)
                assertEquals(0, cancelled?.runAttemptCount)
            }
        }
    }

    @Test
    fun `same provider command preserves fixture ids and publishes two named Claude accounts`() = runBlocking {
        assertFalse(command("same-provider").getBoolean("success"))
        val seeded = command("seed")
        assertTrue(seeded.getBoolean("success"))
        val originalIds = seeded.getJSONObject("localIds")
        assertEquals(6, originalIds.length())
        assertFalse(originalIds.has("evidence-claude-work"))
        assertTrue(command("enable").getBoolean("success"))
        assertTrue(command("pin", "evidence-codex").getBoolean("success"))
        assertTrue(command("states").getBoolean("success"))

        val duplicate = command("same-provider")
        assertTrue(duplicate.optString("error"), duplicate.getBoolean("success"))
        assertTrue(duplicate.getBoolean("enabled"))
        val localIds = duplicate.getJSONObject("localIds")
        assertEquals(setOf("evidence-claude", "evidence-codex", "evidence-gemini", "evidence-xai", "evidence-copilot", "evidence-cursor", "evidence-claude-work"),
            (0 until duplicate.getJSONArray("accounts").length()).map { duplicate.getJSONArray("accounts").getString(it) }.toSet())
        assertEquals(7, duplicate.getJSONArray("accounts").length())
        assertEquals(7, localIds.length())
        for (alias in originalIds.keys()) assertEquals(originalIds.getString(alias), localIds.getString(alias))
        assertEquals(listOf("evidence-claude", "evidence-claude-work"),
            (0 until duplicate.getJSONArray("pins").length()).map { duplicate.getJSONArray("pins").getString(it) })
        val personalId = localIds.getString("evidence-claude")
        val workId = localIds.getString("evidence-claude-work")
        assertTrue(personalId != workId)
        assertEquals(personalId, duplicate.getJSONObject("duplicateLocalIds").getString("personal"))
        assertEquals(workId, duplicate.getJSONObject("duplicateLocalIds").getString("work"))
        assertEquals("claude-color", app.container.settingsStore.settings.first().providerIcons[ProviderId.CLAUDE.id])
        assertEquals(setOf(ProviderId.CLAUDE), app.container.repository.accounts()
            .filter { it.localId in setOf(personalId, workId) }.map { it.provider }.toSet())

        val records = statusRecords()
        assertEquals(4, records.size)
        for ((id, name, title) in listOf(
            Triple(personalId, "Claude (synthetic)", "42% left"),
            Triple(workId, "Claude work (synthetic)", "87% left"),
        )) {
            val group = records.filter { it.tag == "status-icon:$id" }
            assertEquals(2, group.size)
            assertEquals(1, group.count { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 })
            for (record in group) {
                val notification = record.notification
                assertEquals(title, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertEquals("Claude · $name", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
                val visibleText = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT)
                    .joinToString("\n") { notification.extras.getCharSequence(it).toString() }
                assertEquals(1, Regex(Regex.escape(name)).findAll(visibleText).count())
                assertNotNull(notification.getLargeIcon())
            }
        }
        assertEquals(0, manager.activeNotifications.count { it.notification.channelId == NotificationPublisher.CHANNEL_ID })
        val repeated = command("same-provider")
        assertTrue(repeated.getBoolean("success"))
        assertEquals(localIds.toString(), repeated.getJSONObject("localIds").toString())
        assertEquals(4, statusRecords().size)
        val cached = command("cache")
        assertTrue(cached.getBoolean("success"))
        assertEquals(workId, cached.getJSONObject("localIds").getString("evidence-claude-work"))
        assertEquals(2, cached.getJSONArray("pins").length())
    }

    @Test
    fun `same provider command preserves reordered accounts`() = runBlocking {
        val seeded = command("seed")
        assertTrue(seeded.getBoolean("success"))
        val originalIds = seeded.getJSONObject("localIds")
        assertTrue(command("reorder").getBoolean("success"))

        val duplicate = command("same-provider")
        assertTrue(duplicate.optString("error"), duplicate.getBoolean("success"))
        assertEquals(listOf("evidence-gemini", "evidence-claude", "evidence-codex", "evidence-xai", "evidence-copilot", "evidence-cursor", "evidence-claude-work"),
            (0 until duplicate.getJSONArray("accounts").length()).map { duplicate.getJSONArray("accounts").getString(it) })
        val localIds = duplicate.getJSONObject("localIds")
        assertEquals(7, localIds.length())
        for (alias in originalIds.keys()) assertEquals(originalIds.getString(alias), localIds.getString(alias))
        assertEquals(listOf("evidence-claude", "evidence-claude-work"),
            (0 until duplicate.getJSONArray("pins").length()).map { duplicate.getJSONArray("pins").getString(it) })
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
        assertTrue(records.any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().startsWith("42% left") })

        assertTrue(command("update").getBoolean("success"))
        assertTrue(statusRecords().any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().startsWith("87% left") })
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
        assertTrue(statusRecords().any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().startsWith("42% left") })
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
