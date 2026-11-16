package com.usagelimits.core.sync

import android.content.Context
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.widget.WidgetPresentationWorker
import com.usagelimits.widget.WidgetUpdater

/**
 * The two events that change what the app publishes outside its own screens.
 *
 * [cacheMoved] follows a sync, including an account's first reading and a failed attempt that
 * retained cached readings. It repaints out-of-app surfaces, schedules their next presentation
 * boundary, then evaluates and publishes alerts. The notification ledger deduplicates edges
 * when foreground and background syncs observe the same reading.
 *
 * [presentationChanged] follows settings, ordering, removal, configuration or a time boundary.
 * It repaints and reschedules without evaluating or claiming alerts: changing how a reading
 * looks is not a new reading. Neither event requests data from a provider.
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
    private val context: Context,
    private val repository: UsageRepository,
    private val notifications: NotificationPublisher,
) : Publication {
    override suspend fun cacheMoved() {
        presentationChanged()
        notifications.publishFor(repository.accountUsageOnce())
    }

    override suspend fun presentationChanged() {
        WidgetUpdater.refreshAll(context)
        WidgetPresentationWorker.scheduleNext(context)
    }
}
