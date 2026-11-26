package com.usagelimits.ui.text

import android.content.res.Resources
import androidx.annotation.StringRes
import com.usagelimits.R
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditUnavailableProvider
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.RATE_LIMITED_REQUEST_MESSAGE
import com.usagelimits.core.sync.RATE_LIMITED_PAUSE_MESSAGE
import java.math.BigDecimal

/*
 * Resource ids for the user-facing sentences that live in the model and provider layers.
 *
 * Those packages are free of Android, so they keep the English sentence (the tests pin it, and some
 * of it is stored) and this file maps the same thing to the string the screen shows. A mapping
 * returns null where the model text is data — a region id, a label a provider reported — so the
 * caller falls back to what the model says.
 */

/** The hint under a credit provider in the picker; the model's [CreditProviderId.keyHint] in English. */
@StringRes
fun creditKeyHintRes(provider: CreditProviderId): Int = when (provider) {
    CreditProviderId.OPENROUTER -> R.string.credit_key_hint_openrouter
    CreditProviderId.DEEPSEEK -> R.string.credit_key_hint_deepseek
    CreditProviderId.MOONSHOT -> R.string.credit_key_hint_moonshot
    CreditProviderId.XAI -> R.string.credit_key_hint_xai
    CreditProviderId.FAL -> R.string.credit_key_hint_fal
    CreditProviderId.NOVITA -> R.string.credit_key_hint_novita
    CreditProviderId.VERCEL_AI_GATEWAY -> R.string.credit_key_hint_vercel
    CreditProviderId.DEEPINFRA -> R.string.credit_key_hint_deepinfra
    CreditProviderId.TOKENROUTER -> R.string.credit_key_hint_tokenrouter
    CreditProviderId.OLLAMA -> R.string.credit_key_hint_ollama
}

/** The segment label of a Moonshot region; the model's [CreditProviderId.regionLabel] in English. */
@StringRes
fun creditRegionLabelRes(region: ProviderRegion): Int =
    if (region == ProviderRegion.CN) R.string.credit_region_china else R.string.credit_region_international

/**
 * When a provider's team id has to be filled in. xAI needs one for a key that names no team;
 * Vercel's is optional and only narrows a key or token that covers several teams.
 */
@StringRes
fun creditTeamHintRes(provider: CreditProviderId): Int =
    if (provider == CreditProviderId.XAI) R.string.credit_team_hint_xai else R.string.credit_team_hint_vercel

/** Why a provider cannot be tracked; null for a reason this mapping does not know. */
@StringRes
fun creditUnavailableReasonRes(provider: CreditUnavailableProvider): Int? = when (provider.id) {
    "anthropic", "openai" -> R.string.credit_unavailable_no_balance_endpoint
    else -> null
}

/**
 * The region label of a provider's picker segment; the model's [ProviderRegion.label] in English.
 * Null where that label is only the region id.
 */
@StringRes
fun providerRegionLabelRes(provider: ProviderId, region: ProviderRegion): Int? = when (provider to region) {
    ProviderId.KIMI to ProviderRegion.CN -> R.string.region_kimi_china
    ProviderId.KIMI to ProviderRegion.INTL -> R.string.region_kimi_international
    ProviderId.ZAI to ProviderRegion.CN -> R.string.region_zai_china
    ProviderId.ZAI to ProviderRegion.INTL -> R.string.region_zai_international
    else -> null
}

/**
 * The sentence above the key field of a provider that is connected with a pasted key; the
 * provider's own `LoginChallenge.ApiKey.hint` in English. Null for a provider this does not know.
 */
@StringRes
fun apiKeyHintRes(provider: ProviderId): Int? = when (provider) {
    ProviderId.ZAI -> R.string.add_api_key_hint_zai
    ProviderId.KIMI -> R.string.add_api_key_hint_kimi
    ProviderId.OPENCODE -> R.string.add_api_key_hint_opencode
    ProviderId.OLLAMA -> R.string.add_api_key_hint_ollama
    ProviderId.MINIMAX -> R.string.add_api_key_hint_minimax
    else -> null
}

/**
 * A balance label as the credit cards show it. The parsers write these and they are stored
 * with the balance, so the English text is data to match and these are what the screen prints;
 * anything else is shown as stored.
 */
@StringRes
fun creditBalanceLabelRes(label: String): Int? = when (label) {
    "Key limit" -> R.string.credit_balance_key_limit
    "Daily key limit" -> R.string.credit_balance_daily_key_limit
    "Weekly key limit" -> R.string.credit_balance_weekly_key_limit
    "Monthly key limit" -> R.string.credit_balance_monthly_key_limit
    "No limit" -> R.string.credit_balance_no_limit
    "Unavailable" -> R.string.credit_balance_unavailable
    "Token Pass" -> R.string.credit_balance_token_pass
    "Prepaid balance" -> R.string.credit_balance_prepaid
    "Purchased" -> R.string.credit_balance_purchased
    else -> null
}

/**
 * The lifetime spend a "Used: 4.50" label carries — the figure the parsers write, as a plain
 * decimal with no currency symbol, so the screen can print it in the user's own money format.
 * Null for every other label, which [creditBalanceLabelRes] then handles.
 */
fun creditBalanceUsedAmount(label: String): BigDecimal? {
    val figure = USED_LABEL.matchEntire(label)?.groupValues?.get(1) ?: return null
    return runCatching { BigDecimal(figure) }.getOrNull()
}

private val USED_LABEL = Regex("^Used: ([+-]?[0-9]+(?:\\.[0-9]+)?)$")

/**
 * The top-up and voucher spend a "Spent: 80.25 top-up, 10.00 vouchers" label carries — plain
 * decimals, so the screen prints them in the user's own money format — or null for any other
 * label. Never a share: the wallet documents no cap to measure them against.
 */
fun creditBalanceSpentAmounts(label: String): Pair<BigDecimal, BigDecimal>? {
    val match = SPENT_LABEL.matchEntire(label) ?: return null
    val topUp = runCatching { BigDecimal(match.groupValues[1]) }.getOrNull() ?: return null
    val vouchers = runCatching { BigDecimal(match.groupValues[2]) }.getOrNull() ?: return null
    return topUp to vouchers
}

private val SPENT_LABEL =
    Regex("^Spent: ([+-]?[0-9]+(?:\\.[0-9]+)?) top-up, ([+-]?[0-9]+(?:\\.[0-9]+)?) vouchers$")

private class StoredMessage(val pattern: Regex, @StringRes val res: Int)

/*
 * The failure sentences the sync layer and the providers write in English. They are stored with a
 * snapshot (`errorMessage`, a credit snapshot's `error`) when a refresh fails, and the sign-in
 * flows show the same sentences at once, so the sentence itself is the data. They are recognised by
 * shape here, at the display, and the stored text is never rewritten: a language change reads the
 * old rows in the new language, and a sentence this table does not know is shown as stored.
 *
 * The groups carry what the sentence interpolates (a host, a status code, a diagnostic detail).
 */
private val STORED_MESSAGES = listOf(
    StoredMessage(Regex("Sign-in expired — reconnect this account"), R.string.stored_sign_in_expired),
    StoredMessage(Regex("Access denied for this account"), R.string.stored_access_denied),
    StoredMessage(Regex(Regex.escape(RATE_LIMITED_PAUSE_MESSAGE)), R.string.stored_rate_limited),
    StoredMessage(Regex("Rate limited — try again shortly"), R.string.stored_rate_limit_request),
    StoredMessage(Regex("No balance could be read \\(.+ is rate limiting this key\\)\\."), R.string.stored_rate_limit_request),
    StoredMessage(Regex(Regex.escape(RATE_LIMITED_REQUEST_MESSAGE)), R.string.stored_rate_limit_request),
    StoredMessage(Regex("Provider is having trouble \\((\\d+)\\)"), R.string.stored_server_trouble),
    StoredMessage(Regex("No network connection"), R.string.stored_offline),
    StoredMessage(Regex("Unexpected response from provider"), R.string.stored_malformed),
    StoredMessage(Regex("Sign-in was cancelled"), R.string.stored_login_cancelled),
    StoredMessage(Regex("Refresh failed"), R.string.stored_refresh_failed),
    StoredMessage(Regex("Unexpected error while refreshing"), R.string.stored_unexpected),
    StoredMessage(Regex("Could not look up (.+)\\. Check the connection, or a DNS filter that may block it\\."), R.string.stored_dns),
    StoredMessage(Regex("Could not connect to (.+)\\."), R.string.stored_connect),
    StoredMessage(Regex("(.+) did not answer in time\\."), R.string.stored_timeout),
    StoredMessage(Regex("A secure connection to (.+) could not be established\\."), R.string.stored_tls),
    StoredMessage(Regex("The connection to (.+) was interrupted\\."), R.string.stored_interrupted),
    StoredMessage(Regex("Some quota values could not be read; available usage is shown\\."), R.string.stored_quota_malformed),
    StoredMessage(Regex("Reset credits could not be refreshed"), R.string.stored_reset_credits),
    StoredMessage(Regex("One billing view rejected authentication; some usage could not be refreshed\\."), R.string.stored_billing_auth),
    StoredMessage(Regex("One billing view could not be refreshed; only returned usage is shown\\."), R.string.stored_billing_failed),
    StoredMessage(Regex("Some billing values could not be read; available usage is shown\\."), R.string.stored_billing_malformed),
    StoredMessage(Regex("The key was rejected \\(HTTP (\\d+)\\)\\. Check that it is the right kind of key\\."), R.string.credit_error_rejected),
    StoredMessage(
        Regex("The provider answered in a shape this app does not understand \\((.*)\\)\\.", RegexOption.DOT_MATCHES_ALL),
        R.string.credit_error_malformed,
    ),
    StoredMessage(Regex("No balance could be read \\((.*)\\)\\.", RegexOption.DOT_MATCHES_ALL), R.string.credit_error_unavailable),
    StoredMessage(
        Regex("xAI has no prepaid balance for this team \\(HTTP 404\\)\\. Check the team id, and that the team prepays\\."),
        R.string.credit_error_xai_no_prepaid,
    ),
    StoredMessage(Regex("This xAI key is not tied to one team\\. Enter the team id from console\\.x\\.ai\\."), R.string.credit_error_team_required),
    StoredMessage(
        Regex("Vercel refused this key \\(HTTP 403\\)\\. Enter the team id the key belongs to\\."),
        R.string.credit_error_vercel_team,
    ),
    StoredMessage(Regex(Regex.escape("This key lacks the account:read scope.")), R.string.credit_error_missing_scope),
    StoredMessage(Regex("No key was entered\\."), R.string.credit_error_no_key),
    StoredMessage(Regex("The key could not be read from the device\\."), R.string.stored_credit_unreadable),
)

/**
 * A failure message as the screen shows it: translated when it is one of the app's own sentences
 * (see [STORED_MESSAGES]), otherwise exactly as it came. A status code or diagnostic inside the
 * sentence is carried over as written, because it is a diagnostic and not prose.
 */
fun storedMessageText(resources: Resources, message: String): String {
    for (entry in STORED_MESSAGES) {
        val match = entry.pattern.matchEntire(message) ?: continue
        val args = match.groupValues.drop(1).map { argument -> argument.toIntOrNull() ?: argument }
        return resources.getString(entry.res, *args.toTypedArray<Any>())
    }
    return message
}

/**
 * The string for a window label the app itself chose, or null when the label is the provider's.
 *
 * The parsers name the three horizons "5h limit", "Weekly" and "Monthly"; for a global window those
 * exact words are the app's and are translated. Ollama adds two labels of its own whose duration
 * the vendor does not state — "Session" (a legacy limit) and "Included allowance" (the plan's own
 * reset period) — and those are translated for the same reason. A model-scoped window
 * ("Weekly (Opus)") and any other label is data and is shown as stored. Deliberately matches
 * nothing but those words, so the English text of a widget is exactly the label the row carries.
 */
@StringRes
fun windowLabelRes(label: String, category: WindowCategory, scope: UsageScope): Int? {
    if (scope != UsageScope.GLOBAL) return null
    return when {
        category == WindowCategory.FIVE_HOUR && label == "5h limit" -> R.string.window_label_five_hour
        category == WindowCategory.WEEKLY && label == "Weekly" -> R.string.window_label_weekly
        category == WindowCategory.MONTHLY && label == "Monthly" -> R.string.window_label_monthly
        category == WindowCategory.OTHER && label == "Session" -> R.string.window_label_session
        category == WindowCategory.OTHER && label == "Included allowance" ->
            R.string.window_label_included_allowance
        else -> null
    }
}
