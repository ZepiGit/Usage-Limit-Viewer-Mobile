package com.usagelimits.providers.credits

import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditNumbers
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.RATE_LIMITED_REQUEST_MESSAGE
import com.usagelimits.core.time.Instants
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

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
    class RateLimited(val retryAfterMs: Long?) :
        CreditError(RATE_LIMITED_REQUEST_MESSAGE)
    class TeamIdRequired :
        CreditError("This xAI key is not tied to one team. Enter the team id from console.x.ai.")

    /**
     * Vercel answers 403 when the key belongs to another team. It is not a broken key and a retry
     * cannot help, so it says what to change instead.
     */
    class TeamDenied :
        CreditError("Vercel refused this key (HTTP 403). Enter the team id the key belongs to.")

    /**
     * DeepInfra answers 403 to a key without the `account:read` scope. The balance is closed to
     * this key until the scope is granted, so the sentence names the scope.
     */
    class MissingScope : CreditError("This key lacks the account:read scope.")

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
     *
     * [nowMs] only places the next period boundary of a capped key: the payload names the period
     * (`limit_reset`) but never its instant. The budget row is left alone, because a budget's
     * scope can be a member or a workspace rather than this key.
     */
    fun parseKey(payload: JsonObject, nowMs: Long): List<CreditBalance> {
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
        val cap = CreditJson.decimal(data, "limit")
        val window = cap?.takeIf { it.signum() > 0 }?.let { keyLimitWindow(JsonSupport.string(data, "limit_reset"), nowMs) }
        return listOf(
            CreditBalance(
                currency = "USD",
                amount = remaining,
                total = cap,
                label = window?.first ?: "Key limit",
                resetsAt = window?.second,
            ),
        )
    }

    /**
     * A key limit's period label and the next boundary strictly after [nowMs], or null for
     * `limit_reset == null` (a lifetime cap) and for a value this app does not know — no reset is
     * invented. The boundaries are OpenRouter's: daily 00:00 UTC, weekly Monday 00:00 UTC,
     * monthly on the first at 00:00 UTC.
     */
    private fun keyLimitWindow(limitReset: String?, nowMs: Long): Pair<String, Long>? = when (limitReset) {
        "daily" -> "Daily key limit" to nextUtcBoundary(nowMs) { it.plusDays(1) }
        "weekly" -> "Weekly key limit" to
            nextUtcBoundary(nowMs) { it.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) }
        "monthly" -> "Monthly key limit" to nextUtcBoundary(nowMs) { it.withDayOfMonth(1).plusMonths(1) }
        else -> null
    }

    private fun nextUtcBoundary(nowMs: Long, next: (LocalDate) -> LocalDate): Long =
        next(Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate())
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
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

object VercelCreditParser {

    /**
     * The gateway states every figure as a decimal **string**, so a JSON number is refused rather
     * than rounded through a binary float. `balance` is what is left and excludes the Token Pass;
     * `total_used` is lifetime spend and rides on the row's label. An active Token Pass becomes a
     * second row with its reset and no bar, and one whose remainder is null adds no row at all —
     * never a second zero.
     */
    fun parse(payload: JsonObject): List<CreditBalance> {
        val balance = stringDecimal(payload, "balance")
            ?: throw CreditError.Malformed("vercel credits has no balance")
        val rows = mutableListOf(
            CreditBalance(
                currency = "USD",
                amount = balance,
                label = stringDecimal(payload, "total_used")?.let { "Used: ${it.toPlainString()}" },
            ),
        )
        val pass = JsonSupport.obj(payload, "token_pass")
        stringDecimal(pass, "remaining")?.let { remaining ->
            rows += CreditBalance(
                currency = "USD",
                amount = remaining,
                label = "Token Pass",
                resetsAt = Instants.parse(JsonSupport.string(pass, "resets_at")),
            )
        }
        return rows
    }

    /**
     * A figure the provider states as a decimal string, or null when it states none. A JSON number
     * (or any other type) is a payload this app does not read: returning null would hide it, and
     * reading it as a float would lose digits.
     */
    private fun stringDecimal(source: JsonObject?, name: String): BigDecimal? {
        val element = JsonSupport.field(source, name) ?: return null
        val primitive = (element as? JsonPrimitive)?.takeIf { it.isString }
            ?: throw CreditError.Malformed("vercel credits states $name as something other than a decimal string")
        return CreditNumbers.parse(primitive.content)
            ?: throw CreditError.Malformed("vercel credits has no readable $name")
    }
}

object DeepInfraCreditParser {

    /**
     * The billing checklist. `stripe_balance` is signed: NEGATIVE is money left to spend and
     * positive is money owed, so the amount is its negation. The cents reading is the spec's
     * inference — the OpenAPI types the field `number` and states no unit — while the scoped
     * credits name their cents outright. Each live scoped credit is one more row named after it,
     * an expired one adds none, and `limit` is a spending cap that never becomes a balance. A
     * checklist that is null or absent is a failed read, never a zero balance.
     */
    fun parse(payload: JsonObject): List<CreditBalance> {
        val checklist = JsonSupport.obj(payload, "checklist")
            ?: throw CreditError.Unavailable("deepinfra me returned no checklist")
        val stripe = CreditJson.decimal(checklist, "stripe_balance")
            ?: throw CreditError.Malformed("deepinfra checklist has no stripe_balance")
        val rows = mutableListOf(
            CreditBalance("USD", stripe.negate().movePointLeft(2), label = "Prepaid balance"),
        )
        for (element in JsonSupport.array(checklist, "scoped_credits")) {
            val credit = element as? JsonObject
                ?: throw CreditError.Malformed("deepinfra scoped_credits holds a non-object")
            if (JsonSupport.boolean(credit, "expired") == true) continue
            val remaining = CreditJson.decimal(credit, "remaining_cents")
                ?: throw CreditError.Malformed("deepinfra scoped credit has no remaining_cents")
            rows += CreditBalance("USD", remaining.movePointLeft(2), label = JsonSupport.string(credit, "name"))
        }
        return rows
    }
}
