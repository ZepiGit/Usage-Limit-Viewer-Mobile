package com.usagelimits.statusicon

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.IconCompat
import com.usagelimits.R
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
import com.usagelimits.ui.IconStyle
import com.usagelimits.ui.ProviderIconCatalog
import com.usagelimits.ui.ProviderIconChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
 *   boundary does not bring back an icon the person swiped away.
 *
 * "The last time" is [ShownSelection], the list of accounts the previous publication meant to
 * show. It is not a record of dismissals: nothing listens for a swipe.
 *
 * This holds for publications that run to their end. The spec's "Limits of the dismissal rule"
 * name what it does not promise: a process that dies between storing the selection and posting
 * leaves those icons missing until the next sync; a selection that was not stored can let a
 * repaint bring a dismissed icon back early; a permission revoked and granted again with no
 * publication in between may wait for the next sync.
 *
 * **Order.** A sort key does not order separate groups. Within one importance section Android
 * ranks them by ranking time, newest first, and the status bar draws its icons in that order from
 * the start edge. The ranking time is the notification's own `when` only if the platform takes
 * it as set by the app: not in the future, and, on Android 16 with `sortSectionByTime`, not equal
 * to the notification's `creationTime`, which the platform constructor stamps with the clock of
 * the moment the notification is built. Each publication therefore posts the accounts in reverse
 * account order and gives each account a real clock reading that is later than the previous
 * account's and earlier than the moment its own notifications are built ([readingBeforeBuild]),
 * so the first account is the newest and leads. A reorder in the app takes effect with the next
 * publication. See the observed order and exceptions in
 * `docs/research/statusbar-icons-platform-facts.md`.
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
    /**
     * Waits a moment for [now] to move on; called only while it still shows the same millisecond.
     * The app suspends for a millisecond of the wall clock on [Dispatchers.Default], because a
     * caller's own dispatcher may keep time differently (a paused main looper in a test does).
     */
    private val nextTick: suspend () -> Unit = { withContext(Dispatchers.Default) { delay(1) } },
    /** A clock that never jumps, to bound how long the order may be waited for. */
    private val monotonic: () -> Long = SystemClock::elapsedRealtime,
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
        // so a publication after the permission is given counts every pinned account as new. If
        // no publication ran while it was missing, restoration may wait for the next sync.
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
        var previous: Long? = null
        var ordered = true
        for (id in posting.asReversed()) {
            // Once the clock jumped back or stood still for too long, this publication posts the
            // rest without the order guarantee rather than hold the lock and every caller behind it.
            val reading = if (ordered) readingBeforeBuild(previous) else null
            if (reading == null) ordered = false
            val postedAt = reading ?: now()
            val usage = byId.getValue(id)
            val showAccountName = pinned.any { it != id && byId.getValue(it).account.provider == usage.account.provider }
            post(usage, settings, texts, nowMs, postedAt, showAccountName)
            previous = postedAt
        }
    }

    /**
     * A real clock reading for one account's `when`, later than [previous] (the account posted
     * before it) and earlier than the clock at which this account's notifications are then built;
     * null when that cannot be had.
     *
     * Later than [previous], because two equal times would tie and leave the order to Android.
     * Earlier than the build, because on Android 16 with `sortSectionByTime` a `when` equal to the
     * notification's `creationTime` (the build's clock reading) counts as not set by the app and
     * is ignored for ranking. Both by waiting for the clock to move on ([nextTick]), never by
     * inventing a time.
     *
     * That holds for a normal clock, one that does not jump while the reading is taken and the
     * notifications are built. The wait suspends the publication (it keeps the publisher's lock,
     * not a thread) until the clock shows the next millisecond; nominally about two milliseconds
     * per account, with no upper bound from the scheduler. It gives up, and returns null, when
     * the clock moves backwards (an earlier reading is not progress, and waiting for the old time
     * could take as long as the jump) or has not moved within [CLOCK_WAIT_LIMIT_MS] on the
     * [monotonic] clock. A clock that jumps after the last check cannot be ruled out.
     */
    private suspend fun readingBeforeBuild(previous: Long?): Long? {
        val deadline = monotonic() + CLOCK_WAIT_LIMIT_MS
        var reading = now()
        while (previous != null && reading <= previous) {
            if (reading < previous || monotonic() >= deadline) return null
            nextTick()
            reading = now()
        }
        while (true) {
            val later = now()
            if (later > reading) return reading
            if (later < reading || monotonic() >= deadline) return null
            nextTick()
        }
    }

    private fun post(usage: AccountUsage, settings: AppSettings, texts: StatusIconTexts, nowMs: Long, postedAt: Long, showAccountName: Boolean) {
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
        val title = when {
            needsReauthentication -> texts.reconnectRequired()
            icon.content == StatusIconContent.Error -> texts.refreshFailed()
            else -> texts.reading(icon, presentation ?: WindowPresentation.absent(settings.percentDisplay))
        }
        val details = texts.windowDetails(headline, nowMs)
        val subText = texts.subText(usage.account.provider, if (showAccountName) usage.account.label else null)
        val bitmap = StatusIconRenderer.render(usage.account.provider, icon, context.resources.displayMetrics.density)
        val choice = ProviderIconCatalog.selected(usage.account.provider, settings.providerIcons[usage.account.provider.id])
        val artwork = largeIcon(choice)
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
                .setContentTitle(title)
                .setContentText(details)
                .setSubText(subText)
                .setLargeIcon(artwork)
                .setContentIntent(open)
                .setGroup(tag)
                .setGroupSummary(summary)
                .setSilent(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setShowWhen(false)
                // Real, and the account's place in the order: see the class comment.
                .setWhen(postedAt)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            try {
                manager.notify(tag, id, notification)
            } catch (_: SecurityException) {
                // Permission revoked since the check; Settings will say so.
                return
            }
        }
    }

    private fun largeIcon(choice: ProviderIconChoice): Icon {
        if (!choice.whiteInk) {
            return Icon.createWithResource(context, choice.backedShadeArtwork ?: choice.drawable)
        }

        val ground = checkNotNull(context.getDrawable(R.drawable.status_icon_artwork_ground))
        val mark = checkNotNull(context.getDrawable(choice.drawable))
        // Android's standard right-icon slot is 48dp; a larger bitmap gets rescaled at build time.
        val size = (ARTWORK_DP * context.resources.displayMetrics.density).roundToInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ground.setBounds(0, 0, size, size)
        ground.draw(canvas)
        // Wordmark PNGs carry roughly half their width as transparent side padding.
        val markSpan = if (choice.style == IconStyle.WORDMARK) size * 1.5f else size * 0.75f
        val scale = markSpan / maxOf(mark.intrinsicWidth, mark.intrinsicHeight)
        canvas.translate((size - mark.intrinsicWidth * scale) / 2, (size - mark.intrinsicHeight * scale) / 2)
        canvas.scale(scale, scale)
        mark.setBounds(0, 0, mark.intrinsicWidth, mark.intrinsicHeight)
        mark.draw(canvas)
        return Icon.createWithBitmap(bitmap)
    }

    private fun ensureChannel(texts: StatusIconTexts) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, texts.channelName(), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = texts.channelDescription()
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
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

        /**
         * How long one account's time may wait for the clock to move on before the publication
         * gives up the order guarantee. A normal clock needs about two milliseconds.
         */
        internal const val CLOCK_WAIT_LIMIT_MS = 20L

        /** The tag names the account; the two ids name the records inside its group. */
        private const val SUMMARY_ID = 2001
        private const val CHILD_ID = 2002
        private const val ARTWORK_DP = 48

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
