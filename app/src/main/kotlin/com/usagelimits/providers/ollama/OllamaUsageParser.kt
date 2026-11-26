package com.usagelimits.providers.ollama

import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.time.Instants
import com.usagelimits.providers.StableWindowIds
import kotlinx.serialization.json.JsonObject

/**
 * Reads Ollama Cloud's `GET /api/balance` as usage windows (docs.ollama.com/api/balance).
 *
 * Two shapes share the `included` block. A current plan states `balance_usd` and `allowance_usd`
 * over a `period`; that is one window named after the allowance, and its used share is what has
 * been spent of the allowance. A legacy plan states `session` and `weekly` limits instead, each
 * with a `remaining_percent` (what REMAINS) and a `resets_at`.
 *
 * Neither shape's absent block becomes a 0 % window: a block that is missing is omitted. The
 * documentation states no duration for the legacy session limit, and the included period follows
 * the plan's own reset schedule (annual plans included), so both keep their label and reset with
 * no category. `weekly` is a week.
 */
object OllamaUsageParser {

    private const val WEEK_SECONDS = 7L * 86_400

    /** Display order. The label and duration are the app's own, never the payload's field name. */
    private val progressLimits = listOf(
        Triple("session", "Session", null as Long?),
        Triple("weekly", "Weekly", WEEK_SECONDS),
    )

    fun parse(payload: JsonObject): List<UsageWindow> {
        val included = JsonSupport.obj(payload, "included") ?: return emptyList()
        val ids = StableWindowIds()
        val windows = progressLimits.mapNotNull { (key, label, seconds) ->
            val entry = JsonSupport.obj(included, key) ?: return@mapNotNull null
            val used = remainingShare(JsonSupport.double(entry, "remaining_percent"))
            UsageWindow(
                id = ids.next(key),
                label = label,
                category = WindowCategory.fromPeriodSeconds(seconds),
                usedPercent = used,
                periodSeconds = seconds,
                resetAt = Instants.parse(JsonSupport.string(entry, "resets_at")),
                exhausted = used == 100.0,
            )
        }.toMutableList()
        includedAllowance(included, ids)?.let(windows::add)
        return windows
    }

    /** `remaining_percent` is what REMAINS; the bar stores what is used. Out of range is unknown. */
    private fun remainingShare(remaining: Double?): Double? =
        remaining?.takeIf { it in 0.0..100.0 }?.let { 100.0 - it }

    /**
     * The included credits as one window: used is what the allowance has lost. An allowance of
     * zero (or a missing figure) has no share to draw and omits the window. The reset is the
     * period's `until`; a null period invents none.
     */
    private fun includedAllowance(included: JsonObject, ids: StableWindowIds): UsageWindow? {
        val balance = JsonSupport.double(included, "balance_usd") ?: return null
        val allowance = JsonSupport.double(included, "allowance_usd")?.takeIf { it > 0.0 } ?: return null
        val used = ((allowance - balance) * 100.0 / allowance).coerceIn(0.0, 100.0)
        return UsageWindow(
            id = ids.next("included"),
            label = "Included allowance",
            category = WindowCategory.OTHER,
            usedPercent = used,
            periodSeconds = null,
            resetAt = Instants.parse(JsonSupport.string(JsonSupport.obj(included, "period"), "until")),
            exhausted = used == 100.0,
        )
    }
}
