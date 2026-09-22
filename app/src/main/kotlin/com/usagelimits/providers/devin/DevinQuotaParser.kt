package com.usagelimits.providers.devin

import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.time.Instants
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Normalises Devin's JSON Connect-RPC `GetUserStatus` response.
 *
 * CPAMC receives a response shaped like
 *
 * ```json
 * {
 *   "userStatus": {
 *     "planStatus": {
 *       "planInfo": { "planName": "Pro" },
 *       "dailyQuotaRemainingPercent": 54,
 *       "weeklyQuotaRemainingPercent": 77,
 *       "dailyQuotaResetAtUnix": "1789372800",
 *       "weeklyQuotaResetAtUnix": 1789891200
 *     }
 *   }
 * }
 * ```
 *
 * The service reports what remains, while [UsageWindow] stores consumption. Keeping that
 * conversion here means the UI, widgets and notifications get the same semantics as every
 * other provider. Unknown or malformed fields are discarded independently, so one broken
 * reset timestamp cannot hide the other window.
 */
object DevinQuotaParser {

    private const val DAILY_ID = "devin-daily"
    private const val WEEKLY_ID = "devin-weekly"
    private const val DAILY_LABEL = "Daily"
    private const val WEEKLY_LABEL = "Weekly"
    private const val DAILY_SECONDS = 24L * 60 * 60
    private const val WEEKLY_SECONDS = 7L * 24 * 60 * 60

    /** Parses the daily and weekly windows that are present in [payload]. */
    @Suppress("UNUSED_PARAMETER")
    fun parse(payload: JsonObject, nowMs: Long = System.currentTimeMillis()): List<UsageWindow> {
        // nowMs is part of the provider parser contract. Devin sends absolute Unix reset times,
        // so it is intentionally unused today; retaining it keeps this parser easy to evolve
        // if the endpoint starts returning relative timestamps.
        val status = planStatus(payload) ?: return emptyList()
        return listOfNotNull(
            window(
                status = status,
                id = DAILY_ID,
                label = DAILY_LABEL,
                remainingNames = arrayOf(
                    "dailyQuotaRemainingPercent",
                    "daily_quota_remaining_percent",
                    "dailyRemainingPercent",
                    "daily_remaining_percent",
                ),
                resetNames = arrayOf(
                    "dailyQuotaResetAtUnix",
                    "daily_quota_reset_at_unix",
                    "dailyQuotaResetAt",
                    "daily_quota_reset_at",
                ),
                periodSeconds = DAILY_SECONDS,
                category = WindowCategory.OTHER,
            ),
            window(
                status = status,
                id = WEEKLY_ID,
                label = WEEKLY_LABEL,
                remainingNames = arrayOf(
                    "weeklyQuotaRemainingPercent",
                    "weekly_quota_remaining_percent",
                    "weeklyRemainingPercent",
                    "weekly_remaining_percent",
                ),
                resetNames = arrayOf(
                    "weeklyQuotaResetAtUnix",
                    "weekly_quota_reset_at_unix",
                    "weeklyQuotaResetAt",
                    "weekly_quota_reset_at",
                ),
                periodSeconds = WEEKLY_SECONDS,
                category = WindowCategory.WEEKLY,
            ),
        )
    }

    /** Reads the plan name used on the account card, when Devin reports one. */
    fun parsePlan(payload: JsonObject): String? {
        val status = planStatus(payload)
        val planInfo = JsonSupport.obj(status, "planInfo", "plan_info")
        return JsonSupport.string(planInfo, "planName", "plan_name")
            ?: JsonSupport.string(status, "planName", "plan_name", "plan")
            ?: JsonSupport.string(payload, "planName", "plan_name", "plan")
    }

    /** Returns the response's nested planStatus object, accepting both wire spellings. */
    fun planStatus(payload: JsonObject): JsonObject? {
        val root = JsonSupport.obj(payload, "userStatus", "user_status", "status", "result") ?: payload
        val status = JsonSupport.obj(root, "userStatus", "user_status") ?: root
        return JsonSupport.obj(status, "planStatus", "plan_status")
    }

    private fun window(
        status: JsonObject,
        id: String,
        label: String,
        remainingNames: Array<String>,
        resetNames: Array<String>,
        periodSeconds: Long,
        category: WindowCategory,
    ): UsageWindow? {
        val remaining = JsonSupport.double(status, *remainingNames)
            ?.takeIf { it in 0.0..100.0 }
            ?: return null

        // The endpoint calls this a Unix timestamp in seconds. JsonSupport performs a checked
        // seconds-to-milliseconds conversion, avoiding overflow for hostile or malformed JSON.
        val resetAt = resetAt(status, resetNames)

        return UsageWindow(
            id = id,
            label = label,
            category = category,
            usedPercent = (100.0 - remaining).coerceIn(0.0, 100.0),
            periodSeconds = periodSeconds,
            resetAt = resetAt,
            exhausted = remaining <= 0.0,
        )
    }

    private fun resetAt(status: JsonObject, names: Array<String>): Long? {
        // Preserve JSON numeric values such as 1789372800.0 when they are mathematically
        // integral, while rejecting a quoted fractional timestamp (`"1.5"`) the same way
        // CPAMC does. JsonSupport.string() alone cannot distinguish those two wire types.
        val element = JsonSupport.field(status, *names)
        val primitive = runCatching { element?.jsonPrimitive }.getOrNull()
        if (primitive != null && !primitive.isString) {
            val numeric = primitive.doubleOrNull
                ?.takeIf { it.isFinite() && it >= 0.0 && it % 1.0 == 0.0 }
                ?: return null
            return JsonSupport.secondsToMillis(numeric.toLong())?.takeIf { it > 0 }
        }

        val raw = JsonSupport.string(status, *names) ?: return null
        raw.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { JsonSupport.secondsToMillis(it) }
            ?.takeIf { it > 0 }
            ?.let { return it }

        // A few gateway deployments serialise the same field as an ISO instant. CPAMC's
        // canonical form is Unix seconds, but accepting this equivalent spelling costs no
        // ambiguity and keeps a rollout from dropping an otherwise valid row.
        return if (raw.contains('T', ignoreCase = true)) {
            Instants.parse(raw)?.takeIf { it > 0 }
        } else {
            null
        }
    }
}
