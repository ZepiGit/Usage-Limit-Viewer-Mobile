package com.usagelimits.evidence

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.util.AtomicFile
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.statusicon.PrototypeStatusIconRecipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Drives the real cache and publication seam; never constructs or cancels notifications. */
internal class StatusIconEvidenceDriver(
    private val app: UsageLimitsApp,
    private val prepareEmptyInstall: suspend () -> Unit = { quiesceStartup(app) },
) {
    private val container get() = app.container
    private val repository get() = container.repository
    private val sequence = Mutex()
    private var seeded = false

    suspend fun execute(command: String?, token: String?, account: String? = null) = sequence.withLock {
        var failure: String? = null
        try {
            require(!token.isNullOrBlank()) { "A unique token is required" }
            require(command in COMMANDS) { "Unknown command: $command" }
            require(command == "recipe" || account == null || FIXTURES.any { it.alias == account }) { "Unknown account: $account" }
            require(command == "seed" || seeded) { "Seed a clean installation first; clear app data after process restart" }
            when (command) {
                "seed" -> seed()
                "enable" -> container.statusIconChanges.setEnabled(true)
                "disable" -> container.statusIconChanges.setEnabled(false)
                "recipe" -> selectRecipe(account)
                "pin", "unpin" -> {
                    val selected = fixtureAccount(requireNotNull(account) { "An account alias is required" })
                    check(container.statusIconChanges.setPinned(selected.localId, command == "pin")) { "Pin rejected for $account" }
                }
                "update" -> {
                    reading(fixtureAccount("evidence-claude"), 87.0)
                    container.publication.cacheMoved()
                }
                "reorder" -> {
                    repository.reorderAccounts(listOf("evidence-gemini", "evidence-claude", "evidence-codex", "evidence-xai", "evidence-copilot", "evidence-cursor")
                        .map { fixtureAccount(it).localId })
                    container.settingsStore.setAccountsManuallyOrdered(true)
                    container.publication.presentationChanged()
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
            failure = if (command == "seed") {
                "Empty-install startup sync did not finish within 45 seconds; clear app data and retry"
            } else {
                "Evidence command '$command' timed out"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: error.javaClass.simpleName
        }
        writeCompletion(command, token, failure)
    }

    private suspend fun selectRecipe(value: String?) {
        val recipe = requireNotNull(PrototypeStatusIconRecipe.entries.firstOrNull { it.value == value }) { "Unknown recipe: $value" }
        check(!container.settingsStore.settings.first().statusIconsEnabled) { "Disable status icons before changing recipe" }
        val manager = app.getSystemService(NotificationManager::class.java)
        // Cancellation is asynchronous in Android; observe the existing OFF publication without retrying it.
        val cleared = withTimeoutOrNull(5_000L) {
            while (manager.activeNotifications.any { it.notification.channelId in PrototypeStatusIconRecipe.channels }) {
                delay(25)
            }
            true
        }
        check(cleared == true) {
            "Experiment records did not clear within 5 seconds while status icons were disabled; recipe unchanged"
        }
        recipe.store(app)
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
        selectRecipe("low")
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
        val accounts = FIXTURES.map { fixture ->
            repository.upsertFromLogin(fixture.provider, fixture.alias, null,
                "${fixture.provider.displayName} (synthetic)", null, mapOf("screenshotFixture" to "true"))
        }
        repository.reorderAccounts(accounts.map { it.localId })
        settings.setAccountsManuallyOrdered(true)
        initialReadings()
        container.publication.cacheMoved()
        seeded = true
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

    private suspend fun writeCompletion(command: String?, token: String?, error: String?) {
        val accounts = repository.accounts()
        val aliases = accounts.mapNotNull { account ->
            FIXTURES.firstOrNull { it.alias == account.externalAccountId && it.provider == account.provider }?.let { it.alias to account.localId }
        }
        val byId = aliases.associate { (alias, id) -> id to alias }
        val settings = container.settingsStore.settings.first()
        val recipe = PrototypeStatusIconRecipe.read(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val records = manager.activeNotifications.filter { it.notification.channelId in PrototypeStatusIconRecipe.channels }
        val completion = JSONObject()
            .put("token", token ?: JSONObject.NULL)
            .put("command", command ?: JSONObject.NULL)
            .put("success", error == null)
            .put("accounts", JSONArray(aliases.map { it.first }))
            .put("pins", JSONArray(settings.pinnedAccountIds.mapNotNull { byId[it] }))
            .put("enabled", settings.statusIconsEnabled)
            .put("localIds", JSONObject(aliases.toMap()))
            .put("recipe", recipe.value)
            .put("channel", recipe.channel)
            .put("grouped", recipe.grouped)
            .put("promotionAllowed", if (Build.VERSION.SDK_INT >= 36) manager.canPostPromotedNotifications() else JSONObject.NULL)
            .put("activeRecords", JSONArray(records.map { record ->
                val notification = record.notification
                JSONObject()
                    .put("tag", record.tag)
                    .put("id", record.id)
                    .put("channel", notification.channelId)
                    .put("group", notification.group ?: JSONObject.NULL)
                    .put("summary", notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
                    .put("flags", notification.flags)
                    .put("requested", notification.extras.getBoolean("android.requestPromotedOngoing"))
                    .put("actual", notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0)
                    .put("hasPromotableCharacteristics", if (Build.VERSION.SDK_INT >= 36) notification.hasPromotableCharacteristics() else JSONObject.NULL)
            }))
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

    private companion object {
        val COMMANDS = setOf("seed", "enable", "disable", "recipe", "pin", "unpin", "update", "reorder", "states", "reset-readings", "presentation", "cache")
        val FIXTURES = listOf(
            Fixture("evidence-claude", ProviderId.CLAUDE, 42.0),
            Fixture("evidence-codex", ProviderId.CODEX, 5.0),
            Fixture("evidence-gemini", ProviderId.ANTIGRAVITY, 100.0),
            Fixture("evidence-xai", ProviderId.XAI, 0.0),
            Fixture("evidence-copilot", ProviderId.KIMI, 5.0),
            Fixture("evidence-cursor", ProviderId.DEVIN, 42.0),
        )

        @SuppressLint("RestrictedApi")
        suspend fun quiesceStartup(app: UsageLimitsApp) = withTimeout(45_000L) {
            app.startupJob?.join()
            val work = WorkManager.getInstance(app)
            // The initial sync sees an empty cache; let its non-cancellable publication finish.
            while (true) {
                val initial = work.getWorkInfosForUniqueWork("usage_sync_now").get(5, TimeUnit.SECONDS)
                if (initial.isNotEmpty() && initial.all { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED }) break
                check(initial.none { it.state == WorkInfo.State.CANCELLED }) { "Startup sync was cancelled; clear app data and retry" }
                delay(25)
            }
            // WorkManager 2.12 increments periodCount after doWork returns, unlike cancellation
            // states which can precede SyncWorker's non-cancellable publication.
            val database = WorkManagerImpl.getInstance(app).workDatabase
            while (true) {
                val periodic = work.getWorkInfosForUniqueWork("usage_sync_periodic").get(5, TimeUnit.SECONDS)
                check(periodic.none { it.state.isFinished }) { "Periodic startup was interrupted; clear app data and retry" }
                if (periodic.isNotEmpty() && periodic.all { info ->
                        info.state == WorkInfo.State.ENQUEUED &&
                            (database.workSpecDao().getWorkSpec(info.id.toString())?.periodCount ?: 0) > 0
                    }) break
                delay(25)
            }
            work.cancelUniqueWork("usage_sync_periodic").result.get(5, TimeUnit.SECONDS)
            work.cancelUniqueWork("usage_sync_now").result.get(5, TimeUnit.SECONDS)
            while (listOf("usage_sync_periodic", "usage_sync_now").any { name ->
                    work.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).any { it.state == WorkInfo.State.RUNNING }
                }) delay(25)
        }
    }
}
