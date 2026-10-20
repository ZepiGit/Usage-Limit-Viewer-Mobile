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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.ui.components.InkButton
import com.usagelimits.ui.components.LedgerField
import com.usagelimits.ui.components.OutlineButton
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors
import java.math.BigDecimal

private const val REMOVE_BODY = "Its key is deleted from this device. It is not revoked at the provider: do that " +
    "in the provider's console if the key may have leaked."

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
            Text("This credit account is no longer available.", style = MaterialTheme.typography.bodyLarge, color = UsageColors.Muted)
        }
        return
    }

    var showRemove by remember { mutableStateOf(false) }
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
            Text("Edit", style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink)
            Spacer(Modifier.height(12.dp))
            LedgerField(label = "Name", value = label, onValueChange = { label = it })
            Spacer(Modifier.height(12.dp))
            LedgerField(
                label = "Low-balance figure", value = threshold, onValueChange = { threshold = it },
                isError = parsed == ThresholdInput.Invalid,
                supporting = if (parsed == ThresholdInput.Invalid) "Enter a number above zero, or leave blank for the default."
                else "Blank uses the default: 5, or 35 for CNY.",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.height(16.dp))
            // The one filled primary on this screen, at the panel's bottom edge.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InkButton(
                    "Save", { onSave(label, (parsed as? ThresholdInput.Value)?.amount) },
                    enabled = parsed != ThresholdInput.Invalid && label.isNotBlank(),
                )
            }
        }

        OutlineButton("Refresh now", onRefresh, Modifier.fillMaxWidth())

        // Destructive and rare: last, with the consequence stated above it.
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(REMOVE_BODY, style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
            OutlineButton("Remove credit account", { showRemove = true }, Modifier.fillMaxWidth(), color = UsageColors.NegativeText)
        }
    }

    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            containerColor = UsageColors.Raised,
            title = { Text("Remove credit account?", style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink) },
            text = { Text(REMOVE_BODY, color = UsageColors.Muted) },
            confirmButton = {
                TextButton(onClick = { showRemove = false; onRemove() }) { Text("Remove", color = UsageColors.NegativeText) }
            },
            dismissButton = {
                TextButton(onClick = { showRemove = false }) { Text("Cancel", color = UsageColors.Muted) }
            },
        )
    }
}
