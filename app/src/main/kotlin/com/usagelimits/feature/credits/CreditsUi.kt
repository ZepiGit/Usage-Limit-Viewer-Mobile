package com.usagelimits.feature.credits

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.CreditFormat
import com.usagelimits.core.model.CreditNumbers
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSeverity
import com.usagelimits.core.model.Severity
import com.usagelimits.core.time.Countdown
import com.usagelimits.ui.AppIcons
import com.usagelimits.ui.components.IconBadge
import com.usagelimits.ui.components.StatusPill
import com.usagelimits.ui.components.UsageBar
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors
import java.math.BigDecimal

/** A glyph per provider; no brand marks are shipped for these. */
internal fun creditSymbol(provider: CreditProviderId): String = when (provider) {
    CreditProviderId.OPENROUTER -> "⇌"
    CreditProviderId.DEEPSEEK -> "D"
    CreditProviderId.MOONSHOT -> "☾"
    CreditProviderId.XAI -> "✕"
    CreditProviderId.FAL -> "f"
    CreditProviderId.NOVITA -> "N"
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

@Composable
internal fun creditFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = UsageColors.TextPrimary,
    unfocusedTextColor = UsageColors.TextPrimary,
    focusedBorderColor = UsageColors.Terracotta,
    unfocusedBorderColor = UsageColors.Outline,
    focusedLabelColor = UsageColors.Terracotta,
    unfocusedLabelColor = UsageColors.TextSecondary,
    cursorColor = UsageColors.Terracotta,
)

/** One credit account: identity, status, the amount left, and when it was read. */
@Composable
fun CreditCard(
    entry: CreditEntry,
    nowMs: Long,
    staleAfterMs: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val account = entry.account
    val snapshot = entry.snapshot
    val severity = CreditSeverity.of(entry, nowMs, staleAfterMs)
    val balances = CreditSeverity.worstFirst(entry)

    UsageCard(modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(creditSymbol(account.provider), UsageColors.Terracotta, UsageColors.TerracottaSurface)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(account.label, style = MaterialTheme.typography.titleMedium, color = UsageColors.TextPrimary, maxLines = 1)
                Text(account.provider.displayName, style = MaterialTheme.typography.bodyMedium, color = UsageColors.TextSecondary, maxLines = 1)
            }
            StatusPill(severity)
            if (onClick != null) {
                Icon(AppIcons.ChevronRight, contentDescription = null, tint = UsageColors.TextTertiary)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (balances.isEmpty()) {
            Text(
                text = snapshot?.error ?: "No balance read yet",
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.TextTertiary,
            )
        } else {
            balances.forEach { balance ->
                BalanceRow(balance, CreditSeverity.of(balance, account.lowThreshold(balance.currency)), severity)
                Spacer(Modifier.height(8.dp))
            }
            if (snapshot?.error != null) {
                Text(
                    text = "${snapshot.error} · showing last known balance",
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.Amber,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        Text(
            text = Countdown.freshnessLabel(snapshot?.takeIf { it.balances.isNotEmpty() }?.fetchedAt, nowMs),
            style = MaterialTheme.typography.bodySmall,
            color = UsageColors.TextTertiary,
        )
    }
}

@Composable
private fun BalanceRow(balance: CreditBalance, balanceSeverity: Severity, accountSeverity: Severity) {
    // A stale or failed account keeps the grey of its age; otherwise the figure wears its own level.
    val shown = if (accountSeverity == Severity.STALE ||
        accountSeverity == Severity.ERROR) accountSeverity else balanceSeverity
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = CreditFormat.line(balance),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = SeverityPalette.textColor(shown),
                modifier = Modifier.weight(1f),
            )
            balance.label?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = UsageColors.TextSecondary)
            }
        }
        balance.remainingPercent?.let { percent ->
            UsageBar(percent, shown, Modifier.fillMaxWidth(), height = 6.dp)
        }
    }
}

/** The overview's "add" row for credits, drawn like the accounts' one. */
@Composable
fun AddCreditCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, UsageColors.Outline, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).background(UsageColors.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Add, contentDescription = null, tint = UsageColors.Terracotta) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Add credit balance", style = MaterialTheme.typography.titleMedium, color = UsageColors.TextPrimary)
            Text("Track prepaid API credit", style = MaterialTheme.typography.bodyMedium, color = UsageColors.TextSecondary)
        }
    }
}
