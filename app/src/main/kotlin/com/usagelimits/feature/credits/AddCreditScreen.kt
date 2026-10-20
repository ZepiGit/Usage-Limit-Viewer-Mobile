package com.usagelimits.feature.credits

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.ui.components.IconBadge
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors


/**
 * Add a credit balance: pick the provider, paste a key, and it is tried against the provider
 * before anything is saved. Anthropic and OpenAI are listed, disabled, with the reason.
 */
@Composable
fun AddCreditScreen(
    state: AddCreditState,
    onSubmit: (CreditProviderId, key: String, label: String, ProviderRegion, teamId: String, lowThreshold: String) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<CreditProviderId?>(null) }
    val busy = state is AddCreditState.Validating

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Add credit balance", style = MaterialTheme.typography.headlineLarge, color = UsageColors.TextPrimary)

        if (state is AddCreditState.Added) {
            UsageCard {
                Text("Added", style = MaterialTheme.typography.titleMedium, color = UsageColors.Green)
                Text(
                    "${state.label} is now being tracked.",
                    style = MaterialTheme.typography.bodyMedium, color = UsageColors.TextSecondary,
                )
            }
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = UsageColors.Terracotta, contentColor = UsageColors.Background),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Done") }
            return@Column
        }

        Text(
            "Prepaid balances for API keys. The key is checked against the provider before it is saved " +
                "and is stored only in this device's Keystore.",
            style = MaterialTheme.typography.bodyLarge, color = UsageColors.TextSecondary,
        )

        CreditProviderId.entries.forEach { provider ->
            ProviderRow(
                name = provider.displayName, subtitle = provider.keyHint, symbol = creditSymbol(provider),
                selected = provider == selected, enabled = !busy,
            ) { selected = provider }
        }
        CreditProviderId.notPossible.forEach { unavailable ->
            ProviderRow(
                name = unavailable.displayName, subtitle = unavailable.reason, symbol = "–",
                selected = false, enabled = false,
            ) {}
        }

        selected?.let { provider ->
            KeyForm(provider = provider, state = state, onSubmit = onSubmit)
        }

        TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel", color = UsageColors.TextSecondary) }
    }
}

@Composable
private fun ProviderRow(
    name: String, subtitle: String, symbol: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled || selected) 1f else 0.5f)
            .clip(shape)
            .background(UsageColors.Surface)
            .border(1.dp, if (selected) UsageColors.Terracotta else UsageColors.Surface, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(symbol, UsageColors.Terracotta, UsageColors.TerracottaSurface)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = UsageColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = UsageColors.TextSecondary)
        }
    }
}

@Composable
private fun KeyForm(
    provider: CreditProviderId,
    state: AddCreditState,
    onSubmit: (CreditProviderId, String, String, ProviderRegion, String, String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var key by remember(provider) { mutableStateOf("") }
    var label by remember(provider) { mutableStateOf("") }
    var teamId by remember(provider) { mutableStateOf("") }
    var threshold by remember(provider) { mutableStateOf("") }
    var region by remember(provider) { mutableStateOf(CreditProviderId.regions.first()) }
    val busy = state is AddCreditState.Validating

    UsageCard {
        Text("Connect ${provider.displayName}", style = MaterialTheme.typography.titleMedium, color = UsageColors.TextPrimary)
        Spacer(Modifier.height(10.dp))

        if (provider.hasRegion) {
            Text(
                "Keys only work on the service they were issued by. Pick the one your account is on.",
                style = MaterialTheme.typography.bodyMedium, color = UsageColors.TextSecondary,
            )
            Spacer(Modifier.height(6.dp))
            CreditProviderId.regions.forEach { option ->
                Button(
                    onClick = { region = option },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (option == region) UsageColors.Terracotta else UsageColors.TerracottaSurface,
                        contentColor = if (option == region) UsageColors.Background else UsageColors.Terracotta,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(CreditProviderId.regionLabel(option)) }
            }
            Spacer(Modifier.height(10.dp))
        }

        OutlinedTextField(
            value = label, onValueChange = { label = it }, singleLine = true, enabled = !busy,
            label = { Text("Name (optional)") }, placeholder = { Text(provider.displayName) },
            modifier = Modifier.fillMaxWidth(), colors = creditFieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        if (provider.acceptsTeamId) {
            OutlinedTextField(
                value = teamId, onValueChange = { teamId = it }, singleLine = true, enabled = !busy,
                label = { Text("Team id (only if the key is not tied to one team)") },
                modifier = Modifier.fillMaxWidth(), colors = creditFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = threshold, onValueChange = { threshold = it }, singleLine = true, enabled = !busy,
            label = { Text("Low-balance figure (optional)") },
            supportingText = { Text("Default 5, or 35 for CNY. Below it the balance reads low.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(), colors = creditFieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = key, onValueChange = { key = it }, singleLine = true, enabled = !busy,
            label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(), colors = creditFieldColors(),
        )

        if (state is AddCreditState.Failed) {
            Spacer(Modifier.height(10.dp))
            Text(state.message, style = MaterialTheme.typography.bodyMedium, color = UsageColors.Red)
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onSubmit(provider, key, label, region, teamId, threshold) },
                enabled = key.isNotBlank() && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = UsageColors.Terracotta, contentColor = UsageColors.Background),
                modifier = Modifier.weight(1f),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = UsageColors.Background)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "Checking…" else "Check and add")
            }
            Button(
                onClick = { uriHandler.openUri(provider.consoleUrl) },
                colors = ButtonDefaults.buttonColors(containerColor = UsageColors.TerracottaSurface, contentColor = UsageColors.Terracotta),
                modifier = Modifier.weight(1f),
            ) { Text("Open console") }
        }
    }
}
