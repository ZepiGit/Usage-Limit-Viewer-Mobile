package com.usagelimits.core.model

/**
 * How long a quota window spans. Derived from the window duration reported by the provider
 * rather than from its position in the payload, because providers reorder and rename windows.
 */
enum class WindowCategory {
    FIVE_HOUR,
    WEEKLY,
    MONTHLY,
    OTHER;

    companion object {
        private const val FIVE_HOURS_SECONDS = 5L * 60 * 60          // 18_000
        private const val WEEK_SECONDS = 7L * 24 * 60 * 60           // 604_800
        private const val MIN_MONTH_SECONDS = 28L * 24 * 60 * 60     // 2_419_200
        private const val MAX_MONTH_SECONDS = 31L * 24 * 60 * 60     // 2_678_400

        /**
         * Classifies a window by its declared duration.
         *
         * Months are a range, not a constant, because calendar months differ in length and
         * providers report the real period. Anything else stays [OTHER] so an unknown future
         * window still renders instead of being forced into the wrong bucket.
         */
        fun fromPeriodSeconds(periodSeconds: Long?): WindowCategory = when {
            periodSeconds == null -> OTHER
            periodSeconds == FIVE_HOURS_SECONDS -> FIVE_HOUR
            periodSeconds == WEEK_SECONDS -> WEEKLY
            periodSeconds in MIN_MONTH_SECONDS..MAX_MONTH_SECONDS -> MONTHLY
            else -> OTHER
        }
    }
}

/** Whether exhausting this window blocks the entire account or only a model/pool. */
enum class UsageScope { GLOBAL, MODEL, UNKNOWN }

data class UsageSegment(val label: String, val usedPercent: Double)

/**
 * One quota bar in the UI, normalised from whatever shape the provider used.
 *
 * [usedPercent] is the sole stored percentage; [remainingPercent] is derived once.
 * Providers normalize explicit percentages or compatible amount/capacity ratios here.
 */
data class UsageWindow(
    val id: String,
    val label: String,
    val category: WindowCategory,
    val usedPercent: Double?,
    val periodSeconds: Long?,
    /** Epoch millis when the window rolls over, if the provider says so. */
    val resetAt: Long?,
    val exhausted: Boolean,
    /** Optional grouping key, e.g. an Antigravity quota group or Codex "code review". */
    val group: String? = null,
    val scope: UsageScope = UsageScope.GLOBAL,
    val segments: List<UsageSegment> = emptyList(),
    /** False for a retained window in a partial result; it is not a new measurement. */
    val measured: Boolean = true,
) {
    val remainingPercent: Double?
        get() {
            val used = usedPercent ?: return null
            if (!used.isFinite() || used !in 0.0..100.0) return null
            return 100.0 - used
        }

    val hasConflictingAvailability: Boolean
        get() = exhausted && (remainingPercent ?: 0.0) > 0.0

    val severity: Severity
        get() = Severity.fromRemainingPercent(remainingPercent, exhausted)

    /**
     * Reset passed after [fetchedAt]: the figure describes a closed window until a fetch confirms.
     * Without a fetch instant there is no proof the reading is newer than the reset, so any
     * passed reset counts, as on iOS.
     */
    fun resetElapsedAt(fetchedAt: Long?, nowMs: Long): Boolean =
        resetAt != null && resetAt <= nowMs && (fetchedAt == null || fetchedAt < resetAt)
}

/**
 * The account headline: the one window that speaks for the account at [nowMs].
 *
 * Windows whose reset is due drop out first — their figure belongs to a window that has ended —
 * unless that leaves none, when all stay so the account still says something. Among what
 * remains, global windows alone compete if there are any: a scoped pool running dry does not
 * block the account. An exhausted window wins, then the lowest remaining percentage; an unknown
 * percentage loses to any known one, because leading with a blank would hide the figure that is
 * known. A tie goes to the first window in provider order.
 *
 * [fetchedAt] is nullable only so the shared vectors can state a reading with no fetch time;
 * every snapshot has one, and [UsageSnapshot.headlineAt] is what callers use.
 * `HeadlineVectorsTest` proves this against the vector file the Swift `headline` runs too.
 */
fun List<UsageWindow>.headlineAt(fetchedAt: Long?, nowMs: Long): UsageWindow? {
    val pool = filterNot { it.resetElapsedAt(fetchedAt, nowMs) }.ifEmpty { this }
    // minByOrNull keeps the earlier element on a tie, which is the documented tie-break.
    return pool.filter { it.scope == UsageScope.GLOBAL }.ifEmpty { pool }.minByOrNull {
        if (it.severity == Severity.EXHAUSTED) -1.0 else it.remainingPercent ?: Double.MAX_VALUE
    }
}

/** Why a snapshot looks the way it does. Drives the "stale"/"failed" banners. */
enum class SnapshotStatus { OK, PARTIAL, FAILED }

/**
 * The result of one usage fetch for one account.
 *
 * [fetchedAt] is what the UI ages ("last updated 37 minutes ago"). A [FAILED] snapshot still
 * carries the previous [windows] so the app can keep showing known-stale numbers rather than
 * blanking the screen.
 */
data class UsageSnapshot(
    val accountId: String,
    val fetchedAt: Long,
    val status: SnapshotStatus,
    val windows: List<UsageWindow>,
    val resetCredits: List<ResetCredit> = emptyList(),
    /** Provider-reported count; authoritative over [resetCredits].size when present. */
    val resetCreditCount: Int? = null,
    /** Of [resetCreditCount], how many apply to the limit currently reached. Null if unstated. */
    val applicableResetCreditCount: Int? = null,
    val errorMessage: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.fromStored(null, status, errorMessage),
    /** Last attempted refresh; failed attempts do not advance [fetchedAt]. */
    val lastAttemptAt: Long = fetchedAt,
) {
    /**
     * How many credits the user holds, spendable or not — the number worth displaying.
     *
     * The provider's count is authoritative when it states one, because the row list can be
     * truncated or filtered while the count stays exact. Falling back to the rows counts only
     * the AVAILABLE ones: a spent credit is still listed, and counting it told a user with one
     * consumed credit and nothing else that they held one to spend.
     */
    val heldResetCredits: Int
        get() = resetCreditCount
            ?: resetCredits.count { it.status.meansAvailable() }

    /**
     * Credits that are available AND not past their own expiry at [nowMs].
     *
     * A snapshot is a photograph. A credit that read "available, expires at 14:00" is still
     * in the photograph at 15:00, and counting it told the user — and the standing
     * notification — that they held a credit to spend an hour after it lapsed. Only rows can
     * be checked this way; a provider count is authoritative as of the fetch and is left
     * alone, because subtracting from it using a row list that may be truncated invents a
     * number.
     */
    fun spendableResetCreditsAt(nowMs: Long): Int {
        applicableResetCreditCount?.let { return it }
        if (resetCreditCount != null && resetCredits.isEmpty()) return resetCreditCount
        return resetCredits.count {
            it.status.meansAvailable() && (it.expiresAt == null || it.expiresAt > nowMs)
        }
    }

    /**
     * How many credits can be spent; gates redemption.
     * Uses the held count when the provider reports no separate applicable count.
     */
    val spendableResetCredits: Int
        get() = spendableResetCreditsAt(System.currentTimeMillis())

    /** The account headline at [nowMs], judged against this snapshot's own [fetchedAt]. */
    fun headlineAt(nowMs: Long): UsageWindow? = windows.headlineAt(fetchedAt, nowMs)

    val restrictedWindows: List<UsageWindow>
        get() = windows.filter { it.scope != UsageScope.GLOBAL && it.severity == Severity.EXHAUSTED }

    /** The next rollover across all windows, used by the Resets screen and the widgets. */
    val nextReset: Long?
        get() = windows.mapNotNull { it.resetAt }.minOrNull()

    /**
     * Severity ignoring age. Prefer [severityAt] wherever a clock is available.
     */
    val severity: Severity
        get() = if (status == SnapshotStatus.FAILED) Severity.ERROR else quotaSeverity

    /**
     * What the reported windows say by the scope rule, whatever became of the latest read: a
     * model or pool running dry is [Severity.LIMITED], not the whole account [Severity.EXHAUSTED],
     * unless every known pool is dry. [severity] is this unless the read failed.
     */
    val quotaSeverity: Severity
        get() {
            val global = windows.filter { it.scope == UsageScope.GLOBAL }
            if (global.any { it.severity == Severity.EXHAUSTED }) return Severity.EXHAUSTED
            if (restrictedWindows.isNotEmpty()) {
                // Complete exhaustion is defensible only when every reported pool is known,
                // model-specific and exhausted. Unknown scope/data never proves a global block.
                return if (windows.all { it.scope == UsageScope.MODEL } &&
                    windows.groupBy { it.group ?: it.id }.values.all { pool -> pool.any { it.severity == Severity.EXHAUSTED } }) {
                    Severity.EXHAUSTED
                } else Severity.LIMITED
            }
            return (global.ifEmpty { windows }).map { it.severity }
                .filter { it != Severity.UNKNOWN }.maxOrNull() ?: Severity.UNKNOWN
        }

    /**
     * Severity including staleness.
     *
     * Age has to be part of the verdict. Without it, a snapshot that stopped refreshing —
     * doze, no network, an OEM battery manager — keeps whatever pill it had when it last
     * succeeded, so day-old numbers still read "Healthy". Showing a confidently green status
     * over stale data is the exact failure this app exists to prevent.
     */
    fun severityAt(nowMs: Long, staleAfterMs: Long = Severity.STALE_AFTER_MS): Severity {
        // A window whose reset passed since the fetch describes a closed window ("Reset due"),
        // so it no longer decides the verdict; with none left the answer is unknown, not old.
        val base = copy(windows = windows.filterNot { it.resetElapsedAt(fetchedAt, nowMs) }).severity
        if (base == Severity.ERROR) return base
        val age = nowMs - fetchedAt
        return if (age >= staleAfterMs) Severity.STALE else base
    }

    fun isStaleAt(nowMs: Long, staleAfterMs: Long = Severity.STALE_AFTER_MS): Boolean =
        nowMs - fetchedAt >= staleAfterMs
}

/**
 * A Codex rate-limit reset credit.
 *
 * Only credits the provider reports as available *and* of the Codex rate-limit type are
 * modelled; see CodexUsageParser.parseResetCredits for the filtering rules.
 */
/// Whether a provider's status string means the credit can be spent.
///
/// `lowercase()` and not `equals(ignoreCase = true)`, which is not the same question. The
/// JVM's case-insensitive comparison folds a dotless `\u0131` onto `i`, so a provider sending
/// "ava\u0131lable" counted as available here and did not on iOS, where `lowercased()` leaves
/// it alone. Both are now the stricter reading — a string that is not "available" in any case
/// is not available — and, more to the point, both are the SAME reading.
internal fun String.meansAvailable(): Boolean = trim().lowercase() == "available"

data class ResetCredit(
    val id: String,
    val grantedAt: Long?,
    val expiresAt: Long?,
    val status: String,
)

