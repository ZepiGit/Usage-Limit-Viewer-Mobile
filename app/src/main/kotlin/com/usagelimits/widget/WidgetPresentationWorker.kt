package com.usagelimits.widget

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.sync.SyncWorker
import kotlinx.coroutines.flow.first

/**
 * Repaints the widgets at the next moment their PRESENTATION changes on its own.
 *
 * The sync worker fetches new data and network-constrains itself accordingly, but two visible
 * transitions do not need the network and were not covered by any event: a cached snapshot
 * crossing its staleness threshold, and a cached reset instant arriving. Neither moves the
 * Room cache, so the data-driven observe flow never re-emits, and a healthy-looking tile
 * stayed healthy-looking for as long as nothing else happened to run — exactly offline, which
 * is when "as of when?" matters most. This worker exists to cross those boundaries: it reads
 * only the local cache, repaints, and schedules the next boundary. No network constraint, no
 * credentials, no polling loop, no exact alarm — WorkManager may defer it, which is why the
 * views also age every timestamp themselves when they do render.
 *
 * A reset boundary is the one case a cache-only repaint cannot get right: the number the
 * provider now reports is not in the cache. This requests the confirming sync before the
 * cache-only publication, through the same aged-cache check the system's widget tick uses.
 * The repaint can show "reset due" while the network request waits, and a slow or failed
 * presentation reservation cannot prevent the sync request.
 */
class WidgetPresentationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        SyncWorker.syncIfCacheAged(applicationContext)
        (applicationContext as UsageLimitsApp).container.publication.presentationChanged()
        return Result.success()
    }

    companion object {
        internal const val WORK_NAME = "widget_presentation_boundary"

        /** Never wake the device more often than this for a presentation boundary. */
        private const val MIN_DELAY_MS = 60_000L

        /** Beyond a day the routine sync will repaint long before anything ages further. */
        private const val MAX_DELAY_MS = 24L * 60 * 60 * 1000

        /**
         * The next instant at which some cached presentation is wrong rather than merely old:
         * the soonest staleness threshold or future reset across the cache. Pure, so the
         * arithmetic is testable without WorkManager or a database.
         */
        fun nextPresentationBoundary(
            fetchedAtMs: List<Long>,
            resetAtMs: List<Long>,
            staleAfterMs: Long,
            nowMs: Long,
        ): Long? = (fetchedAtMs.map { it + staleAfterMs } + resetAtMs).filter { it > nowMs }.minOrNull()

        /**
         * Reserves a repaint for the boundary computed from the publication module's cache.
         * Outside a running boundary, REPLACE moves the pending reservation. While this unique
         * work is RUNNING, APPEND_OR_REPLACE avoids cancelling the worker publishing the repaint.
         * WorkManager's Flow suspends for the state lookup rather than blocking a UI caller.
         *
         * The lookup and enqueue are not atomic with WorkManager starting a worker. Concurrent
         * events can briefly append more than one successor, or replace a worker that just
         * started. The next non-running publication coalesces those reservations. The worker
         * requests its confirming sync first, but a start/replace race can still interrupt it;
         * the periodic sync remains the fallback. Avoiding this requires a different scheduling
         * protocol, not a caller-supplied flag on the two-event publication interface.
         */
        suspend fun scheduleNext(context: Context, boundary: Long?, now: Long) {
            val workManager = WorkManager.getInstance(context)
            val running = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first()
                .any { it.state == WorkInfo.State.RUNNING }
            if (boundary == null) {
                // Nothing cached ages on its own — no accounts, or nothing fetched. The next
                // sync will reschedule this when there is.
                if (!running) workManager.cancelUniqueWork(WORK_NAME).await()
                return
            }

            val delay = (boundary - now).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
            workManager.enqueueUniqueWork(
                WORK_NAME,
                if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetPresentationWorker>()
                    .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build(),
            ).await()
        }
    }
}
