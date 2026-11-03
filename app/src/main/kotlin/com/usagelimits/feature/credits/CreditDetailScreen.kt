package com.usagelimits.feature.credits

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.usagelimits.R
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.ui.components.InkButton
import com.usagelimits.ui.components.LedgerField
import com.usagelimits.ui.components.OutlineButton
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors
import java.math.BigDecimal

/** One credit account in full: its balance, the name and low-balance figure to edit, and removal. */
@Composable
fun CreditDetailScreen(
    entry: CreditEntry?,
    nowMs: Long,
    staleAfterMs: Long,
    onSave: (label: String, lowThreshold: BigDecimal?) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entry == null) {
        Column(modifier.fillMaxSize().background(UsageColors.Ground).padding(16.dp)) {
            Text(stringResource(R.string.credit_unavailable), style = MaterialTheme.typography.bodyLarge, color = UsageColors.Muted)
        }
        return
    }

    var showRemove by remember { mutableStateOf(false) }
    val removeBody = stringResource(R.string.credit_remove_body)
    var label by remember(entry.id) { mutableStateOf(entry.account.label) }
    var threshold by remember(entry.id) { mutableStateOf(entry.account.customThreshold?.toPlainString().orEmpty()) }
    val parsed = parseThreshold(threshold)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Ground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CreditCard(entry, nowMs, staleAfterMs, onRetry = onRefresh)

        UsageCard {
            Text(stringResource(R.string.credit_edit), style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink)
            Spacer(Modifier.height(12.dp))
            LedgerField(label = stringResource(R.string.credit_detail_name_label), value = label, onValueChange = { label = it })
            Spacer(Modifier.height(12.dp))
            LedgerField(
                label = stringResource(R.string.credit_detail_threshold_label), value = threshold, onValueChange = { threshold = it },
                isError = parsed == ThresholdInput.Invalid,
                supporting = stringResource(
                    if (parsed == ThresholdInput.Invalid) R.string.credit_threshold_invalid else R.string.credit_threshold_blank,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.height(16.dp))
            // The one filled primary on this screen, at the panel's bottom edge.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InkButton(
                    stringResource(R.string.credit_save), { onSave(label, (parsed as? ThresholdInput.Value)?.amount) },
                    enabled = parsed != ThresholdInput.Invalid && label.isNotBlank(),
                )
            }
        }

        OutlineButton(stringResource(R.string.account_refresh_now), onRefresh, Modifier.fillMaxWidth())

        // Destructive and rare: last, with the consequence stated above it.
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(removeBody, style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
            OutlineButton(stringResource(R.string.credit_remove), { showRemove = true }, Modifier.fillMaxWidth(), color = UsageColors.NegativeText)
        }
    }

    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            containerColor = UsageColors.Raised,
            title = { Text(stringResource(R.string.credit_remove_title), style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink) },
            text = { Text(removeBody, color = UsageColors.Muted) },
            confirmButton = {
                TextButton(onClick = { showRemove = false; onRemove() }) { Text(stringResource(R.string.account_remove_confirm), color = UsageColors.NegativeText) }
            },
            dismissButton = {
                TextButton(onClick = { showRemove = false }) { Text(stringResource(R.string.action_cancel), color = UsageColors.Muted) }
            },
        )
    }
}
