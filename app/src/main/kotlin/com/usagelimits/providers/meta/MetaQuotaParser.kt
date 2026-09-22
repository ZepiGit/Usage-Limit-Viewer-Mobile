package com.usagelimits.providers.meta

import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.time.Instants
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Converts the subscription usage block returned by Meta's Muse endpoint into app windows.
 *
 * Meta reports consumption (`used_percent`) while the app displays what remains. The rolling
 * window carries its duration in the payload, so it is deliberately not assumed to be five
 * hours: a changed duration is still a real limit and remains visible as [WindowCategory.OTHER]
 * when it is not one of the standard periods.
 */
object MetaQuotaParser {

    private const val WINDOW_ID = "meta-window"
    private const val WEEKLY_ID = "meta-weekly"
    private const val WEEK_SECONDS = 7L * 24 * 60 * 60

    /**
     * Parses a successful `/muse-code/key` response.
     *
     * Older responses use `subs_usage`; streaming responses seen in the ecosystem wrap the
     * same shape in `subscription_usage.subscription`. Both are accepted so a server rollout
     * does not turn a healthy account into an empty snapshot.
     */
    @Suppress("UNUSED_PARAMETER")
    fun parse(payload: JsonObject, nowMs: Long = System.currentTimeMillis()): List<UsageWindow> {
        // CPAMC treats a valid key response without `subs_usage` as a successful observation
        // with unknown windows. Preserve that distinction: an empty list would look like a
        // transport failure to a caller that expects the provider to report its two meters.
        val usage = usageObject(payload) ?: buildJsonObject {}
        return buildList {
            parseWindow(
                id = WINDOW_ID,
                label = windowLabel(JsonSupport.obj(usage, "window")),
                value = JsonSupport.obj(usage, "window"),
                includeDuration = true,
            )?.let(::add)
            parseWindow(
                id = WEEKLY_ID,
                label = "Weekly",
                value = JsonSupport.obj(usage, "weekly"),
                includeDuration = false,
            )?.let(::add)
        }
    }

    /** Reads the subscription tier without assuming a fixed set of future plan names. */
    fun parsePlan(payload: JsonObject): String? {
        JsonSupport.string(
            payload,
            "subs_tier_name",
            "subsTierName",
            "plan_name",
            "planName",
            "plan",
            "tier",
        )?.let { return it }
        val usage = usageObject(payload)
        return JsonSupport.string(usage, "tier", "plan", "plan_name", "planName")
            ?: JsonSupport.string(payload, "subs_tier_id", "subsTierId", "tier_id", "tierId")
    }

    /** The stable `subs_usage` shape plus the nested stream shape used by some clients. */
    private fun usageObject(payload: JsonObject): JsonObject? {
        JsonSupport.obj(payload, "subs_usage", "subsUsage")?.let { return it }
        val subscription = JsonSupport.obj(payload, "subscription_usage", "subscriptionUsage")
        return JsonSupport.obj(subscription, "subscription") ?: subscription
    }

    private fun parseWindow(
        id: String,
        label: String,
        value: JsonObject?,
        includeDuration: Boolean,
    ): UsageWindow? {
        val used = JsonSupport.double(value, "used_percent", "usedPercent")
            ?.coerceIn(0.0, 100.0)
        val durationMinutes = if (includeDuration) {
            JsonSupport.long(value, "window_duration_mins", "windowDurationMins")
                ?.takeIf { it > 0 }
        } else {
            null
        }
        val periodSeconds = durationMinutes?.let { minutes ->
            runCatching { Math.multiplyExact(minutes, 60L) }.getOrNull()
                ?.takeIf { it > 0 }
        } ?: if (id == WEEKLY_ID) WEEK_SECONDS else null

        val resetAt = JsonSupport.long(value, "resets_at", "resetsAt")
            ?.takeIf { it > 0 }
            ?.let(Instants::fromEpochNumber)

        return UsageWindow(
            id = id,
            label = label,
            category = WindowCategory.fromPeriodSeconds(periodSeconds),
            usedPercent = used,
            periodSeconds = periodSeconds,
            resetAt = resetAt,
            exhausted = used?.let { it >= 100.0 } ?: false,
        )
    }

    /** Keep the standard five-hour wording but preserve arbitrary upstream durations. */
    private fun windowLabel(window: JsonObject?): String {
        val minutes = JsonSupport.long(window, "window_duration_mins", "windowDurationMins")
            ?.takeIf { it > 0 }
            ?: return "Rolling window"
        return when {
            minutes % 60L == 0L -> "${minutes / 60L}h limit"
            else -> "$minutes min limit"
        }
    }
}
