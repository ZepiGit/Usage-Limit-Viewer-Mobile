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
import androidx.core.content.edit
import androidx.core.graphics.drawable.IconCompat
import com.usagelimits.UsageLimitsActivity
import com.usagelimits.core.database.AccountUsage
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * **What a publication posts.** Every publication cancels the group of an account that is no
 * longer pinned. Which pinned accounts it posts depends on why it runs, because a person can
 * swipe an icon away and Android gives the app no way to tell that apart from a reboot or from
 * the permission being withdrawn; all three leave the group missing from the active list:
 * - after a sync ([restoreDismissed]) every pinned account is posted, so a swiped icon returns
 *   with the next sync, and so do icons lost to a reboot;
 * - otherwise only groups still showing are updated, plus accounts that were not pinned the last
 *   time a publication ran. Turning the feature on, pinning an account, or unpinning and pinning
 *   it again therefore shows it; pinning another account, a display-mode change or a time
 *   boundary never brings back an icon the person swiped away.
 *
 * "The last time" is [ShownSelection], the list of accounts the previous publication meant to
 * show. It is not a record of dismissals: nothing listens for a swipe.
 *
 * **Order.** A sort key does not order separate groups. Within one importance section Android
 * ranks them by ranking time, newest first, and the status bar draws its icons in that order from
 * the start edge. A notification's own `when` is its ranking time when it is not in the future,
 * also on an update. Each publication therefore posts the accounts in reverse account order, each
 * with a real clock reading later than the one before, so the first account is the newest and
 * leads. A reorder in the app takes effect with the next publication. Whether a given device
 * ranks this way is ticket 04's evidence; the cited sources are in its report.
 */
class StatusIconPublisher(
    private val context: Context,
    settingsStore: SettingsStore,
    /** The cached accounts in the app's order; the repository's one-shot read in the app. */
    private val readAccounts: suspend () -> List<AccountUsage>,
    private val now: () -> Long = System::currentTimeMillis,
    /** The current settings; one read of the store in the app. */
    private val readSettings: suspend () -> AppSettings = { settingsStore.settings.first() },
    private val shown: ShownSelection = PreferencesShownSelection(context),
) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    /**
     * One publication at a time, from its reads through its last post.
     *
     * A sync in the background and a switch in Settings publish independently. Without this, a
     * publication that read "on, Claude" and then waited on the cache could finish after the
     * person switched Off, and post Claude again over the cleanup the newer publication did.
     * Serialised, the later one always reads after the earlier one has written, so the records
     * end up matching the latest choice.
     */
    private val serial = Mutex()

    /** [restoreDismissed] is true after a sync and false for every other publication. */
    suspend fun publish(restoreDismissed: Boolean): Unit = serial.withLock {
        // Missing permission is Settings' to explain; posting would only throw. Nothing is shown,
        // so once the permission is given every pinned account counts as new and appears.
        if (!hasPermission()) {
            shown.write(emptyList())
            return@withLock
        }
        val settings = readSettings()
        val accounts = readAccounts()
        val pinned = pinnedAccountIds(settings.statusIconsEnabled, settings.pinnedAccountIds, accounts.map { it.account.localId })
        val previouslyShown = shown.read().toSet()

        val wanted = pinned.map(::tagFor).toSet()
        val active = manager.activeNotifications.filter { it.notification.channelId == CHANNEL_ID }
        active.filter { it.tag !in wanted }.forEach { manager.cancel(it.tag, it.id) }
        val showing = active.map { it.tag }.toSet()
        shown.write(pinned)

        val posting = pinned.filter { restoreDismissed || it !in previouslyShown || tagFor(it) in showing }
        if (posting.isEmpty()) return@withLock

        val texts = ResourceStatusIconTexts(context.resources)
        ensureChannel(texts)
        val nowMs = now()
        val byId = accounts.associateBy { it.account.localId }
        var postedAt: Long? = null
        for (id in posting.asReversed()) {
            postedAt = laterThan(postedAt)
            post(byId.getValue(id), settings, texts, nowMs, postedAt)
        }
    }

    /**
     * A real clock reading later than [previous]: two posts in the same millisecond would tie,
     * and their order would be Android's to choose. Waits out the rest of the millisecond rather
     * than inventing a time. A clock set back since [previous] is taken as it is.
     */
    private suspend fun laterThan(previous: Long?): Long {
        var time = now()
        while (time == previous) {
            delay(1)
            time = now()
        }
        return time
    }

    private fun post(usage: AccountUsage, settings: AppSettings, texts: StatusIconTexts, nowMs: Long, postedAt: Long) {
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
                // Real, and the account's place in the order: see the class comment.
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

/**
 * The pinned accounts the previous status publication meant to show, in order.
 *
 * Kept outside the notifications because they do not survive a reboot or a swipe, and the
 * publisher has to know whether an account is newly pinned or merely missing from the active
 * list. Survives a restart, so a reboot is not mistaken for every account being newly pinned.
 */
interface ShownSelection {
    fun read(): List<String>
    fun write(accountIds: List<String>)
}

/** [ShownSelection] in the app's private preferences. */
internal class PreferencesShownSelection(context: Context) : ShownSelection {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): List<String> =
        prefs.getString(KEY, null).orEmpty().split(SEPARATOR).filter { it.isNotBlank() }

    override fun write(accountIds: List<String>) {
        prefs.edit { putString(KEY, accountIds.joinToString(SEPARATOR)) }
    }

    private companion object {
        const val PREFS = "usage_limits_status_icons"
        const val KEY = "shown_account_ids"
        /** Account ids are UUIDs and never contain a line break. */
        const val SEPARATOR = "\n"
    }
}
