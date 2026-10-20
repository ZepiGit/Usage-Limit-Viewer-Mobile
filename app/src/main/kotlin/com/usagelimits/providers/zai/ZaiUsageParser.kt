package com.usagelimits.providers.zai

import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.model.planLabel
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.time.Instants
import kotlinx.serialization.json.JsonObject

/**
 * Reads Z.ai's `GET /api/monitor/usage/quota/limit` (GLM Coding Plan).
 *
 * Undocumented; the shape comes from other open-source monitors. `data.limits[]` carries one
 * entry per window, typed TOKENS_LIMIT, TIME_LIMIT or CREDIT_LIMIT — all three are read the
 * same way, because plans differ in which they use. The window length is `number` of `unit`,
 * and the usage is `percentage` (percent USED) when present, else `currentValue / usage`
 * (`usage` is the cap, despite its name). Max-plan entries may carry only a percentage.
 *
 * An unreadable or out-of-range figure makes that window unknown, never zero and never full.
 */
object ZaiUsageParser {

    /** Minutes per `unit`: 1 day, 3 hour, 5 minute, 6 week. */
    private val unitMinutes = mapOf(1L to 1_440L, 3L to 60L, 5L to 1L, 6L to 10_080L)
    private const val MONTH_SECONDS = 30L * 24 * 60 * 60

    fun parse(payload: JsonObject): List<UsageWindow> =
        JsonSupport.array(JsonSupport.obj(payload, "data"), "limits").mapIndexedNotNull { index, element ->
            val entry = element as? JsonObject ?: return@mapIndexedNotNull null
            val type = JsonSupport.string(entry, "type").orEmpty().uppercase()
            val seconds = periodSeconds(type, entry)
            val category = WindowCategory.fromPeriodSeconds(seconds)
            val percent = usedPercent(entry)
            UsageWindow(
                id = "zai-${type.lowercase()}-${seconds ?: index}",
                label = label(category, type),
                category = category,
                usedPercent = percent,
                periodSeconds = seconds,
                resetAt = JsonSupport.long(entry, "nextResetTime")?.let(Instants::fromEpochNumber),
                exhausted = percent == 100.0,
            )
        }

    /** The plan tier (`lite`, `pro`, `max`), as a label. */
    fun plan(payload: JsonObject): String? =
        planLabel(JsonSupport.string(JsonSupport.obj(payload, "data"), "level"))

    private fun periodSeconds(type: String, entry: JsonObject): Long? {
        val unit = JsonSupport.long(entry, "unit") ?: return null
        val number = JsonSupport.long(entry, "number")?.takeIf { it > 0 } ?: return null
        // The monthly MCP allowance is sent as unit 5 (minute) number 1; read it as the month.
        if (type == "TIME_LIMIT" && unit == 5L && number == 1L) return MONTH_SECONDS
        val perUnit = unitMinutes[unit] ?: return null
        return runCatching { Math.multiplyExact(number, perUnit * 60) }.getOrNull()
    }

    private fun usedPercent(entry: JsonObject): Double? {
        val cap = JsonSupport.double(entry, "usage")
        val current = JsonSupport.double(entry, "currentValue")
        val used = JsonSupport.double(entry, "percentage")
            ?: if (cap != null && cap > 0.0 && current != null) current / cap * 100.0 else return null
        return used.takeIf { it.isFinite() && it in 0.0..100.0 }
    }

    private fun label(category: WindowCategory, type: String): String = when (category) {
        WindowCategory.FIVE_HOUR -> "5h limit"
        WindowCategory.WEEKLY -> "Weekly"
        WindowCategory.MONTHLY -> "Monthly"
        WindowCategory.OTHER -> when (type) {
            "TOKENS_LIMIT" -> "Token limit"
            "TIME_LIMIT" -> "Time limit"
            "CREDIT_LIMIT" -> "Credit limit"
            else -> "Limit"
        }
    }
}
