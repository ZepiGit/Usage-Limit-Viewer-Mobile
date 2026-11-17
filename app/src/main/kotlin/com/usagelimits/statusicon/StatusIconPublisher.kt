package com.usagelimits.statusicon

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.usagelimits.UsageLimitsActivity
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.ReadingValidity
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.StatusIconContent
import com.usagelimits.core.model.WindowPresentation
import com.usagelimits.core.model.pinnedAccountIds
import com.usagelimits.core.model.presentation
import com.usagelimits.core.model.readingValidity
import com.usagelimits.core.model.statusIcon
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Keeps one status notification group per pinned account in step with the cache and the settings.
 *
 * Each pinned account gets a group of its own holding one summary and one child, both carrying
 * the status icon as their small icon. That shape is what the prototype found keeps separate icons
 * on Android 16: single notifications with distinct group keys and no summary are regrouped into
 * one generated stack after a few seconds, a shared summary shows one icon for all
 * (`docs/research/statusbar-android16-grouping-exemptions.md` on `prototype/statusbar-icons`).
 * Both records sit at stable identities derived from the account, so a later publication replaces
 * them in place, and only-alert-once keeps a changing figure silent.
 *
 * Every publication posts the pinned accounts and cancels the group of any account that is no
 * longer pinned, so switching the feature off, clearing the choice or choosing another account
 * removes the previous icon at once.
 */
class StatusIconPublisher(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val repository: UsageRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    suspend fun publish() {
        // Missing permission is Settings' to explain; posting would only throw.
        if (!hasPermission()) return
        val settings = settingsStore.settings.first()
        val accounts = repository.accountUsageOnce()
        val pinned = pinnedAccountIds(settings.statusIconsEnabled, settings.pinnedAccountIds, accounts.map { it.account.localId })

        val wanted = pinned.map(::tagFor).toSet()
        manager.activeNotifications
            .filter { it.notification.channelId == CHANNEL_ID && it.tag !in wanted }
            .forEach { manager.cancel(it.tag, it.id) }
        if (pinned.isEmpty()) return

        val texts = ResourceStatusIconTexts(context.resources)
        ensureChannel(texts)
        val nowMs = now()
        for (usage in accounts.filter { it.account.localId in pinned }) post(usage, settings, texts, nowMs)
    }

    private fun post(usage: AccountUsage, settings: AppSettings, texts: StatusIconTexts, nowMs: Long) {
        val snapshot = usage.snapshot
        val headline = snapshot?.headlineAt(nowMs)
        val needsReauthentication = snapshot?.connectionStatus == ConnectionStatus.RECONNECT_REQUIRED
        val validity = when {
            snapshot == null -> ReadingValidity.CURRENT
            // A failed sync keeps its old windows; the icon must not repeat their number as current.
            snapshot.status == SnapshotStatus.FAILED -> ReadingValidity.ERROR
            headline == null -> ReadingValidity.CURRENT
            else -> headline.readingValidity(
                stale = snapshot.isStaleAt(nowMs, Severity.staleAfterMs(settings.syncIntervalMinutes)),
                fetchedAt = snapshot.fetchedAt,
                nowMs = nowMs,
            )
        }
        val presentation = headline?.presentation(validity, settings.percentDisplay)
        val icon = statusIcon(presentation, validity, needsReauthentication)
        val line = when {
            needsReauthentication -> texts.reconnectRequired()
            icon.content == StatusIconContent.Error -> texts.refreshFailed()
            else -> texts.reading(icon.content, headline, presentation ?: WindowPresentation.absent(settings.percentDisplay), nowMs)
        }
        val bitmap = StatusIconRenderer.render(usage.account.provider, icon, context.resources.displayMetrics.density)
        val tag = tagFor(usage.account.localId)
        val open = PendingIntent.getActivity(
            context,
            usage.account.localId.hashCode(),
            Intent(context, UsageLimitsActivity::class.java)
                .putExtra("accountId", usage.account.localId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // An update keeps the time the record was first posted: Android 16 rejects a post whose
        // time is long past, and a fresh time on every sync would only reshuffle the shade.
        val postedAt = manager.activeNotifications.firstOrNull { it.tag == tag }?.notification?.`when` ?: nowMs
        for ((id, summary) in listOf(SUMMARY_ID to true, CHILD_ID to false)) {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(IconCompat.createWithBitmap(bitmap))
                .setContentTitle(usage.account.label)
                .setContentText(line)
                .setContentIntent(open)
                .setGroup(tag)
                .setGroupSummary(summary)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setSilent(true)
                .setShowWhen(false)
                .setWhen(postedAt)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            try {
                manager.notify(tag, id, notification)
            } catch (_: SecurityException) {
                // Permission revoked since the check; Settings will say so.
                return
            }
        }
    }

    private fun ensureChannel(texts: StatusIconTexts) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, texts.channelName(), NotificationManager.IMPORTANCE_LOW).apply {
                description = texts.channelDescription()
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Separate from the alert channel, so a person can silence one without the other. */
        const val CHANNEL_ID = "status_icons"

        /** The tag names the account; the two ids name the records inside its group. */
        private const val SUMMARY_ID = 2001
        private const val CHILD_ID = 2002

        private fun tagFor(accountId: String) = "status-icon:$accountId"
    }
}
