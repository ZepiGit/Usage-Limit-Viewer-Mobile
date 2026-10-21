package com.usagelimits.core.model

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/*
 * Prepaid / API balances ("credits"), kept apart from the usage-limit accounts: a credit account
 * has no windows and no reset, only an amount of money left.
 *
 * Raw values, field names and the severity rules are the shared Android/iOS contract written down
 * in docs/credits.md; change them on both platforms or neither. Money is BigDecimal end to end:
 * 0.1 + 0.2 must read 0.30, and a Double cannot promise that.
 */

/** The providers whose balance can be read with a key. [id] is persisted. */
enum class CreditProviderId(val id: String, val displayName: String, val keyHint: String, val consoleUrl: String) {
    OPENROUTER(
        "openrouter", "OpenRouter",
        "Management key for the account balance, or any API key for that key's own limit.",
        "https://openrouter.ai/settings/keys",
    ),
    DEEPSEEK("deepseek", "DeepSeek", "DeepSeek API key.", "https://platform.deepseek.com/api_keys"),
    MOONSHOT(
        "moonshot", "Moonshot (Kimi API)", "Moonshot platform API key (not a Kimi Code key).",
        "https://platform.moonshot.ai/console/api-keys",
    ),
    XAI(
        "xai", "xAI (prepaid)",
        "xAI management key (console.x.ai, Settings, Management Keys), not an inference key.",
        "https://console.x.ai/team/default/api-keys",
    ),
    FAL(
        "fal", "fal.ai", "fal key with the billing:usage:read scope (the BILLING preset), not an inference key.",
        "https://fal.ai/dashboard/keys",
    ),
    NOVITA("novita", "Novita AI", "Novita API key.", "https://novita.ai/settings/key-management");

    /** Only Moonshot runs two hosts (and two currencies) that keys do not cross. */
    val hasRegion: Boolean get() = this == MOONSHOT

    /** xAI needs a team id when the management key is not scoped to one team. */
    val acceptsTeamId: Boolean get() = this == XAI

    companion object {
        fun fromId(id: String?): CreditProviderId? = entries.firstOrNull { it.id == id }

        /** Regions in picker order. Default is the first. */
        val regions: List<ProviderRegion> = listOf(ProviderRegion.INTL, ProviderRegion.CN)

        fun regionLabel(region: ProviderRegion): String =
            if (region == ProviderRegion.CN) "China (moonshot.cn, CNY)" else "International (moonshot.ai, USD)"

        /** Shown in the picker but disabled, with the reason. Not enum cases: nothing to store or sync. */
        val notPossible: List<CreditUnavailableProvider> = listOf(
            CreditUnavailableProvider("anthropic", "Anthropic API", "No balance endpoint for API keys"),
            CreditUnavailableProvider("openai", "OpenAI API", "No balance endpoint for API keys"),
        )
    }
}

data class CreditUnavailableProvider(val id: String, val displayName: String, val reason: String)

/** One amount of money left, in one currency. */
data class CreditBalance(
    /** ISO 4217 code. */
    val currency: String,
    val amount: BigDecimal,
    /** The cap or purchased total, when the provider states one; draws the bar. */
    val total: BigDecimal? = null,
    val label: String? = null,
    /**
     * An uncapped OpenRouter key: there is no balance to be low on. [amount] then holds what the
     * key spent this month and [label] is "No limit".
     */
    val unlimited: Boolean = false,
) {
    /** Remaining share of [total], 0..100, or null without a positive total. */
    val remainingPercent: Double?
        get() {
            val cap = total?.takeIf { it.signum() > 0 } ?: return null
            return amount.multiply(HUNDRED).divide(cap, 6, java.math.RoundingMode.HALF_EVEN)
                .toDouble().coerceIn(0.0, 100.0)
        }

    private companion object {
        val HUNDRED: BigDecimal = BigDecimal(100)
    }
}

enum class CreditStatus { OK, FAILED }

/**
 * The last read of one credit account. A failure keeps the previous balances and the original
 * [fetchedAt]: stale numbers marked stale beat an empty card.
 */
data class CreditSnapshot(
    val accountId: String,
    val balances: List<CreditBalance>,
    val fetchedAt: Long,
    val lastAttemptAt: Long = fetchedAt,
    val status: CreditStatus = CreditStatus.OK,
    val error: String? = null,
)

/** A connected credit account. Carries no key: [credentialReference] names the Keystore entry. */
data class CreditAccount(
    val id: String,
    val provider: CreditProviderId,
    val label: String,
    val credentialReference: String,
    /** Non-secret extras: `region` (Moonshot), `teamId` (xAI), `lowThreshold` (decimal string). */
    val attributes: Map<String, String> = emptyMap(),
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
) {
    val region: ProviderRegion
        get() = ProviderRegion.entries.firstOrNull { it.id == attributes[REGION_KEY] } ?: ProviderRegion.INTL

    val teamId: String? get() = attributes[TEAM_ID_KEY]?.takeIf { it.isNotEmpty() }

    /** What counts as low for [currency]: the user's own figure, else 5 units (35 for CNY). */
    fun lowThreshold(currency: String): BigDecimal {
        attributes[LOW_THRESHOLD_KEY]?.let { text ->
            CreditNumbers.parse(text)?.takeIf { it.signum() > 0 }?.let { return it }
        }
        return if (currency.uppercase() == "CNY") BigDecimal(35) else BigDecimal(5)
    }

    /** The user's own figure, for the edit field. */
    val customThreshold: BigDecimal?
        get() = attributes[LOW_THRESHOLD_KEY]?.let(CreditNumbers::parse)?.takeIf { it.signum() > 0 }

    fun with(label: String, lowThreshold: BigDecimal?): CreditAccount =
        copy(
            label = label,
            attributes = if (lowThreshold == null) attributes - LOW_THRESHOLD_KEY
            else attributes + (LOW_THRESHOLD_KEY to lowThreshold.toPlainString()),
        )

    companion object {
        const val REGION_KEY = ProviderRegion.KEY
        const val TEAM_ID_KEY = "teamId"
        const val LOW_THRESHOLD_KEY = "lowThreshold"

        /** `credit-<provider>-<digest of the key>`: the same key pasted again is the same account. */
        fun idFor(provider: CreditProviderId, keyDigest: String): String = "credit-${provider.id}-$keyDigest"

        fun referenceFor(id: String): String = "credit:$id"
    }
}

/** An account and its latest snapshot, as the repository hands them over. */
data class CreditEntry(val account: CreditAccount, val snapshot: CreditSnapshot?) {
    val id: String get() = account.id
}

object CreditNumbers {
    private val STRICT = Regex("^[+-]?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")

    /**
     * A strict decimal: digits with an optional sign, fraction and exponent (iOS reads every JSON
     * number as text, so "1e5" must parse alike on both). "12abc" and "" are rejected, never zero.
     */
    fun parse(text: String): BigDecimal? =
        text.trim().takeIf { STRICT.matches(it) }?.let { runCatching { BigDecimal(it) }.getOrNull() }
}

/**
 * How low a balance is. Empty is exhausted; with a known total the remaining share goes through
 * the same thresholds as usage; without one the per-account threshold decides (below it low,
 * below twice it moderate).
 */
object CreditSeverity {

    fun of(balance: CreditBalance, lowThreshold: BigDecimal): Severity {
        if (balance.unlimited) return Severity.HEALTHY
        if (balance.amount.signum() <= 0) return Severity.EXHAUSTED
        balance.remainingPercent?.let { return Severity.fromRemainingPercent(it) }
        if (balance.amount < lowThreshold) return Severity.LOW
        if (balance.amount < lowThreshold.multiply(BigDecimal(2))) return Severity.MEDIUM
        return Severity.HEALTHY
    }

    /**
     * What the numbers say, before age is considered. Never fetched and failed is an error;
     * never fetched and not failed is unknown.
     */
    fun base(account: CreditAccount, snapshot: CreditSnapshot?): Severity {
        if (snapshot == null) return Severity.UNKNOWN
        if (snapshot.balances.isEmpty()) {
            return if (snapshot.status == CreditStatus.FAILED) Severity.ERROR else Severity.UNKNOWN
        }
        return snapshot.balances.maxOf { of(it, account.lowThreshold(it.currency)) }
    }

    /**
     * The same staleness rule usage uses: an error stays an error, anything older than the
     * threshold reads stale rather than keeping the colour of its last number.
     */
    fun aged(base: Severity, fetchedAt: Long?, nowMs: Long, staleAfterMs: Long): Severity {
        if (fetchedAt == null) return base
        if (base == Severity.ERROR) return Severity.ERROR
        return if (nowMs - fetchedAt >= staleAfterMs) Severity.STALE else base
    }

    /** Severity of an entry as of [nowMs]; a snapshot with no balances has no age to judge. */
    fun of(entry: CreditEntry, nowMs: Long, staleAfterMs: Long): Severity {
        val base = base(entry.account, entry.snapshot)
        val fetchedAt = entry.snapshot?.takeIf { it.balances.isNotEmpty() }?.fetchedAt
        return aged(base, fetchedAt, nowMs, staleAfterMs)
    }

    /** The worst balance leads, so the figure shown is the one the colour is about. */
    fun worstFirst(entry: CreditEntry): List<CreditBalance> =
        entry.snapshot?.balances.orEmpty()
            .sortedByDescending { of(it, entry.account.lowThreshold(it.currency)) }
}

/** Locale-aware money text, two fraction digits. */
object CreditFormat {

    private val MINIMUM_UNIT = BigDecimal("0.01")

    /**
     * "$12.34". A positive amount under one cent reads "<$0.01" instead of "$0.00"; negative
     * (arrears) is allowed and shown as is.
     */
    fun string(amount: BigDecimal, currency: String, locale: Locale = Locale.getDefault()): String =
        if (amount.signum() > 0 && amount < MINIMUM_UNIT) "<" + plain(MINIMUM_UNIT, currency, locale)
        else plain(amount, currency, locale)

    /** A balance as the cards and the widget say it: an uncapped key reads as spend, not balance. */
    fun line(balance: CreditBalance, locale: Locale = Locale.getDefault()): String {
        val text = string(balance.amount, balance.currency, locale)
        return if (balance.unlimited) "$text spent this month" else text
    }

    /** Every balance of an account on one line: "¥110.00 · $5.00". */
    fun summary(balances: List<CreditBalance>, locale: Locale = Locale.getDefault()): String =
        balances.joinToString(" · ") { string(it.amount, it.currency, locale) }

    private fun plain(amount: BigDecimal, code: String, locale: Locale): String {
        val currency = runCatching { Currency.getInstance(code) }.getOrNull()
            ?: return NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 2; maximumFractionDigits = 2
            }.format(amount) + " " + code
        return NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = currency
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(amount)
    }
}
