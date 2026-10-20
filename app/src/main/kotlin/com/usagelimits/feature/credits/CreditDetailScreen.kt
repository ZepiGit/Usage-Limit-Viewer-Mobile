package com.usagelimits.feature.credits

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
        Column(modifier.fillMaxSize().background(UsageColors.Background).padding(16.dp)) {
            Text("This credit account is no longer available.", style = MaterialTheme.typography.bodyLarge, color = UsageColors.TextSecondary)
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
            .background(UsageColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CreditCard(entry, nowMs, staleAfterMs)

        UsageCard {
            Text("Edit", style = MaterialTheme.typography.titleMedium, color = UsageColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = label, onValueChange = { label = it }, singleLine = true,
                label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
                colors = creditFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = threshold, onValueChange = { threshold = it }, singleLine = true,
                label = { Text("Low-balance figure") },
                isError = parsed == ThresholdInput.Invalid,
                supportingText = {
                    Text(
                        if (parsed == ThresholdInput.Invalid) "Enter a number above zero, or leave blank for the default."
                        else "Blank uses the default: 5, or 35 for CNY.",
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(), colors = creditFieldColors(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onSave(label, (parsed as? ThresholdInput.Value)?.amount) },
                enabled = parsed != ThresholdInput.Invalid && label.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = UsageColors.Terracotta, contentColor = UsageColors.Background),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }

        UsageCard {
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = UsageColors.SurfaceElevated, contentColor = UsageColors.TextPrimary),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Refresh now") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showRemove = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Remove credit account", color = UsageColors.Red)
            }
        }
    }

    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            containerColor = UsageColors.SurfaceElevated,
            title = { Text("Remove credit account?", color = UsageColors.TextPrimary) },
            text = {
                Text(
                    "Its key is deleted from this device. It is not revoked at the provider: do that " +
                        "in the provider's console if the key may have leaked.",
                    color = UsageColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { showRemove = false; onRemove() }) { Text("Remove", color = UsageColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showRemove = false }) { Text("Cancel", color = UsageColors.TextSecondary) }
            },
        )
    }
}

