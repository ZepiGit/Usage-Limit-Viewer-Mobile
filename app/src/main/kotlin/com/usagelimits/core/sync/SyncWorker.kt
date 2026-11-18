package com.usagelimits.core.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background sync shares SyncEngine with the UI and publishes the refreshed cache.
 * Partial success returns success; an all-failed periodic pass may retry.
 * User-triggered one-shots finish without retry so KEEP cannot block later taps during backoff.
 * Short-interval attempts release a pre-armed successor even when provider reads failed.
 * Provider failures are recorded per account.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!inputData.getBoolean(KEY_SHORT, false)) {
            return resultFor(sync(), periodic = inputData.getBoolean(KEY_PERIODIC, false))
        }
        try {
            if (!ensureShortSuccessor()) return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("SyncWorker", "short sync successor could not be scheduled", error)
            return Result.retry()
        }
        try {
            sync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("SyncWorker", "short sync attempt failed", error)
        }
        // Success releases the pre-armed successor even when providers failed this attempt.
        return Result.success()
    }

    private suspend fun ensureShortSuccessor(): Boolean = scheduleLock.withLock {
        val container = (applicationContext as UsageLimitsApp).container
        val interval = container.settingsStore.settings.first().syncIntervalMinutes
        if (interval >= PERIODIC_MINUTES) return@withLock false
        val manager = WorkManager.getInstance(applicationContext)
        val active = manager.getWorkInfosFlow(activeShortWork).first()
        if (active.none { it.id == id && it.state == WorkInfo.State.RUNNING }) return@withLock false
        // An OS stop may rerun this request with its successor already blocked on it.
        if (active.none { it.id != id }) {
            manager.enqueueUniqueWork(
                SHORT_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, shortRequest(interval),
            ).await()
        }
        true
    }

    private suspend fun sync(): List<SyncOutcome> {
        val container = (applicationContext as UsageLimitsApp).container
        val now = System.currentTimeMillis()

        // A widget-driven trigger only fetches when the cache has actually aged. The system
        // asks every placed widget to update every `updatePeriodMillis`, which is also the
        // moment a widget was just placed or resized — none of which should cost a provider
        // round trip when a pass landed a minute ago.
        if (inputData.getBoolean(KEY_ONLY_IF_AGED, false)) {
            val accounts = container.repository.accountUsageOnce()
            val interval = container.settingsStore.settings.first().syncIntervalMinutes
            val credits = container.creditRepository.entriesOnce()
            if (!cacheNeedsSync(accounts, interval, now) && !creditCacheNeedsSync(credits, interval, now)) {
                return emptyList()
            }
        }

        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (manual) reconcileForManualRefresh(applicationContext, container.settingsStore.settings)
        container.settingsStore.recordSyncAttempt(now)

        return publishingEvenIfCancelled(
            sync = {
                // Credit accounts ride the same pass. Each is isolated, so a bad key costs one line.
                (container.syncEngine.syncAll(automatic = !manual) + container.creditSyncEngine.syncAll(automatic = !manual))
                    .also { container.settingsStore.recordSyncFinished(System.currentTimeMillis()) }
            },
            publish = { container.publication.cacheMoved() },
        )
    }

    companion object {
        internal const val PERIODIC_WORK_NAME = "usage_sync_periodic"
        internal const val ONE_SHOT_WORK_NAME = "usage_sync_now"
        internal const val SHORT_WORK_NAME = "usage_sync_short"

        private const val KEY_MANUAL = "manual"
        private const val FOREGROUND_TAG = "usage_sync_foreground"

        /** Only periodic work retries when every account fails. */
        internal const val KEY_PERIODIC = "periodic"

        /** Set by widget-driven triggers: fetch only when the cache is older than the interval. */
        internal const val KEY_ONLY_IF_AGED = "onlyIfAged"

        /** Tags the `onlyIfAged` one-shot, so a user's tap can find and supersede it. */
        internal const val AGED_TAG = "usage_sync_aged"

        /**
         * How long a periodic pass waits after every account failed.
         *
         * Linear and short. WorkManager's default is exponential from 30 seconds, and a run of
         * all-account failures — a captive portal, a DNS outage — grew that into hours, during
         * which the widgets aged with nothing scheduled to move them.
         */
        private const val RETRY_BACKOFF_MINUTES = 10L

        /**
         * Which result a pass reports, given what happened to each account.
         *
         * Pure, so the policy is testable: only a periodic pass in which nothing succeeded asks
         * for a retry.
         */
        fun resultFor(outcomes: List<SyncOutcome>, periodic: Boolean): Result = when {
            outcomes.isEmpty() || outcomes.any { it.success } -> Result.success()
            periodic -> Result.retry()
            else -> Result.failure()
        }

        /**
         * Runs [sync], then [publish] — even when the pass is cancelled part-way (a lost
         * constraint, the system reclaiming the worker): accounts it already saved must reach
         * the widgets now, not at the next pass.
         */
        internal suspend fun <T> publishingEvenIfCancelled(
            sync: suspend () -> T,
            publish: suspend () -> Unit,
        ): T = try {
            sync()
        } finally {
            withContext(NonCancellable) { publish() }
        }

        /**
         * Refresh each account when its last attempt is due, or once at a newly passed reset.
         * A fresh account cannot hide an older or missing one. Attempts bound retry frequency
         * after a failure while fetchedAt continues to describe the age of the cached values.
         */
        fun cacheNeedsSync(accounts: List<AccountUsage>, syncIntervalMinutes: Int, nowMs: Long): Boolean {
            val intervalMs = syncIntervalMinutes.coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES) * 60_000L
            return accounts.any { usage ->
                val snapshot = usage.snapshot ?: return@any true
                val due = nowMs - snapshot.lastAttemptAt >= intervalMs
                val resetSinceAttempt = snapshot.windows.any { window ->
                    val resetAt = window.resetAt
                    resetAt != null && resetAt <= nowMs && snapshot.lastAttemptAt < resetAt
                }
                due || resetSinceAttempt
            }
        }

        /** [cacheNeedsSync] for credit accounts: never read, or last attempted an interval ago. */
        fun creditCacheNeedsSync(entries: List<CreditEntry>, syncIntervalMinutes: Int, nowMs: Long): Boolean {
            val intervalMs = syncIntervalMinutes.coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES) * 60_000L
            return entries.any { entry ->
                val snapshot = entry.snapshot ?: return@any true
                nowMs - snapshot.lastAttemptAt >= intervalMs
            }
        }

        private val scheduleLock = Mutex()
        private const val KEY_SHORT = "shortInterval"
        private const val INTERVAL_TAG = "usage_sync_interval:"
        private val activeShortWork = WorkQuery.Builder.fromUniqueWorkNames(listOf(SHORT_WORK_NAME))
            .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED))
            .build()
        private val PERIODIC_MINUTES = TimeUnit.MILLISECONDS.toMinutes(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS).toInt()

        /** UPDATE preserves periodic timing; short requests keep their pending delay on restart. */
        internal suspend fun scheduleBackgroundSync(
            context: Context,
            settings: Flow<AppSettings> = (context.applicationContext as UsageLimitsApp).container.settingsStore.settings,
        ) = scheduleLock.withLock<Unit> {
            val interval = try {
                settings.first().syncIntervalMinutes
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("SyncWorker", "could not read the refresh interval; using the default", error)
                AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES
            }
            val manager = WorkManager.getInstance(context)
            if (interval < PERIODIC_MINUTES) {
                manager.cancelUniqueWork(PERIODIC_WORK_NAME).await()
                val active = manager.getWorkInfosFlow(activeShortWork).first()
                if (active.isEmpty() || active.any { "$INTERVAL_TAG$interval" !in it.tags }) {
                    manager.enqueueUniqueWork(SHORT_WORK_NAME, ExistingWorkPolicy.REPLACE, shortRequest(interval)).await()
                }
            } else {
                manager.cancelUniqueWork(SHORT_WORK_NAME).await()
                val request = PeriodicWorkRequestBuilder<SyncWorker>(interval.toLong(), TimeUnit.MINUTES)
                    .setConstraints(connected())
                    .setInputData(workDataOf(KEY_PERIODIC to true))
                    .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
                    .build()
                manager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request).await()
            }
        }

        private fun shortRequest(interval: Int) = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(interval.toLong(), TimeUnit.MINUTES)
            .setConstraints(connected())
            .setInputData(workDataOf(KEY_SHORT to true))
            .addTag("$INTERVAL_TAG$interval")
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

        /** Repairs a failed short chain without making an explicit refresh depend on scheduling. */
        internal suspend fun reconcileForManualRefresh(context: Context, settings: Flow<AppSettings>) {
            try {
                scheduleBackgroundSync(context, settings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("SyncWorker", "could not restore the background schedule", error)
            }
        }

        /** A widget refresh tap must not inherit a pending automatic pass's cooldown. */
        fun syncNow(context: Context) = synchronized(triggerLock) {
            val manager = WorkManager.getInstance(context)
            manager.cancelAllWorkByTag(AGED_TAG)
            manager.cancelAllWorkByTag(FOREGROUND_TAG)
            enqueueOneShot(manager, onlyIfAged = false, manual = true)
        }

        /** Opening the screen requests fresh readings, but is not an explicit refresh. */
        internal fun syncOnResume(context: Context) = synchronized(triggerLock) {
            val manager = WorkManager.getInstance(context)
            manager.cancelAllWorkByTag(AGED_TAG)
            enqueueOneShot(manager, onlyIfAged = false, manual = false)
        }

        /** Automatic widget updates and presentation boundaries read only when due. */
        fun syncIfCacheAged(context: Context) = synchronized(triggerLock) {
            enqueueOneShot(WorkManager.getInstance(context), onlyIfAged = true, manual = false)
        }

        // Serialize each cancel/enqueue pair so a lower-priority trigger cannot take the name in between.
        private val triggerLock = Any()

        private fun enqueueOneShot(manager: WorkManager, onlyIfAged: Boolean, manual: Boolean) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(connected())
                .setInputData(workDataOf(KEY_ONLY_IF_AGED to onlyIfAged, KEY_MANUAL to manual))
                .apply {
                    if (onlyIfAged) addTag(AGED_TAG) else if (!manual) addTag(FOREGROUND_TAG)
                }
                .apply {
                    // Expedited so a refresh the user just asked for — or one the system just
                    // asked for on behalf of a widget — does not wait for the next Doze
                    // maintenance window. Below Android 12 an expedited CoroutineWorker must
                    // supply a foreground notification, which a quota check does not warrant;
                    // there the request stays ordinary.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()

            // KEEP coalesces repeated triggers. Higher-priority requests cancel lower-priority work above.
            manager.enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private fun connected() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    }
}
