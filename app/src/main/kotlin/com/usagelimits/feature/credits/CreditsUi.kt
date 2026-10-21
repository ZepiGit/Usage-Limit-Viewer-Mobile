package com.usagelimits.feature.credits

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.usagelimits.R
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.CreditFormat
import com.usagelimits.core.model.CreditNumbers
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSeverity
import com.usagelimits.core.model.Severity
import com.usagelimits.core.time.Countdown
import com.usagelimits.ui.AppIcons
import com.usagelimits.ui.components.ErrorBlock
import com.usagelimits.ui.components.StatusMark
import com.usagelimits.ui.components.UsageBar
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors
import java.math.BigDecimal

@DrawableRes
internal fun creditIcon(id: CreditProviderId): Int = when (id) {
    CreditProviderId.OPENROUTER -> R.drawable.icon_credit_openrouter
    CreditProviderId.DEEPSEEK -> R.drawable.icon_credit_deepseek
    CreditProviderId.MOONSHOT -> R.drawable.icon_credit_moonshot
    CreditProviderId.XAI -> R.drawable.icon_credit_xai
    CreditProviderId.FAL -> R.drawable.icon_credit_fal
    CreditProviderId.NOVITA -> R.drawable.icon_credit_novita
}

/**
 * The low-balance field as typed. Blank clears the figure; a comma is accepted as the decimal
 * mark because that is what a German keyboard offers.
 */
internal sealed interface ThresholdInput {
    data object Blank : ThresholdInput
    data object Invalid : ThresholdInput
    data class Value(val amount: BigDecimal) : ThresholdInput
}

internal fun parseThreshold(text: String): ThresholdInput {
    if (text.isBlank()) return ThresholdInput.Blank
    val amount = CreditNumbers.parse(text.trim().replace(',', '.'))
    return if (amount != null && amount.signum() > 0) ThresholdInput.Value(amount) else ThresholdInput.Invalid
}

/** One credit account: identity, status, the amount left, and when it was read. */
@Composable
fun CreditCard(
    entry: CreditEntry,
    nowMs: Long,
    staleAfterMs: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** Retry beside a failed read; null hides it. */
    onRetry: (() -> Unit)? = null,
) {
    val account = entry.account
    val snapshot = entry.snapshot
    val severity = CreditSeverity.of(entry, nowMs, staleAfterMs)
    val balances = CreditSeverity.worstFirst(entry)

    UsageCard(modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp)) {
            Image(painterResource(creditIcon(account.provider)), null, Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(account.label, style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink, maxLines = 1)
                Text(
                    account.provider.displayName + " · " +
                        Countdown.freshnessLabel(snapshot?.takeIf { it.balances.isNotEmpty() }?.fetchedAt, nowMs),
                    style = MaterialTheme.typography.bodySmall, color = UsageColors.Faint, maxLines = 1,
                )
            }
            StatusMark(severity)
            if (onClick != null) {
                Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterEnd) {
                    Icon(AppIcons.ChevronRight, contentDescription = null, tint = UsageColors.Faint, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (balances.isEmpty()) {
            if (snapshot?.error != null) {
                ErrorBlock(snapshot.error, onRetry = onRetry)
            } else {
                Text(text = "No balance read yet", style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                balances.forEach { balance -> BalanceRow(balance, severity) }
            }
            if (snapshot?.error != null) {
                ErrorBlock(
                    message = "${snapshot.error} · showing last known balance",
                    modifier = Modifier.padding(top = 12.dp),
                    onRetry = onRetry,
                )
            }
        }
    }
}

/** The amount in ink (never severity-coloured, a real zero included), its cap, and a data bar. */
@Composable
private fun BalanceRow(balance: CreditBalance, accountSeverity: Severity) {
    // A stale or failed account keeps the grey of its age in the bar.
    val barSeverity = if (accountSeverity == Severity.STALE || accountSeverity == Severity.ERROR) Severity.STALE else Severity.HEALTHY
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = CreditFormat.line(balance),
                style = MaterialTheme.typography.titleLarge,
                color = UsageColors.Ink,
            )
            balance.total?.takeIf { !balance.unlimited && balance.remainingPercent != null }?.let { total ->
                Text(
                    text = "of " + CreditFormat.string(total, balance.currency),
                    modifier = Modifier.weight(1f).padding(bottom = 2.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = UsageColors.Muted,
                )
            } ?: Spacer(Modifier.weight(1f))
            if (!balance.unlimited) balance.label?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
            }
        }
        balance.remainingPercent?.let { percent ->
            UsageBar(percent, barSeverity, Modifier.fillMaxWidth())
        }
        // An uncapped key has no bar: its existing label says so instead.
        if (balance.unlimited) balance.label?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted)
        }
    }
}
