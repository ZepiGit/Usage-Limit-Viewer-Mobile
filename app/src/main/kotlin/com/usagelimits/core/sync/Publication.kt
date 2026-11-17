package com.usagelimits.core.sync

import android.content.Context
import android.util.Log
import com.usagelimits.core.database.CreditRepository
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.model.Severity
import com.usagelimits.core.settings.SettingsStore
import com.usagelimits.statusicon.StatusIconPublisher
import com.usagelimits.widget.WidgetPresentationWorker
import com.usagelimits.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The two events that change what the app publishes outside its own screens.
 *
 * [cacheMoved] follows a sync, including an account's first reading and a failed attempt that
 * retained cached readings. It repaints out-of-app surfaces, schedules their next presentation
 * boundary, repaints the status icons of pinned accounts, then evaluates and publishes alerts. The
 * notification ledger deduplicates edges when foreground and background syncs observe the same
 * reading.
 *
 * [presentationChanged] follows settings, ordering, removal, configuration or a time boundary.
 * It repaints and reschedules without evaluating or claiming alerts: changing how a reading
 * looks is not a new reading. Neither event requests data from a provider.
 *
 * The change has already happened when either event is called. Each publication effect is
 * best-effort: an ordinary failure is logged without failing that change or skipping another
 * effect. Cancellation still propagates to stop work that no longer belongs to the caller.
 *
 * Callers name only the event. A new out-of-app surface belongs in this module, not at every
 * call site. The application container supplies the adapter, so tests can substitute it at
 * the same interface callers use.
 */
interface Publication {
    suspend fun cacheMoved()
    suspend fun presentationChanged()
}

internal class OutOfAppPublication(
    private val repaint: suspend () -> Unit,
    private val scheduleBoundary: suspend () -> Unit,
    /** Told whether the cache moved: only a sync restores status icons a person swiped away. */
    private val publishStatusIcons: suspend (cacheMoved: Boolean) -> Unit,
    private val publishAlerts: suspend () -> Unit,
) : Publication {
    constructor(
        context: Context,
        repository: UsageRepository,
        credits: CreditRepository,
        settings: SettingsStore,
        notifications: NotificationPublisher,
        statusIcons: StatusIconPublisher,
        scheduleBoundary: suspend () -> Unit = {
            val accounts = repository.accountUsageOnce()
            val balances = credits.entriesOnce()
            val interval = settings.settings.first().syncIntervalMinutes
            val now = System.currentTimeMillis()
            WidgetPresentationWorker.scheduleNext(context, WidgetPresentationWorker.nextPresentationBoundary(
                fetchedAtMs = accounts.mapNotNull { it.snapshot?.fetchedAt } + balances.mapNotNull { it.snapshot?.fetchedAt },
                resetAtMs = accounts.flatMap { it.snapshot?.windows.orEmpty().mapNotNull { window -> window.resetAt } },
                staleAfterMs = Severity.staleAfterMs(interval),
                nowMs = now,
            ), now)
        },
    ) : this(
        repaint = { WidgetUpdater.refreshAll(context, settings) },
        scheduleBoundary = scheduleBoundary,
        publishStatusIcons = { cacheMoved -> statusIcons.publish(restoreDismissed = cacheMoved) },
        publishAlerts = { notifications.publishFor(repository.accountUsageOnce()) },
    )

    override suspend fun cacheMoved() {
        publishPresentation(cacheMoved = true)
        bestEffort("alerts", publishAlerts)
    }

    override suspend fun presentationChanged() = publishPresentation(cacheMoved = false)

    private suspend fun publishPresentation(cacheMoved: Boolean) {
        bestEffort("repaint", repaint)
        bestEffort("presentation boundary", scheduleBoundary)
        bestEffort("status icons") { publishStatusIcons(cacheMoved) }
    }

    private suspend fun bestEffort(step: String, publish: suspend () -> Unit) {
        try {
            publish()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("Publication", "$step publication failed", error)
        }
    }
}
