package com.usagelimits.providers.credits

import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditNumbers
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.core.network.JsonSupport
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal

/**
 * Why a balance could not be read. Messages are built from endpoint names and status codes only,
 * never a payload, header or key, so they are safe to show and to store.
 */
sealed class CreditError(message: String) : Exception(message) {
    /** 401 or 403: the key is wrong, expired, or the wrong kind for this endpoint. */
    class Rejected(val status: Int) :
        CreditError("The key was rejected (HTTP $status). Check that it is the right kind of key.")

    class NotFound(detail: String) : CreditError(detail)
    class Malformed(detail: String) :
        CreditError("The provider answered in a shape this app does not understand ($detail).")
    class Unavailable(detail: String) : CreditError("No balance could be read ($detail).")
    class TeamIdRequired :
        CreditError("This xAI key is not tied to one team. Enter the team id from console.x.ai.")
    class NoKey : CreditError("No key was entered.")
}

/** Reading the balance payloads. Pure: payload in, balances out, so every quirk is testable. */
object CreditJson {

    /**
     * A JSON number or a numeric string. A number is read from the text the provider sent, so
     * 49.58894 stays 49.58894 and never picks up binary noise; a string must be a strict decimal
     * ("12abc" is rejected, not read as 12).
     */
    fun decimal(source: JsonObject?, vararg names: String): BigDecimal? {
        val primitive = (JsonSupport.field(source, *names) as? JsonPrimitive) ?: return null
        if (primitive.isString) return CreditNumbers.parse(primitive.content)
        return runCatching { BigDecimal(primitive.content.trim()) }.getOrNull()
    }

    fun currency(value: String?, default: String): String {
        val code = value?.trim()?.uppercase()
        return if (code != null && code.length == 3 && code.all { it.isLetter() }) code else default
    }
}

object OpenRouterCreditParser {

    /** `/credits` (management key): purchased minus used, and the purchased total for the bar. */
    fun parseCredits(payload: JsonObject): List<CreditBalance> {
        val data = JsonSupport.obj(payload, "data")
        val total = CreditJson.decimal(data, "total_credits")
        val used = CreditJson.decimal(data, "total_usage")
        if (total == null || used == null) throw CreditError.Malformed("openrouter credits has no totals")
        return listOf(CreditBalance("USD", total - used, total))
    }

    /**
     * `/key` (any key): the key's own budget. `effective_budget` wins when it states a
     * remainder; `limit` null means no cap, so there is no balance and the month's spend shows.
     */
    fun parseKey(payload: JsonObject): List<CreditBalance> {
        val data = JsonSupport.obj(payload, "data") ?: throw CreditError.Malformed("openrouter key has no data")
        val budget = JsonSupport.obj(data, "effective_budget")
        CreditJson.decimal(budget, "remaining_usd")?.let { remaining ->
            return listOf(CreditBalance("USD", remaining, CreditJson.decimal(budget, "limit_usd"), "Key limit"))
        }
        // `limit` must be SENT: an explicit null is an uncapped key, an absent one is a payload
        // that is not what this app expects, and must not read as a healthy $0.
        val limit = data["limit"] ?: throw CreditError.Malformed("openrouter key has no limit field")
        if (limit is JsonNull) {
            val spent = CreditJson.decimal(data, "usage_monthly")
                ?: throw CreditError.Malformed("openrouter key has no limit and no monthly usage")
            return listOf(CreditBalance("USD", spent, label = "No limit", unlimited = true))
        }
        val remaining = CreditJson.decimal(data, "limit_remaining")
            ?: throw CreditError.Malformed("openrouter key has a limit but no remainder")
        return listOf(CreditBalance("USD", remaining, CreditJson.decimal(data, "limit"), "Key limit"))
    }
}

object DeepSeekCreditParser {

    /**
     * One balance per currency. `is_available: false` means requests are refused, so the
     * balances read as empty whatever the grant figures say.
     */
    fun parse(payload: JsonObject): List<CreditBalance> {
        val available = JsonSupport.boolean(payload, "is_available") ?: true
        val balances = JsonSupport.array(payload, "balance_infos").mapNotNull { element ->
            val info = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val amount = CreditJson.decimal(info, "total_balance") ?: return@mapNotNull null
            val currency = CreditJson.currency(JsonSupport.string(info, "currency"), "")
            if (currency.isEmpty()) return@mapNotNull null
            if (available) CreditBalance(currency, amount)
            else CreditBalance(currency, BigDecimal.ZERO, label = "Unavailable")
        }
        if (balances.isNotEmpty()) return balances
        if (!available) return listOf(CreditBalance("CNY", BigDecimal.ZERO, label = "Unavailable"))
        throw CreditError.Malformed("deepseek balance has no balances")
    }
}

object MoonshotCreditParser {

    /**
     * `available_balance` is what requests can spend; `cash_balance` may be negative (arrears)
     * and is deliberately ignored. The currency follows the host.
     */
    fun parse(payload: JsonObject, region: ProviderRegion): List<CreditBalance> {
        val available = CreditJson.decimal(JsonSupport.obj(payload, "data"), "available_balance")
            ?: throw CreditError.Malformed("moonshot balance has no available_balance")
        return listOf(CreditBalance(if (region == ProviderRegion.CN) "CNY" else "USD", available))
    }
}

object XaiCreditParser {

    /**
     * The team a management key is scoped to, or null for an organisation-wide key (the caller
     * then needs the user's team id).
     */
    fun parseTeamId(validation: JsonObject): String? {
        if (JsonSupport.string(validation, "scope") != "SCOPE_TEAM") return null
        return JsonSupport.string(validation, "scopeId", "scope_id")
    }

    /**
     * `total.val` is USD cents as a string, from a ledger: a top-up is NEGATIVE, so what is left
     * is minus the total. A missing or unreadable total is an error, never $0.
     */
    fun parseBalance(payload: JsonObject): List<CreditBalance> {
        val cents = CreditJson.decimal(JsonSupport.obj(payload, "total"), "val")
            ?: throw CreditError.Malformed("xai prepaid balance has no total")
        return listOf(CreditBalance("USD", cents.negate().movePointLeft(2)))
    }
}

object FalCreditParser {

    /** A 200 without the credits block happens during fal incidents; that is a failed read. */
    fun parse(payload: JsonObject): List<CreditBalance> {
        val credits = JsonSupport.obj(payload, "credits")
        val balance = CreditJson.decimal(credits, "current_balance")
        if (credits == null || balance == null) throw CreditError.Unavailable("fal returned no credits")
        return listOf(CreditBalance(CreditJson.currency(JsonSupport.string(credits, "currency"), "USD"), balance))
    }
}

object NovitaCreditParser {

    /** Every figure is a string in 1/10000 USD ("10000" is $1.00). */
    fun parse(payload: JsonObject): List<CreditBalance> {
        val units = CreditJson.decimal(payload, "availableBalance")
            ?: throw CreditError.Malformed("novita balance has no availableBalance")
        return listOf(CreditBalance("USD", units.movePointLeft(4)))
    }
}
