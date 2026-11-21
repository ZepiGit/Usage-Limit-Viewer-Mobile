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
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.overviewCreditSummary
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

    @Test
    fun `credit fixture keeps a usable compact hint through an automatic refresh`() = runBlocking {
        val completion = command("seed-credits")
        assertTrue(completion.toString(), completion.getBoolean("success"))
        val credits = app.container.creditRepository
        val before = credits.entriesOnce().single()
        try {
            val instant = requireNotNull(before.snapshot).fetchedAt
            val engine = com.usagelimits.core.sync.CreditSyncEngine(
                credits, app.container.credentialStore, app.container.creditRegistry, nowMs = { instant },
            )
            engine.syncAll(automatic = true)
            val winner = credits.entriesOnce().overviewCreditSummary(instant, 3_600_000).winner
            assertEquals("Synthetic credit", winner?.account?.label)
            assertEquals("3.18", winner?.balance?.amount?.toPlainString())
        } finally {
            credits.remove(before.id)
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
    fun `same provider command preserves the seeded stored order`() = runBlocking {
        val seeded = command("seed")
        assertTrue(seeded.getBoolean("success"))
        val originalIds = seeded.getJSONObject("localIds")
        assertTrue(command("rerank").getBoolean("success"))

        val duplicate = command("same-provider")
        assertTrue(duplicate.optString("error"), duplicate.getBoolean("success"))
        assertEquals(listOf("evidence-claude", "evidence-codex", "evidence-gemini", "evidence-xai", "evidence-copilot", "evidence-cursor", "evidence-claude-work"),
            (0 until duplicate.getJSONArray("accounts").length()).map { duplicate.getJSONArray("accounts").getString(it) })
        val localIds = duplicate.getJSONObject("localIds")
        assertEquals(7, localIds.length())
        for (alias in originalIds.keys()) assertEquals(originalIds.getString(alias), localIds.getString(alias))
        assertEquals(listOf("evidence-claude", "evidence-claude-work"),
            (0 until duplicate.getJSONArray("pins").length()).map { duplicate.getJSONArray("pins").getString(it) })
    }

    /**
     * The order split the CI scenario shows on a device (ADR 0004). Seed arranges a pre-upgrade
     * stored order through the repository writer; Overview and All accounts rank by the headline,
     * the pinned icons follow the stored order. A synthetic reading for Antigravity then moves it
     * up the automatic order while the stored order, the pin list and the icons' order stay put,
     * and the publication posts that reading.
     *
     * Both clocks are scripted (standard 1): accounts are added at fixed, distinct instants, and
     * the publisher's wall clock moves only when it waits for the next tick, as in
     * `StatusIconClockTest`, so the order of the `when` stamps cannot depend on the host.
     */
    @Test
    fun `a reading reranks Overview and All accounts while the pinned icons keep the stored order`() = runBlocking {
        var added = 1_700_000_000_000L
        // A starting point only: the readings the driver writes are fresh against it, and from
        // here the publisher's clock advances by the script alone.
        var wall = System.currentTimeMillis()
        val container = object : AppContainer(app) {
            override val repository by lazy {
                UsageRepository(database.accountDao(), database.usageSnapshotDao(), transactions = transactions,
                    nowMs = { added += 60_000; added })
            }
            override val statusIconPublisher by lazy {
                StatusIconPublisher(app, settingsStore, repository::accountUsageOnce,
                    now = { wall }, nextTick = { wall += 1 }, monotonic = { 0L })
            }
        }
        val driver = StatusIconEvidenceDriver(app, container, prepareEmptyInstall = {})
        val seeded = command("seed", driver = driver)
        val stored = listOf("evidence-claude", "evidence-codex", "evidence-gemini", "evidence-xai", "evidence-copilot", "evidence-cursor")
        assertEquals(stored, seeded.strings("accounts"))
        // Added newest-first: each account in stored order was added strictly after the next one.
        val created = container.repository.accounts().associate { it.externalAccountId to it.createdAt }
        assertTrue("addition order runs against the stored order: $created",
            stored.zipWithNext().all { (a, b) -> created.getValue(a) > created.getValue(b) })
        assertTrue(command("enable", driver = driver).getBoolean("success"))
        for (alias in listOf("evidence-codex", "evidence-gemini", "evidence-claude")) {
            assertTrue(command("pin", alias, driver = driver).getBoolean("success"))
        }
        val before = command("update", driver = driver)
        val pinOrder = listOf("evidence-claude", "evidence-codex", "evidence-gemini")
        assertEquals("pins follow the stored order, not the pin order", pinOrder, before.strings("pinOrder"))
        assertEquals("Antigravity (100 left) ranks last before the reading", "evidence-gemini", before.strings("overview").last())
        assertEquals(before.strings("overview"), before.strings("allAccounts"))
        assertEquals(pinOrder, postedOrder(container))
        val gemini = before.getJSONObject("localIds").getString("evidence-gemini")
        assertEquals("summary and child show the reading before", listOf("100% left", "100% left"), titles(gemini))
        val keys = statusRecords().map { it.key }.toSet()

        val after = command("rerank", driver = driver)
        assertEquals("xAI (exhausted) then Antigravity (2 left) lead", listOf("evidence-xai", "evidence-gemini"), after.strings("overview").take(2))
        assertEquals(after.strings("overview"), after.strings("allAccounts"))
        assertEquals(stored, after.strings("accounts"))
        assertEquals(before.strings("pins"), after.strings("pins"))
        assertEquals("the icons keep the stored order", pinOrder, after.strings("pinOrder"))
        assertEquals(pinOrder, postedOrder(container))
        assertEquals("the publication posted the new reading on summary and child", listOf("2% left", "2% left"), titles(gemini))
        assertEquals("the same six records were updated in place", keys, statusRecords().map { it.key }.toSet())
    }

    /** The titles of one account's status records, summary first. */
    private fun titles(localId: String): List<String> = statusRecords().filter { it.tag == "status-icon:$localId" }
        .sortedByDescending { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        .map { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() }

    /** Pinned accounts in the order the publisher stamped them: the newest `when` leads. */
    private suspend fun postedOrder(container: AppContainer): List<String> {
        val aliases = container.repository.accounts().associate { it.localId to it.externalAccountId }
        return statusRecords().groupBy { it.tag.removePrefix("status-icon:") }
            .mapValues { (_, records) -> records.maxOf { it.notification.`when` } }
            .entries.sortedByDescending { it.value }.map { aliases.getValue(it.key) }
    }

    private fun JSONObject.strings(name: String): List<String> = getJSONArray(name).let { array -> (0 until array.length()).map(array::getString) }

    @Test
    fun `driver commands seed real accounts publish six records update readings and report rejection`() = runBlocking {
        val seeded = command("seed")
        assertTrue(seeded.getBoolean("success"))
        assertFalse(seeded.getBoolean("enabled"))
        assertEquals(0, seeded.getJSONArray("pins").length())
        assertEquals(0, statusRecords().size)
        val seededOrder = listOf("evidence-claude", "evidence-codex", "evidence-gemini", "evidence-xai", "evidence-copilot", "evidence-cursor")
        assertEquals(seededOrder, seeded.strings("accounts"))
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

        val reranked = command("rerank")
        assertTrue(reranked.getBoolean("success"))
        assertEquals("a reading moves no stored position", seededOrder, reranked.strings("accounts"))
        assertTrue(command("states").getBoolean("success"))
        assertTrue(app.container.repository.snapshot(localIds.getString("evidence-claude"))!!.windows.single().exhausted)
        assertTrue(app.container.repository.snapshot(localIds.getString("evidence-codex"))!!.isStaleAt(System.currentTimeMillis()))
        assertEquals(com.usagelimits.core.model.SnapshotStatus.FAILED,
            app.container.repository.snapshot(localIds.getString("evidence-gemini"))!!.status)
        val readingsReset = command("reset-readings")
        assertTrue(readingsReset.getBoolean("success"))
        assertEquals(seededOrder, readingsReset.strings("accounts"))
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
