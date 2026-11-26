package com.usagelimits.providers.minimax

import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.time.Instants
import com.usagelimits.providers.StableWindowIds
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.min

/**
 * Reads the MiniMax Token Plan's `model_remains[]`, one five-hour and one weekly window per model.
 *
 * The ambiguity this parser exists for is in `*_usage_count`: older responses state a count that
 * REMAINS, newer ones state a count that was CONSUMED, and the field is spelled the same either
 * way. MiniMax's own CLI resolves it with `resolveQuotaCounts` (MiniMax-AI/cli, commit
 * ae29aae57b05c2140c7ea6aba0a852763ffe7859, `src/utils/quota.ts`), which is reproduced here
 * rather than approximated: the reported count is read as remaining unless a
 * `*_remaining_percent` is present and agrees better with the *consumed* reading, in which case
 * the count is inverted. A percentage that agrees with neither reading within one point makes the
 * window unknown instead of guessing.
 *
 * `status` follows the CLI's own comment in `src/types/api.ts`: 1 limited (the counts decide),
 * 2 exhausted (100 % used), 3 unlimited (no bar). An unlimited plan states no weekly limit either.
 */
object MiniMaxUsageParser {

    private const val FIVE_HOURS_SECONDS = 5L * 3_600
    private const val WEEK_SECONDS = 7L * 86_400

    private const val STATUS_EXHAUSTED = 2L
    private const val STATUS_UNLIMITED = 3L

    /** The CLI's `PERCENT_MATCH_TOLERANCE`: one percentage point of rounding. */
    private const val PERCENT_MATCH_TOLERANCE = 1.0

    fun parse(payload: JsonObject): List<UsageWindow> {
        val ids = StableWindowIds()
        return JsonSupport.array(payload, "model_remains").flatMap { element ->
            val entry = element as? JsonObject ?: return@flatMap emptyList()
            // Without a model name there is no stable id to file the window under.
            val name = JsonSupport.string(entry, "model_name") ?: return@flatMap emptyList()
            modelWindows(entry, name, ids)
        }
    }

    private fun modelWindows(entry: JsonObject, name: String, ids: StableWindowIds): List<UsageWindow> {
        val fiveHour = window(
            id = ids.next("$name:5h"),
            label = "$name · 5-hour",
            category = WindowCategory.FIVE_HOUR,
            periodSeconds = FIVE_HOURS_SECONDS,
            usedPercent = usedPercent(
                status = JsonSupport.long(entry, "current_interval_status", "status"),
                reported = JsonSupport.double(entry, "current_interval_usage_count"),
                total = JsonSupport.double(entry, "current_interval_total_count"),
                remainingPercent = JsonSupport.double(entry, "current_interval_remaining_percent"),
            ),
            resetAt = resetAt(entry, "end_time"),
        )

        val weeklyStatus = JsonSupport.long(entry, "current_weekly_status", "status")
        val weeklyTotal = JsonSupport.double(entry, "current_weekly_total_count")
        // A plan without a weekly allowance states a zero total, and an unlimited one says so in
        // the status; neither is a 0 % window.
        val weekly = if (weeklyStatus == STATUS_UNLIMITED || weeklyTotal == null || weeklyTotal <= 0.0) {
            null
        } else {
            window(
                id = ids.next("$name:weekly"),
                label = "$name · Weekly",
                category = WindowCategory.WEEKLY,
                periodSeconds = WEEK_SECONDS,
                usedPercent = usedPercent(
                    status = weeklyStatus,
                    reported = JsonSupport.double(entry, "current_weekly_usage_count"),
                    total = weeklyTotal,
                    remainingPercent = JsonSupport.double(entry, "current_weekly_remaining_percent"),
                ),
                resetAt = resetAt(entry, "weekly_end_time"),
            )
        }

        return listOfNotNull(fiveHour, weekly)
    }

    /** The provider's own verdict on a window, or the counts where it states none. */
    private fun usedPercent(status: Long?, reported: Double?, total: Double?, remainingPercent: Double?): Double? =
        when (status) {
            STATUS_EXHAUSTED -> 100.0
            STATUS_UNLIMITED -> null
            else -> resolveUsedPercent(reported, total, remainingPercent)
        }

    /**
     * Used share from the CLI's `resolveQuotaCounts`, then `used = total - remaining`.
     *
     * A zero or unusable total, a negative count, a count above the total, or a percentage that
     * matches neither reading leaves the window unknown: the server stated something this parser
     * cannot reconcile, and a bar drawn from a guess is worse than a dash.
     */
    private fun resolveUsedPercent(reported: Double?, total: Double?, remainingPercent: Double?): Double? {
        if (reported == null || total == null) return null
        if (!reported.isFinite() || !total.isFinite()) return null
        if (total <= 0.0 || reported < 0.0 || reported > total) return null

        var remaining = reported
        if (remainingPercent != null && remainingPercent.isFinite()) {
            val asRemaining = reported / total * 100.0
            val asUsed = (total - reported) / total * 100.0
            val remainingDistance = abs(asRemaining - remainingPercent)
            val usedDistance = abs(asUsed - remainingPercent)
            if (min(remainingDistance, usedDistance) > PERCENT_MATCH_TOLERANCE) return null
            // The percentage is the authority; a tie keeps the legacy remaining reading.
            if (usedDistance < remainingDistance) remaining = total - reported
        }
        return (total - remaining) / total * 100.0
    }

    private fun window(
        id: String,
        label: String,
        category: WindowCategory,
        periodSeconds: Long,
        usedPercent: Double?,
        resetAt: Long?,
    ) = UsageWindow(
        id = id,
        label = label,
        category = category,
        usedPercent = usedPercent,
        periodSeconds = periodSeconds,
        resetAt = resetAt,
        exhausted = usedPercent == 100.0,
        // A token plan grants each model its own allowance, so a dry model is not a dry account.
        scope = UsageScope.MODEL,
    )

    /** The reset is a server-supplied epoch stamp; millis by magnitude, seconds tolerated. */
    private fun resetAt(entry: JsonObject, name: String): Long? =
        JsonSupport.long(entry, name)?.let(Instants::fromEpochNumber)
}
