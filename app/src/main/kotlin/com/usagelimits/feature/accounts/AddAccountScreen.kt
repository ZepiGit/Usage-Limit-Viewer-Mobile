package com.usagelimits.feature.accounts

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import com.usagelimits.ui.theme.LocalMotionEnabled
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.ui.AppIcons
import androidx.compose.material3.Icon
import com.usagelimits.ui.components.Dot
import com.usagelimits.ui.components.InkButton
import com.usagelimits.ui.components.LedgerField
import com.usagelimits.ui.components.OutlineButton
import com.usagelimits.ui.components.RuleDivider
import com.usagelimits.ui.components.ScreenTitle
import com.usagelimits.ui.components.Spinner
import com.usagelimits.ui.components.TextAction
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors

/**
 * Add-account flow.
 *
 * Each provider's step is described in plain language before the browser opens, because an
 * unexplained jump to an OAuth page is exactly what a phishing flow looks like.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddAccountScreen(
    state: AddAccountState,
    providers: List<ProviderId>,
    onStart: (ProviderId) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onSubmitApiKey: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The pasted-key way in, for a provider that offers one beside its flow. */
    onStartWithKey: (ProviderId) -> Unit = {},
    /** The device-code way in, for a provider whose browser redirect may not land. */
    onStartWithDeviceCode: (ProviderId) -> Unit = {},
    /** The region picked for Kimi or Z.ai, before the code or key step. */
    onChooseRegion: (ProviderId, ProviderRegion) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val activeState by rememberUpdatedState(state)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Ground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenTitle("Add account")

        Crossfade(
            targetState = state,
            animationSpec = tween(if (LocalMotionEnabled.current) 180 else 0),
            label = "signInStep",
        ) { displayedState ->
            // Crossfade remembers outgoing content, so read the latest state through a State.
            val acceptsInput = { displayedState == activeState }
            Column(
                modifier = if (acceptsInput()) Modifier else Modifier.clearAndSetSemantics {},
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (val state = displayedState) {
                    is AddAccountState.PickProvider -> {
                        Text(
                            text = "You can connect several accounts per provider.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = UsageColors.Muted,
                        )
                        Spacer(Modifier.height(4.dp))
                        UsageCard(contentPadding = PaddingValues(0.dp)) {
                            providers.forEachIndexed { index, provider ->
                                if (index > 0) RuleDivider(Modifier.padding(horizontal = 16.dp))
                                ProviderRow(provider) { if (acceptsInput()) onStart(provider) }
                            }
                        }
                    }

                    is AddAccountState.ChoosingRegion -> {
                        UsageCard {
                            Text(
                                text = "${state.provider.displayName} region",
                                style = MaterialTheme.typography.titleMedium,
                                color = UsageColors.Ink,
                            )
                            Text(
                                text = "Keys and sign-ins only work on the service they were issued by. " +
                                    "Pick the one your account is on.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = UsageColors.Muted,
                            )
                            Spacer(Modifier.height(14.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProviderRegion.options(state.provider).forEach { option ->
                                    OutlineButton(
                                        option.label(state.provider),
                                        { if (acceptsInput()) onChooseRegion(state.provider, option) },
                                        Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                        TextAction("Cancel", { if (acceptsInput()) onCancel() }, color = UsageColors.Muted)
                    }

                    is AddAccountState.Starting -> LoadingCard("Contacting ${state.provider.displayName}…")

                    is AddAccountState.AwaitingDeviceCode -> {
                        // Reading the code off the screen and typing it into a browser on the same phone
                        // means holding eight characters in your head while switching apps. The code goes
                        // to the clipboard the moment it exists, so the sign-in page needs a paste and
                        // nothing else.
                        val clipboard = LocalClipboardManager.current
                        val uriHandler = LocalUriHandler.current
                        var copied by remember(state.userCode) { mutableStateOf(false) }
                        val copy = {
                            clipboard.setText(AnnotatedString(state.userCode))
                            copied = true
                        }
                        LaunchedEffect(state.userCode) { copy() }

                        UsageCard {
                            Text(
                                text = "Enter this code",
                                style = MaterialTheme.typography.titleMedium,
                                color = UsageColors.Ink,
                            )
                            Text(
                                text = "Your browser is opening ${state.verificationUri}. Paste the code " +
                                    "there — this app never sees your password.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = UsageColors.Muted,
                            )
                            Spacer(Modifier.height(14.dp))
                            // The sheet's lead figure: a code, so the figure face, in ink.
                            Text(
                                text = state.userCode,
                                style = MaterialTheme.typography.headlineMedium,
                                // 0.04em, enough to read the code a character at a time.
                                letterSpacing = 1.2.sp,
                                color = UsageColors.Ink,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // The code itself is the biggest target on the card, so it is also
                                    // the copy button.
                                    .clickable(onClick = { if (acceptsInput()) copy() }),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (copied) "Copied to your clipboard" else "Tap the code to copy",
                                style = MaterialTheme.typography.bodySmall,
                                color = UsageColors.Faint,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                InkButton("Copy code", { if (acceptsInput()) copy() }, Modifier.weight(1f))
                                // The browser was opened once already. This is for the times it was not —
                                // no default browser, the tab dismissed, the wrong profile — where the
                                // flow is otherwise dead with a code and nowhere to put it.
                                OutlineButton("Open page", { if (acceptsInput()) uriHandler.openUri(state.verificationUri) }, Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spinner()
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Waiting for approval…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = UsageColors.Muted,
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.Center) {
                            if (state.keyAlternative) {
                                // The flow is the default because it costs the user nothing. The key
                                // is for when the flow's account cannot reach the API yet.
                                TextAction("Use an API key instead", { if (acceptsInput()) onStartWithKey(state.provider) })
                            }
                            TextAction("Cancel", { if (acceptsInput()) onCancel() }, color = UsageColors.Muted)
                        }
                    }

                    is AddAccountState.AwaitingApiKey -> {
                        val uriHandler = LocalUriHandler.current
                        var key by remember(state.provider) { mutableStateOf("") }

                        UsageCard {
                            Text(
                                text = "Paste your ${state.provider.displayName} key",
                                style = MaterialTheme.typography.titleMedium,
                                color = UsageColors.Ink,
                            )
                            Text(
                                text = state.hint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = UsageColors.Muted,
                            )
                            Spacer(Modifier.height(14.dp))
                            LedgerField(
                                label = "API key",
                                value = key,
                                onValueChange = { if (acceptsInput()) key = it },
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                InkButton("Connect", { if (acceptsInput()) onSubmitApiKey(key) }, Modifier.weight(1f), enabled = key.isNotBlank())
                                OutlineButton("Open console", { if (acceptsInput()) uriHandler.openUri(state.consoleUrl) }, Modifier.weight(1f))
                            }
                        }
                        TextAction("Cancel", { if (acceptsInput()) onCancel() }, color = UsageColors.Muted)
                    }

                    is AddAccountState.AwaitingBrowser -> {
                        val uriHandler = LocalUriHandler.current
                        LoadingCard(
                            "Complete the sign-in in your browser. You'll come straight back here.",
                        )
                        if (state.deviceCodeAlternative) {
                            // The browser flow needs the browser to reach this app on
                            // localhost, and not every phone lets it. The code needs nothing
                            // of the sort, so it is offered right where the wait would
                            // otherwise run out the clock.
                            Text(
                                text = "Browser not coming back? Sign in with a code instead — " +
                                    "you type it on the provider's page and no redirect is needed.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = UsageColors.Muted,
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.Center) {
                            if (state.deviceCodeAlternative) {
                                TextAction("Use a device code", { if (acceptsInput()) onStartWithDeviceCode(state.provider) })
                            }
                            state.authorizationUrl?.let { url ->
                                TextAction("Open page again", { if (acceptsInput()) uriHandler.openUri(url) })
                            }
                            TextAction("Cancel", { if (acceptsInput()) onCancel() }, color = UsageColors.Muted)
                        }
                    }

                    is AddAccountState.Success -> {
                        UsageCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Dot(UsageColors.Positive)
                                Text(
                                    text = "Connected",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = UsageColors.Ink,
                                )
                            }
                            Text(
                                text = "${state.accountLabel} is now being monitored.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = UsageColors.Muted,
                            )
                        }
                        InkButton("Done", { if (acceptsInput()) onDone() }, Modifier.fillMaxWidth())
                    }

                    is AddAccountState.Failed -> {
                        UsageCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Dot(UsageColors.Negative)
                                Text(
                                    text = "Sign-in failed",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = UsageColors.Ink,
                                )
                            }
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = UsageColors.Muted,
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.Center) {
                            state.provider?.let { provider ->
                                InkButton(
                                    "Try again",
                                    // The same way in as the attempt that failed: someone who
                                    // chose the code because the browser never came back
                                    // must not be sent back to the browser by "try again".
                                    {
                                        if (acceptsInput()) {
                                            if (state.viaDeviceCode) onStartWithDeviceCode(provider) else onStart(provider)
                                        }
                                    },
                                )
                                if (state.keyAlternative) {
                                    TextAction("Use an API key", { if (acceptsInput()) onStartWithKey(provider) })
                                }
                                if (state.deviceCodeAlternative) {
                                    if (state.viaDeviceCode) {
                                        TextAction("Use the browser", { if (acceptsInput()) onStart(provider) })
                                    } else {
                                        TextAction("Use a device code", { if (acceptsInput()) onStartWithDeviceCode(provider) })
                                    }
                                }
                            }
                            TextAction("Back", { if (acceptsInput()) onCancel() }, color = UsageColors.Muted)
                        }
                    }
                }
            }
        }
    }
}

/** A 56dp ledger row: logo, name, how you sign in, chevron. */
@Composable
private fun ProviderRow(provider: ProviderId, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.usagelimits.ui.ProviderBadge(provider = provider, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = UsageColors.Ink,
            )
            Text(
                text = loginHint(provider),
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.Muted,
            )
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = UsageColors.Faint, modifier = Modifier.size(16.dp))
    }
}

/** Sets expectations before the browser opens — device code versus a redirect back. */
private fun loginHint(provider: ProviderId): String = when (provider) {
    ProviderId.CODEX -> "Sign in in your browser"
    ProviderId.XAI -> "Sign in with a device code"
    ProviderId.CLAUDE -> "Sign in in your browser"
    ProviderId.ANTIGRAVITY -> "Sign in with Google"
    ProviderId.KIMI -> "Sign in with a device code"
    ProviderId.DEVIN -> "Sign in in your browser"
    ProviderId.META -> "Sign in with a device code"
    ProviderId.ZAI -> "Paste an API key"
    ProviderId.OPENCODE -> "Paste an API key"
}

@Composable
private fun LoadingCard(message: String) {
    UsageCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner()
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.Muted,
            )
        }
    }
}
