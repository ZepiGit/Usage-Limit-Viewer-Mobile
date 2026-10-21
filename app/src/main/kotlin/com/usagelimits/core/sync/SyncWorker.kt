package com.usagelimits.core.sync

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.settings.AppSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background sync shares SyncEngine with the UI and publishes the refreshed cache.
 * Partial success returns success; an all-failed periodic pass may retry.
 * One-shots finish without retry so KEEP cannot block later user triggers during backoff.
 * Each failure is already recorded per account.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
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
                return Result.success()
            }
        }

        container.settingsStore.recordSyncAttempt(now)

        val outcomes = publishingEvenIfCancelled(
            sync = {
                // Credit accounts ride the same pass. Each is isolated, so a bad key costs one line.
                (container.syncEngine.syncAll() + container.creditSyncEngine.syncAll())
                    .also { container.settingsStore.recordSyncFinished(System.currentTimeMillis()) }
            },
            publish = { container.publishAfterSync(applicationContext) },
        )

        return resultFor(outcomes, periodic = inputData.getBoolean(KEY_PERIODIC, false))
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "usage_sync_periodic"
        internal const val ONE_SHOT_WORK_NAME = "usage_sync_now"

        /** Set on the periodic request only: the one pass allowed to ask WorkManager for a retry. */
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

        /**
         * (Re)schedules the periodic pass.
         *
         * [ExistingPeriodicWorkPolicy.UPDATE] keeps the existing schedule when only the
         * interval changed, so changing the setting does not reset the timer and cause an
         * immediate extra sync.
         */
        fun schedulePeriodic(context: Context, intervalMinutes: Int) {
            val interval = intervalMinutes.coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES)

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                interval.toLong(),
                TimeUnit.MINUTES,
            )
                .setConstraints(connected())
                .setInputData(workDataOf(KEY_PERIODIC to true))
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /**
         * Fires an immediate sync — app start, pull-to-refresh, a widget's refresh tap.
         *
         * A pending `onlyIfAged` pass would otherwise hold the unique name under KEEP and may
         * skip the fetch, swallowing the tap; cancel it first. WorkManager runs both operations
         * on its serial executor, so the cancel lands before the enqueue. Both triggers hold
         * [triggerLock] so an aged enqueue from another thread cannot land between the two.
         */
        fun syncNow(context: Context) = synchronized(triggerLock) {
            WorkManager.getInstance(context).cancelAllWorkByTag(AGED_TAG)
            enqueueOneShot(context, onlyIfAged = false)
        }

        /**
         * Fires a sync only if the cache has aged past the interval or been overtaken by a
         * reset — the trigger for anything that runs on a clock rather than a user's tap: the
         * system's periodic widget update, and the cache-only presentation repaint.
         */
        fun syncIfCacheAged(context: Context) = synchronized(triggerLock) {
            enqueueOneShot(context, onlyIfAged = true)
        }

        private val triggerLock = Any()

        private fun enqueueOneShot(context: Context, onlyIfAged: Boolean) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(connected())
                .setInputData(workDataOf(KEY_ONLY_IF_AGED to onlyIfAged))
                .apply { if (onlyIfAged) addTag(AGED_TAG) }
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

            // KEEP: an impatient user tapping five times gets one pass, and a tick behind a
            // pending tap adds nothing. Safe now that a one-shot never lingers in a retry
            // backoff — see the class comment — and a tap first clears an aged pass (syncNow).
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private fun connected() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }
}
