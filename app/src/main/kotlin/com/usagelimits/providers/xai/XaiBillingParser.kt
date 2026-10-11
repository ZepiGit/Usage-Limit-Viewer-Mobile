package com.usagelimits.providers.xai

import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.time.Instants
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Normalizes the independent credit and billing responses without inventing missing usage. */
object XaiBillingParser {
    const val CREDITS_WINDOW_ID = "xai-credits"
    const val MONTHLY_WINDOW_ID = "xai-monthly"
    const val ON_DEMAND_WINDOW_ID = "xai-on-demand"
    private const val BILLING_PERIOD_SECONDS = 2_592_000L
    private val creditKeys = arrayOf("creditUsagePercent", "credit_usage_percent")
    private val monthlyKeys = arrayOf("monthlyLimit", "monthly_limit")
    private val usedKeys = arrayOf("used", "includedUsed", "included_used", "totalUsed", "total_used")
    private val capKeys = arrayOf("onDemandCap", "on_demand_cap")
    private val onDemandKeys = arrayOf("onDemandUsed", "on_demand_used")

    private fun config(payload: JsonObject): JsonObject = JsonSupport.obj(payload, "config") ?: payload

    /** The first present alias wins, including explicit null; invalid data cannot fall through. */
    private fun field(source: JsonObject, names: Array<String>): JsonElement? =
        names.firstOrNull { source.containsKey(it) }?.let { source[it] }

    private fun number(value: JsonElement?): Double? = value?.let {
        JsonSupport.double(JsonObject(mapOf("value" to it)), "value")
    }

    /** Only a present empty Cent object is the documented proto3 zero; missing/null stays unknown. */
    private fun cents(value: JsonElement?): Double? {
        val amount = when (value) {
            is JsonObject -> if (value.isEmpty()) 0.0 else number(value["val"])
            else -> number(value)
        }
        return amount?.takeIf { it >= 0.0 && it.isFinite() }
    }

    /** Malformed explicit values are a data-quality warning, independently of quota availability. */
    fun hasInvalidUsage(payload: JsonObject): Boolean {
        val source = config(payload)
        val percent = field(source, creditKeys)
        if (percent != null && percent != JsonNull && number(percent)?.let { it in 0.0..100.0 } != true) return true
        return listOf(monthlyKeys, usedKeys, capKeys, onDemandKeys).any { names ->
            val value = field(source, names)
            value != null && value != JsonNull &&
                !(value is JsonObject && value["val"] == JsonNull) && cents(value) == null
        }
    }

    fun parseCredits(payload: JsonObject, nowMs: Long): List<UsageWindow> {
        val source = config(payload)
        val period = JsonSupport.obj(source, "currentPeriod", "current_period")
        val startMs = Instants.parse(JsonSupport.string(period, "start")
            ?: JsonSupport.string(source, "usagePeriodStart", "usage_period_start"))
        val endMs = Instants.parse(JsonSupport.string(period, "end")
            ?: JsonSupport.string(source, "usagePeriodEnd", "usage_period_end"))
        val periodType = JsonSupport.string(period, "type")
            ?: JsonSupport.string(source, "usagePeriodType", "usage_period_type")
        val value = field(source, creditKeys)
        if (value == null && startMs == null && endMs == null && periodType == null) return emptyList()

        // The first-party BillingConfig uses Option<f64> for credit_usage_percent. A current
        // period is not evidence for zero; it is equally compatible with omitted/unknown usage.
        val used = number(value)?.takeIf { it in 0.0..100.0 }
        val seconds = if (startMs != null && endMs != null && endMs > startMs) {
            (endMs - startMs) / 1000
        } else when {
            periodType?.contains("week", ignoreCase = true) == true -> 604_800L
            periodType?.contains("month", ignoreCase = true) == true -> BILLING_PERIOD_SECONDS
            else -> null
        }
        val category = WindowCategory.fromPeriodSeconds(seconds)
        return listOf(UsageWindow(
            id = CREDITS_WINDOW_ID,
            label = when (category) {
                WindowCategory.WEEKLY -> "Weekly credits"
                WindowCategory.MONTHLY -> "Monthly credits"
                else -> "Credits"
            },
            category = category,
            usedPercent = used,
            periodSeconds = seconds,
            resetAt = endMs,
            exhausted = used == 100.0,
        ))
    }

    fun parseBilling(payload: JsonObject, nowMs: Long): List<UsageWindow> {
        val source = config(payload)
        val windows = parseCredits(payload, nowMs).toMutableList()
        val limitField = field(source, monthlyKeys)
        val usedField = field(source, usedKeys)
        val monthlyLimit = cents(limitField)
        val used = cents(usedField)
        val resetAt = Instants.parse(JsonSupport.string(source, "billingPeriodEnd", "billing_period_end"))
        val capField = field(source, capKeys)
        val demandField = field(source, onDemandKeys)
        val cap = cents(capField)
        val billingScope = if (cap != null && cap > 0.0) UsageScope.MODEL else UsageScope.GLOBAL
        if (limitField != null || usedField != null) {
            val percent = monthlyLimit?.let { limit -> used?.let { percentOf(it, limit) } }
            windows += UsageWindow(
                id = MONTHLY_WINDOW_ID,
                label = "Monthly included",
                category = WindowCategory.MONTHLY,
                usedPercent = percent,
                periodSeconds = BILLING_PERIOD_SECONDS,
                resetAt = resetAt,
                exhausted = percent == 100.0,
                scope = billingScope,
            )
        }

        // A stated zero cap means no on-demand facility. A missing/invalid cap is unknown.
        if (cap != 0.0 && (capField != null || demandField != null)) {
            val demandUsed = if (demandField != null) cents(demandField)
                else monthlyLimit?.let { limit -> used?.let { maxOf(0.0, it - limit) } }
            val percent = cap?.let { total -> demandUsed?.let { percentOf(it, total) } }
            windows += UsageWindow(
                id = ON_DEMAND_WINDOW_ID,
                label = "On-demand",
                category = WindowCategory.MONTHLY,
                usedPercent = percent,
                periodSeconds = BILLING_PERIOD_SECONDS,
                resetAt = resetAt,
                exhausted = percent == 100.0,
                scope = UsageScope.MODEL,
            )
        }
        return merge(emptyList(), windows)
    }

    /** Prefer the direct credit view, except that unknown must not hide a measured fallback. */
    fun merge(credits: List<UsageWindow>, billing: List<UsageWindow>): List<UsageWindow> {
        val byId = linkedMapOf<String, UsageWindow>()
        for (window in credits + billing) {
            val previous = byId[window.id]
            if (previous == null || (previous.remainingPercent == null && window.remainingPercent != null)) {
                byId[window.id] = window
            }
        }
        // These are newer and deprecated representations of the same included allowance.
        // The first-party schema explicitly prefers measured credits over monthlyLimit/used.
        val measuredCredits = byId[CREDITS_WINDOW_ID]?.remainingPercent != null
        return byId.values.filterNot { measuredCredits && it.id == MONTHLY_WINDOW_ID }
    }

    /** Verified nonnegative spend beyond a valid cap exhausts that allowance, never exceeds 100%. */
    private fun percentOf(amount: Double, total: Double): Double? =
        if (amount.isFinite() && amount >= 0.0 && total.isFinite() && total > 0.0) {
            (minOf(amount, total) / total * 100.0).takeIf { it.isFinite() }
        } else null
}
