package com.usagelimits.feature.overview

import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.database.inOverviewOrder
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.ReadingValidity
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.readingValidity
import java.time.Instant
import java.time.ZoneId

/** The Overview filter: one at a time, never stored, All whenever Overview is left. */
enum class OverviewFilter { ALL, NEEDS_ATTENTION, RESETS_TODAY }

/**
 * What the header may claim. [ATTENTION] counts expired sign-ins; [ROOM] says every account has
 * room; [RESET_ONLY] and [NONE] make no claim, the first with a next reset, the second without.
 */
enum class OverviewHeaderKind { ATTENTION, ROOM, RESET_ONLY, NONE }

data class OverviewHeader(val kind: OverviewHeaderKind, val resetAt: Long?)

/** One visible account and the id of the window that speaks for it, the one its row emphasises. */
data class OverviewEntry(val usage: AccountUsage, val headlineWindowId: String?)

data class OverviewProjection(
    val entries: List<OverviewEntry>,
    /** Expired sign-ins across every usage account, whatever the filter shows. */
    val attentionCount: Int,
    val header: OverviewHeader,
)

/**
 * The Overview list, its filter and its header at [nowMs] in [zone].
 *
 * The rank is 01a's (ADR 0004) and comes first; a filter only drops rows from it. Needs attention
 * keeps expired sign-ins and nothing else: low or exhausted allowance is not a sign-in problem.
 * Resets today keeps an account with any recorded reset strictly after now and strictly before
 * the next local day starts in [zone], whatever its connection, validity or window scope.
 *
 * The header speaks for all accounts. Its reset is the earliest recorded future reset of an
 * account whose sign-in is known to be valid ([ConnectionStatus.CONNECTED]), even beyond today.
 * "Room" needs a nonempty register in which every headline is current, known, not exhausted and
 * above [Severity.MEDIUM_ABOVE]; a usable reading under a rate-limit pause still counts, because
 * the pause is a message, not a validity.
 *
 * `OverviewFiltersHeaderVectorsTest` proves this against
 * `shared/test-vectors/overview-filters-header.json`, which the Swift suite reads too.
 */
fun List<AccountUsage>.overviewAt(
    filter: OverviewFilter,
    nowMs: Long,
    zone: ZoneId,
    staleAfterMs: Long,
): OverviewProjection {
    val dayEnd = nextLocalDayStart(nowMs, zone)
    var attentionCount = 0
    var everyoneHasRoom = isNotEmpty()
    var nextReset: Long? = null
    val entries = ArrayList<OverviewEntry>(size)
    for (usage in inOverviewOrder(nowMs)) {
        val snapshot = usage.snapshot
        val expired = snapshot?.connectionStatus == ConnectionStatus.RECONNECT_REQUIRED
        if (expired) attentionCount++
        val headline = snapshot?.headlineAt(nowMs)
        if (everyoneHasRoom) everyoneHasRoom = !expired && headline != null &&
            headline.severity != Severity.EXHAUSTED &&
            (headline.remainingPercent ?: 0.0) > Severity.MEDIUM_ABOVE &&
            usage.validityOf(headline, nowMs, staleAfterMs) == ReadingValidity.CURRENT
        var resetsToday = false
        for (window in snapshot?.windows.orEmpty()) {
            val reset = window.resetAt?.takeIf { it > nowMs } ?: continue
            if (reset < dayEnd) resetsToday = true
            if (snapshot?.connectionStatus == ConnectionStatus.CONNECTED && (nextReset == null || reset < nextReset)) {
                nextReset = reset
            }
        }
        val shown = when (filter) {
            OverviewFilter.ALL -> true
            OverviewFilter.NEEDS_ATTENTION -> expired
            OverviewFilter.RESETS_TODAY -> resetsToday
        }
        if (shown) entries += OverviewEntry(usage, headline?.id)
    }
    val kind = when {
        attentionCount > 0 -> OverviewHeaderKind.ATTENTION
        everyoneHasRoom -> OverviewHeaderKind.ROOM
        nextReset != null -> OverviewHeaderKind.RESET_ONLY
        else -> OverviewHeaderKind.NONE
    }
    return OverviewProjection(entries, attentionCount, OverviewHeader(kind, nextReset))
}

/**
 * The next instant at which Overview can change without a new reading: a reset falling due, a
 * usage or credit reading turning stale, or the next local day starting. Overview wakes then,
 * not at its next countdown tick.
 */
fun List<AccountUsage>.nextOverviewBoundary(nowMs: Long, zone: ZoneId, staleAfterMs: Long, credits: List<CreditEntry>): Long {
    var next = nextLocalDayStart(nowMs, zone)
    for (usage in this) {
        val snapshot = usage.snapshot ?: continue
        val stale = snapshot.fetchedAt + staleAfterMs
        if (stale in (nowMs + 1) until next) next = stale
        for (window in snapshot.windows) {
            val reset = window.resetAt ?: continue
            if (reset in (nowMs + 1) until next) next = reset
        }
    }
    for (entry in credits) {
        val snapshot = entry.snapshot ?: continue
        if (snapshot.balances.isEmpty()) continue
        val stale = snapshot.fetchedAt + staleAfterMs
        if (stale > nowMs && stale < next) next = stale
    }
    return next
}

/**
 * How a row reads [window] at [nowMs]: a stale or failed account is stale as a whole, and a window
 * whose reset passed since the reading is reset due. The same rule the account rows draw by.
 */
internal fun AccountUsage.validityOf(window: UsageWindow, nowMs: Long, staleAfterMs: Long): ReadingValidity {
    val severity = snapshot?.severityAt(nowMs, staleAfterMs) ?: Severity.STALE
    return window.readingValidity(severity == Severity.STALE || severity == Severity.ERROR, snapshot?.fetchedAt, nowMs)
}

/** The first instant of the next local day; where DST skips midnight, the first one that exists. */
internal fun nextLocalDayStart(nowMs: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
