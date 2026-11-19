package com.usagelimits.evidence

import android.os.SystemClock
import android.util.AtomicFile
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WidgetScope
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.model.pinnedAccountIds
import com.usagelimits.core.sync.SyncWorker
import com.usagelimits.feature.UsageUiState
import com.usagelimits.widget.WidgetDataBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Drives the real cache and publication seam; never constructs or cancels notifications. */
internal class StatusIconEvidenceDriver(
    private val app: UsageLimitsApp,
    /** The app's own container on a device; a test supplies one with scripted clocks. */
    private val container: AppContainer = app.container,
    private val prepareEmptyInstall: suspend () -> Unit = { quiesceStartup(app) },
) {
    private val repository get() = container.repository
    private val sequence = Mutex()
    private var seeded = false

    suspend fun execute(command: String?, token: String?, account: String? = null) = sequence.withLock {
        var failure: String? = null
        try {
            require(!token.isNullOrBlank()) { "A unique token is required" }
            require(command in COMMANDS) { "Unknown command: $command" }
            require(account == null || FIXTURES.any { it.alias == account }) { "Unknown account: $account" }
            require(command == "seed" || seeded) { "Seed a clean installation first; clear app data after process restart" }
            when (command) {
                "seed" -> seed()
                "same-provider" -> sameProvider()
                "enable" -> container.statusIconChanges.setEnabled(true)
                "pin", "unpin" -> {
                    val selected = fixtureAccount(requireNotNull(account) { "An account alias is required" })
                    check(container.statusIconChanges.setPinned(selected.localId, command == "pin")) { "Pin rejected for $account" }
                }
                "update" -> {
                    reading(fixtureAccount("evidence-claude"), 87.0)
                    container.publication.cacheMoved()
                }
                // A synthetic reading, not a user action: Antigravity drops to 2% left, which moves
                // it to the top of Overview's automatic order while the stored order, and with it
                // the pinned icons' order, stays as seeded (ADR 0004).
                "rerank" -> {
                    reading(fixtureAccount("evidence-gemini"), 2.0)
                    container.publication.cacheMoved()
                }
                "states" -> {
                    reading(fixtureAccount("evidence-claude"), 0.0, exhausted = true)
                    reading(fixtureAccount("evidence-codex"), 5.0, stale = true)
                    repository.saveFailure(fixtureAccount("evidence-gemini").localId, "Synthetic evidence sync error", System.currentTimeMillis())
                    container.publication.cacheMoved()
                }
                "reset-readings" -> {
                    initialReadings()
                    container.publication.cacheMoved()
                }
                "presentation" -> container.publication.presentationChanged()
                "cache" -> container.publication.cacheMoved()
            }
        } catch (_: TimeoutCancellationException) {
            failure = "Sync work did not become idle within 45 seconds; clear app data and retry"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: error.javaClass.simpleName
        }
        writeCompletion(command, token, failure, SystemClock.elapsedRealtime())
    }

    private suspend fun seed() {
        if (!seeded) {
            check(repository.accounts().isEmpty() && container.creditRepository.entriesOnce().isEmpty()) {
                "First seed requires an empty installation; clear app data and grant notifications again"
            }
            prepareEmptyInstall()
        }
        val changes = container.statusIconChanges
        changes.setEnabled(false)
        for (id in container.settingsStore.settings.first().pinnedAccountIds) {
            check(changes.setPinned(id, false))
        }
        val settings = container.settingsStore
        settings.setPercentDisplay(PercentDisplay.LEFT)
        settings.setNotifyBelow20Percent(false)
        settings.setNotifyBelow10Percent(false)
        settings.setNotifyOnExhausted(false)
        settings.setNotifyOnResetCredit(false)
        settings.setNotifyOnAuthExpired(false)
        settings.setNotifyOnResetApproaching(false)
        settings.setNotifyOnResetCreditExpiring(false)
        // Added newest-first, then arranged by the repository writer into the order a build with
        // Overview dragging left behind: a pre-upgrade stored order that differs from the order
        // of addition, from the pin order the runner uses and from the automatic rank. Fixture
        // set-up, not user behaviour; nothing in the app writes this order any more.
        val added = FIXTURES.asReversed().map { fixture ->
            repository.upsertFromLogin(fixture.provider, fixture.alias, null,
                "${fixture.provider.displayName} (synthetic)", null, mapOf("screenshotFixture" to "true"))
        }
        repository.reorderAccounts(FIXTURES.map { fixture -> added.single { it.externalAccountId == fixture.alias }.localId })
        initialReadings()
        container.publication.cacheMoved()
        seeded = true
    }

    private suspend fun sameProvider() {
        val changes = container.statusIconChanges
        changes.setEnabled(false)
        for (id in container.settingsStore.settings.first().pinnedAccountIds) {
            check(changes.setPinned(id, false))
        }
        val personal = fixtureAccount("evidence-claude")
        val work = repository.upsertFromLogin(WORK_FIXTURE.provider, WORK_FIXTURE.alias, null,
            "Claude work (synthetic)", null, mapOf("screenshotFixture" to "true"))
        reading(personal, 42.0)
        reading(work, WORK_FIXTURE.left)
        container.settingsStore.setProviderIcon(ProviderId.CLAUDE.id, "claude-color")
        container.publication.cacheMoved()
        check(changes.setPinned(personal.localId, true))
        check(changes.setPinned(work.localId, true))
        changes.setEnabled(true)
    }

    private suspend fun initialReadings() {
        for (fixture in FIXTURES) {
            val account = fixtureAccount(fixture.alias)
            reading(account, fixture.left, exhausted = fixture.alias == "evidence-xai", stale = fixture.alias == "evidence-copilot")
            if (fixture.alias == "evidence-cursor") {
                repository.saveFailure(account.localId, "Synthetic evidence sync error", System.currentTimeMillis())
            }
        }
    }

    private suspend fun fixtureAccount(alias: String): ProviderAccount = checkNotNull(repository.accounts().firstOrNull {
        it.externalAccountId == alias && FIXTURES.any { fixture -> fixture.alias == alias && fixture.provider == it.provider }
    }) { "Fixture account is missing: $alias" }

    private suspend fun reading(account: ProviderAccount, left: Double, exhausted: Boolean = false, stale: Boolean = false) {
        val now = System.currentTimeMillis()
        repository.saveSnapshot(UsageSnapshot(account.localId, if (stale) now - 86_400_000L else now, SnapshotStatus.OK,
            windows = listOf(UsageWindow("evidence-five-hour", "5h limit", WindowCategory.FIVE_HOUR,
                usedPercent = 100.0 - left, periodSeconds = 18_000, resetAt = now + 18_000_000L, exhausted = exhausted))))
    }

    private suspend fun writeCompletion(command: String?, token: String?, error: String?, completedElapsedRealtimeMs: Long) {
        val accounts = repository.accounts()
        val aliases = accounts.mapNotNull { account ->
            (FIXTURES.firstOrNull { it.alias == account.externalAccountId && it.provider == account.provider }
                ?: WORK_FIXTURE.takeIf { it.alias == account.externalAccountId && it.provider == account.provider })
                ?.let { it.alias to account.localId }
        }
        val byId = aliases.associate { (alias, id) -> id to alias }
        val settings = container.settingsStore.settings.first()
        // The two automatic-order projections at one instant: Overview's list and the All
        // accounts widget's selection, before any layout trims it.
        val usage = repository.accountUsageOnce()
        val rankedAt = System.currentTimeMillis()
        fun aliasesOf(ids: List<String>) = JSONArray(ids.mapNotNull { byId[it] })
        val completion = JSONObject()
            .put("token", token ?: JSONObject.NULL)
            .put("command", command ?: JSONObject.NULL)
            .put("success", error == null)
            .put("completedElapsedRealtimeMs", completedElapsedRealtimeMs)
            .put("accounts", JSONArray(aliases.map { it.first }))
            .put("pins", JSONArray(settings.pinnedAccountIds.mapNotNull { byId[it] }))
            // The order the publisher posts pinned icons in: stored order, not pin order.
            .put("pinOrder", JSONArray(pinnedAccountIds(settings.statusIconsEnabled, settings.pinnedAccountIds,
                accounts.map { it.localId }).mapNotNull { byId[it] }))
            .put("enabled", settings.statusIconsEnabled)
            .put("overview", aliasesOf(UsageUiState(usage).overviewAccountsAt(rankedAt).map { it.account.localId }))
            .put("allAccounts", aliasesOf(WidgetDataBuilder.build(usage, rankedAt, WidgetScope.ALL_ACCOUNTS, null, null)
                .accounts.map { it.accountId }))
            .put("localIds", JSONObject(aliases.toMap()))
        if (command == "same-provider" && error == null) {
            val localIds = aliases.toMap()
            completion.put("duplicateLocalIds", JSONObject()
                .put("personal", localIds.getValue("evidence-claude"))
                .put("work", localIds.getValue(WORK_FIXTURE.alias)))
        }
        if (error != null) completion.put("error", error)
        val file = AtomicFile(File(app.filesDir, "status-icon-evidence.json"))
        val output = file.startWrite()
        try {
            output.write(completion.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    private data class Fixture(val alias: String, val provider: ProviderId, val left: Double)

    companion object {
        fun observingStartup(app: UsageLimitsApp, onSnapshot: suspend (List<WorkInfo>) -> Unit) =
            StatusIconEvidenceDriver(app, prepareEmptyInstall = { quiesceStartup(app, onSnapshot) })

        private val COMMANDS = setOf("seed", "enable", "pin", "unpin", "update", "rerank", "states", "reset-readings", "presentation", "cache", "same-provider")
        private val WORK_FIXTURE = Fixture("evidence-claude-work", ProviderId.CLAUDE, 87.0)
        private val FIXTURES = listOf(
            Fixture("evidence-claude", ProviderId.CLAUDE, 42.0),
            Fixture("evidence-codex", ProviderId.CODEX, 5.0),
            Fixture("evidence-gemini", ProviderId.ANTIGRAVITY, 100.0),
            Fixture("evidence-xai", ProviderId.XAI, 0.0),
            Fixture("evidence-copilot", ProviderId.KIMI, 5.0),
            Fixture("evidence-cursor", ProviderId.DEVIN, 42.0),
        )

        private fun WorkInfo.hasActiveAttempt() = !state.isFinished && (state == WorkInfo.State.RUNNING || runAttemptCount != 0)

        private suspend fun quiesceStartup(
            app: UsageLimitsApp,
            onSnapshot: (suspend (List<WorkInfo>) -> Unit)? = null,
        ) = withTimeout(45_000L) {
            app.startupJob?.join()
            val work = WorkManager.getInstance(app)
            // The default class tag includes periodic, short-chain and one-shot sync work.
            val syncWork = SyncWorker::class.java.name
            val workInfo = work.getWorkInfosByTagFlow(syncWork)
            val startupInfo = if (onSnapshot == null) workInfo else workInfo.onEach(onSnapshot)
            // Let due work start without waiting the full deadline for unmet constraints. The grace
            // runs out only while a due sync cannot start; otherwise this returns at once, so a
            // slow emulator start-up gets 15 s without slowing a normal seed.
            withTimeoutOrNull(15_000L) {
                startupInfo.first { requests ->
                    // Include work that can become due between this snapshot and cancellation.
                    val dueBy = System.currentTimeMillis() + 250L
                    requests.none {
                        it.hasActiveAttempt() ||
                            (it.state == WorkInfo.State.ENQUEUED && it.nextScheduleTimeMillis <= dueBy)
                    }
                }
            } ?: startupInfo.first { requests -> requests.none { it.hasActiveAttempt() } }
            work.cancelAllWorkByTag(syncWork).await()
            val remaining = workInfo.first()
            check(remaining.all { it.state.isFinished }) { "Sync work is still scheduled; clear app data and retry" }
            // WorkManager 2.12 increments attempts when claiming RUNNING and preserves them on cancel.
            // A request that raced the cancellation can still be publishing in NonCancellable.
            check(remaining.none { it.state == WorkInfo.State.CANCELLED && it.runAttemptCount != 0 }) {
                "An attempted sync was cancelled before it could be verified idle; clear app data and retry"
            }
        }
    }
}
