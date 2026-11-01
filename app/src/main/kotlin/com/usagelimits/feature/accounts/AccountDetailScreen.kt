package com.usagelimits.feature.accounts

import com.usagelimits.core.model.title
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.usagelimits.R
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.Severity
import com.usagelimits.core.time.Countdown
import com.usagelimits.ui.components.Dot
import com.usagelimits.ui.components.ErrorBlock
import com.usagelimits.ui.components.InkButton
import com.usagelimits.ui.components.OutlineButton
import com.usagelimits.ui.components.RuleDivider
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.Spinner
import com.usagelimits.ui.components.StatusMark
import com.usagelimits.ui.components.ToggleRow
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.components.UsageWindowRow
import com.usagelimits.ui.text.rememberCountdownTexts
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageType

/**
 * One account in full: every window, its reset credits, and account management.
 *
 * This is the only place a reset credit can be spent, and only behind a confirmation dialog —
 * see [ResetCreditCard].
 */
@Composable
fun AccountDetailScreen(
    usage: AccountUsage?,
    nowMs: Long,
    staleAfterMs: Long,
    // Required, never defaulted: a defaulted mode would leave this screen silently in LEFT.
    percentDisplay: PercentDisplay,
    resetInFlight: Boolean,
    supportsResetCredits: Boolean,
    onRefresh: () -> Unit,
    onConsumeResetCredit: () -> Unit,
    onRemove: () -> Unit,
    notificationsEnabled: Boolean,
    onNotificationsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onReconnect: () -> Unit = {},
) {
    if (usage == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(UsageColors.Ground)
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.account_unavailable),
                style = MaterialTheme.typography.bodyLarge,
                color = UsageColors.Muted,
            )
        }
        return
    }

    var showRemoveDialog by remember { mutableStateOf(false) }
    val removeBody = stringResource(R.string.account_remove_body)
    val countdownTexts = rememberCountdownTexts()
    val snapshot = usage.snapshot
    val severity = snapshot?.severityAt(nowMs, staleAfterMs)
    // Old or failed: the figures stay, in grey.
    val stale = severity == Severity.STALE || severity == Severity.ERROR

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Ground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.usagelimits.ui.ProviderBadge(provider = usage.account.provider, size = 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = usage.account.title(),
                        style = MaterialTheme.typography.headlineLarge,
                        color = UsageColors.Ink,
                    )
                    Text(
                        text = usage.account.maskedEmail ?: usage.account.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = UsageColors.Muted,
                    )
                }
                severity?.let {
                    StatusMark(it, label = if (snapshot.connectionStatus == com.usagelimits.core.model.ConnectionStatus.RECONNECT_REQUIRED) stringResource(R.string.status_reconnect) else null)
                }
            }
        }

        item {
            val attempt = if (snapshot != null && snapshot.status != com.usagelimits.core.model.SnapshotStatus.OK) {
                val age = (nowMs - snapshot.lastAttemptAt).coerceAtLeast(0)
                " · " + if (age < 60_000) stringResource(R.string.account_last_attempt_just_now)
                    else stringResource(R.string.account_last_attempt_ago, Countdown.format(age, countdownTexts))
            } else ""
            Text(
                text = Countdown.freshnessLabel(usage.measurementAt, nowMs, countdownTexts) + attempt,
                style = MaterialTheme.typography.bodySmall,
                color = UsageColors.Faint,
            )
        }
        if (!snapshot?.restrictedWindows.isNullOrEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(UsageColors.Warning, size = 6.dp)
                    Text(
                        text = stringResource(R.string.restricted_windows, snapshot!!.restrictedWindows.joinToString { it.label }),
                        style = MaterialTheme.typography.bodyMedium,
                        color = UsageColors.Muted,
                    )
                }
            }
        }

        if (snapshot?.connectionStatus == com.usagelimits.core.model.ConnectionStatus.RECONNECT_REQUIRED) {
            item {
                // The one filled primary on this screen.
                InkButton(stringResource(R.string.account_reconnect), onReconnect, Modifier.fillMaxWidth())
            }
        }
        snapshot?.errorMessage?.let { error ->
            item {
                val partial = snapshot.status == com.usagelimits.core.model.SnapshotStatus.PARTIAL
                UsageCard {
                    ErrorBlock(
                        message = error,
                        dot = if (partial) UsageColors.Warning else UsageColors.Negative,
                        detail = when {
                            partial -> stringResource(R.string.account_detail_partial)
                            usage.measurementAt == null -> stringResource(R.string.account_detail_no_measurement)
                            else -> stringResource(R.string.account_detail_last_loaded)
                        },
                        onRetry = onRefresh,
                    )
                }
            }
        }

        val windows = snapshot?.windows.orEmpty()
        if (windows.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.account_section_limits)) }
            // One ledger card; grouped windows keep their heading so a shared quota bucket
            // reads as one thing.
            item(key = "limits") {
                val rowPadding = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                val ungrouped = windows.filter { it.group == null }
                val groups = windows.filter { it.group != null }.groupBy { it.group!! }
                UsageCard(contentPadding = PaddingValues(0.dp)) {
                    var first = true
                    fun divide(): Boolean { val had = !first; first = false; return had }
                    ungrouped.forEach { window ->
                        if (divide()) RuleDivider(Modifier.padding(horizontal = 16.dp))
                        UsageWindowRow(window, nowMs, percentDisplay, rowPadding, fetchedAt = snapshot?.fetchedAt, stale = stale)
                    }
                    groups.forEach { (group, rows) ->
                        if (divide()) RuleDivider(Modifier.padding(horizontal = 16.dp))
                        Text(
                            text = group,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = UsageColors.Ink,
                        )
                        rows.forEach { window ->
                            UsageWindowRow(window, nowMs, percentDisplay, rowPadding, fetchedAt = snapshot?.fetchedAt, stale = stale)
                        }
                    }
                }
            }
        }

        if (supportsResetCredits) {
            item { SectionHeader(stringResource(R.string.reset_credits_title)) }
            item {
                ResetCreditCard(
                    heldCount = snapshot?.heldResetCredits ?: 0,
                    spendableCount = snapshot?.spendableResetCredits ?: 0,
                    expiresAt = snapshot?.resetCredits?.mapNotNull { it.expiresAt }?.minOrNull(),
                    nowMs = nowMs,
                    inFlight = resetInFlight,
                    onConsume = onConsumeResetCredit,
                )
            }
        }

        item { SectionHeader(stringResource(R.string.account_section_account)) }
        item {
            UsageCard {
                // Per account, because the global switches in Settings decide WHICH kinds of
                // alert exist, not which accounts may raise them. Someone with a spare account
                // they never run down does not want to turn off "below 20%" for the account
                // they live in.
                ToggleRow(
                    title = stringResource(R.string.account_notifications_title),
                    subtitle = stringResource(R.string.account_notifications_subtitle),
                    checked = notificationsEnabled,
                    onChange = onNotificationsChange,
                )
            }
        }

        item { OutlineButton(stringResource(R.string.account_refresh_now), onRefresh, Modifier.fillMaxWidth()) }

        // Destructive and rare: its own section, last, with the consequence stated above it.
        item {
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(removeBody, style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
                OutlineButton(stringResource(R.string.account_remove), { showRemoveDialog = true }, Modifier.fillMaxWidth(), color = UsageColors.NegativeText)
            }
        }
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            containerColor = UsageColors.Raised,
            title = { Text(stringResource(R.string.account_remove_title), style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink) },
            text = { Text(removeBody, color = UsageColors.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveDialog = false
                    onRemove()
                }) { Text(stringResource(R.string.account_remove_confirm), color = UsageColors.NegativeText) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = UsageColors.Muted)
                }
            },
        )
    }
}

/**
 * Reset credits, and the only path that spends one.
 *
 * Spending is irreversible, so it is gated behind an explicit confirmation that states the
 * cost in plain words. Nothing in the app spends a credit automatically — not sync, not a
 * retry, not a widget.
 */
@Composable
private fun ResetCreditCard(
    heldCount: Int,
    spendableCount: Int,
    expiresAt: Long?,
    nowMs: Long,
    inFlight: Boolean,
    onConsume: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val countdownTexts = rememberCountdownTexts()

    // The provider reports credits held and credits that apply to the limit currently reached
    // as separate numbers. Showing the held count keeps a user who owns credits from being
    // told they have none, while gating the button on the spendable count keeps the app from
    // offering a tap the provider will refuse.
    val heldButUnspendable = heldCount > 0 && spendableCount == 0

    UsageCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (heldCount > 0) stringResource(R.string.reset_credits_available_count, heldCount)
                        else stringResource(R.string.reset_credits_none_available),
                    style = UsageType.FigureLarge,
                    color = if (heldCount > 0) UsageColors.Ink else UsageColors.Muted,
                )
                Text(
                    text = when {
                        heldButUnspendable -> stringResource(R.string.reset_credits_none_apply)
                        expiresAt != null -> stringResource(
                            R.string.reset_credits_expires_in, Countdown.format(expiresAt - nowMs, countdownTexts),
                        )
                        else -> stringResource(R.string.reset_credits_empty_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.Muted,
                )
            }
            if (inFlight) Spinner()
        }
        if (spendableCount > 0) {
            Spacer(Modifier.height(12.dp))
            // Spends a credit, so it is deliberately not the screen's primary.
            OutlineButton(stringResource(R.string.reset_credits_reset_limit), { showConfirm = true }, Modifier.fillMaxWidth(), enabled = !inFlight)
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = UsageColors.Raised,
            title = { Text(stringResource(R.string.reset_credits_confirm_title), style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink) },
            text = {
                Text(stringResource(R.string.reset_credits_confirm_body), color = UsageColors.Muted)
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onConsume()
                }) { Text(stringResource(R.string.reset_credits_confirm_action), color = UsageColors.Ink) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.action_cancel), color = UsageColors.Muted)
                }
            },
        )
    }
}
