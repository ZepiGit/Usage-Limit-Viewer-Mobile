package com.usagelimits.feature.credits

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.ui.components.Dot
import com.usagelimits.ui.components.ErrorBlock
import com.usagelimits.ui.components.InkButton
import com.usagelimits.ui.components.LedgerField
import com.usagelimits.ui.components.OutlineButton
import com.usagelimits.ui.components.RuleDivider
import com.usagelimits.ui.components.ScreenTitle
import com.usagelimits.ui.components.Segment
import com.usagelimits.ui.components.SegmentedRow
import com.usagelimits.ui.components.TextAction
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
    val uriHandler = LocalUriHandler.current
    val busy = state is AddCreditState.Validating

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Ground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenTitle("Add credit balance")

        if (state is AddCreditState.Added) {
            UsageCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(UsageColors.Positive)
                    Text("Added", style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink)
                }
                Text(
                    "${state.label} is now being tracked.",
                    style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted,
                )
            }
            InkButton("Done", onDone, Modifier.fillMaxWidth())
            return@Column
        }

        Text(
            "Prepaid balances for API keys. The key is checked against the provider before it is saved " +
                "and is stored only in this device's Keystore.",
            style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted,
        )

        UsageCard(contentPadding = PaddingValues(0.dp)) {
            CreditProviderId.entries.forEachIndexed { index, provider ->
                if (index > 0) RuleDivider(Modifier.padding(horizontal = 16.dp))
                ProviderRow(
                    name = provider.displayName, subtitle = provider.keyHint,
                    selected = provider == selected, enabled = !busy,
                    icon = creditIcon(provider), onOpen = { uriHandler.openUri(provider.consoleUrl) },
                ) { selected = provider }
            }
            CreditProviderId.notPossible.forEach { unavailable ->
                RuleDivider(Modifier.padding(horizontal = 16.dp))
                ProviderRow(name = unavailable.displayName, subtitle = unavailable.reason, selected = false, enabled = false) {}
            }
        }

        selected?.let { provider ->
            KeyForm(provider = provider, state = state, onSubmit = onSubmit)
        }

        TextAction("Cancel", onCancel, color = UsageColors.Muted)
    }
}

/** A 56dp ledger row; the chosen one carries a 1.5dp ink border. Unavailable rows are plain grey text. */
@Composable
private fun ProviderRow(
    name: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    @DrawableRes icon: Int? = null,
    onOpen: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (selected) Modifier.border(1.5.dp, UsageColors.Ink, MaterialTheme.shapes.medium) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Image(painterResource(icon), null, Modifier.size(30.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = if (enabled || selected) UsageColors.Ink else UsageColors.Muted)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = if (enabled || selected) UsageColors.Muted else UsageColors.Faint)
        }
        if (onOpen != null) OutlineButton("Open", onOpen, Modifier.semantics { contentDescription = "Open $name key page" })
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
        Text("Connect ${provider.displayName}", style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink)
        Spacer(Modifier.height(10.dp))

        if (provider.hasRegion) {
            Text(
                "Keys only work on the service they were issued by. Pick the one your account is on.",
                style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted,
            )
            Spacer(Modifier.height(6.dp))
            SegmentedRow {
                CreditProviderId.regions.forEach { option ->
                    Segment(CreditProviderId.regionLabel(option), option == region) { if (!busy) region = option }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        LedgerField(
            label = "Name (optional)", value = label, onValueChange = { label = it }, enabled = !busy,
            placeholder = provider.displayName,
        )
        Spacer(Modifier.height(12.dp))
        if (provider.acceptsTeamId) {
            LedgerField(
                label = "Team id (only if the key is not tied to one team)", value = teamId,
                onValueChange = { teamId = it }, enabled = !busy,
            )
            Spacer(Modifier.height(12.dp))
        }
        LedgerField(
            label = "Low-balance figure (optional)", value = threshold, onValueChange = { threshold = it }, enabled = !busy,
            supporting = "Default 5, or 35 for CNY. Below it the balance reads low.",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Spacer(Modifier.height(12.dp))
        LedgerField(
            label = "API key", value = key, onValueChange = { key = it }, enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        )

        if (state is AddCreditState.Failed) {
            ErrorBlock(state.message, Modifier.padding(top = 12.dp))
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InkButton(
                if (busy) "Checking…" else "Check and add",
                { onSubmit(provider, key, label, region, teamId, threshold) },
                Modifier.weight(1f), enabled = key.isNotBlank() && !busy, busy = busy,
            )
            OutlineButton("Open console", { uriHandler.openUri(provider.consoleUrl) }, Modifier.weight(1f))
        }
    }
}
