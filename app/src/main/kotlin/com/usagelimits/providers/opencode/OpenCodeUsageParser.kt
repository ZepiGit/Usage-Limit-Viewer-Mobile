package com.usagelimits.providers.opencode

import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.time.Instants
import kotlinx.serialization.json.JsonObject

/**
 * Reads OpenCode Go's `GET /zen/go/v1/usage`.
 *
 * `usage.rolling` is the five-hour window, `weekly` the week and `monthly` the month; each has
 * `status`, `percent` (integer, USED) and `resetsAt` (ISO-8601). Only status `ok` is trusted —
 * any other status, or a missing or out-of-range percent, leaves that window unknown rather
 * than guessing. The month is nominally 30 days; the real period follows the subscription date.
 */
object OpenCodeUsageParser {

    private data class Spec(val key: String, val label: String, val seconds: Long)

    private val windows = listOf(
        Spec("rolling", "5h limit", 5 * 3_600L),
        Spec("weekly", "Weekly", 7 * 86_400L),
        Spec("monthly", "Monthly", 30 * 86_400L),
    )

    fun parse(payload: JsonObject): List<UsageWindow> {
        val usage = JsonSupport.obj(payload, "usage")
        return windows.mapNotNull { spec ->
            val entry = JsonSupport.obj(usage, spec.key) ?: return@mapNotNull null
            val ok = JsonSupport.string(entry, "status")?.lowercase() == "ok"
            val percent = if (ok) JsonSupport.double(entry, "percent")?.takeIf { it in 0.0..100.0 } else null
            UsageWindow(
                id = "opencode-${spec.key}",
                label = spec.label,
                category = WindowCategory.fromPeriodSeconds(spec.seconds),
                usedPercent = percent,
                periodSeconds = spec.seconds,
                resetAt = Instants.parse(JsonSupport.string(entry, "resetsAt")),
                exhausted = percent == 100.0,
            )
        }
    }
}
