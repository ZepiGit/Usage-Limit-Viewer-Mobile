package com.usagelimits.feature.overview
import sh.calvin.reorderable.ReorderableItem

import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.WindowPresentation
import com.usagelimits.core.model.presentation
import com.usagelimits.core.model.readingValidity
import com.usagelimits.core.model.title
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.usagelimits.R
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.Severity
import com.usagelimits.core.time.Countdown
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.settings.AppIcon
import com.usagelimits.feature.settings.AppIconTile
import com.usagelimits.ui.AppIcons
import com.usagelimits.ui.percentFlipLabelRes
import com.usagelimits.ui.percentSideWordRes
import com.usagelimits.ui.text.percentFigureText
import com.usagelimits.ui.text.rememberCountdownTexts
import com.usagelimits.ui.text.storedMessageText
import com.usagelimits.ui.text.windowLabelRes
import com.usagelimits.ui.components.Dot
import com.usagelimits.ui.components.EmptyBox
import com.usagelimits.ui.components.ErrorBlock
import com.usagelimits.ui.components.OutlineButton
import com.usagelimits.ui.components.RuleDivider
import com.usagelimits.ui.components.ScreenTitle
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.SkeletonBar
import com.usagelimits.ui.components.Spinner
import com.usagelimits.ui.components.StatusMark
import com.usagelimits.ui.components.TextAction
import com.usagelimits.ui.components.UsageBar
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.components.UsageWindowRow
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageType

/**
 * The landing screen: a lead strip answering "what runs out first, and when does anything come
 * back", then one card per account.
 *
 * Ordered worst-first so the account that needs attention is the one already on screen —
 * scrolling to find a problem defeats the point of a glanceable app.
 */
@Composable
fun OverviewScreen(
    state: UsageUiState,
    nowMs: Long,
    onRefresh: () -> Unit,
    onAccountClick: (String) -> Unit,
    onAddAccount: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onPercentDisplayChange: (PercentDisplay) -> Unit,
    modifier: Modifier = Modifier,
    onCreditClick: (String) -> Unit = {},
    onAddCredit: () -> Unit = {},
) {
    val listState = rememberLazyListState()

    var reordering by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var liveOrder by remember { mutableStateOf<List<AccountUsage>?>(null) }
    val shown = liveOrder?.mapNotNull { pending -> state.accounts.firstOrNull { it.account.localId == pending.account.localId } }
        ?: state.orderedAccounts(nowMs)
    val currentShown by androidx.compose.runtime.rememberUpdatedState(shown)
    val reorderState = sh.calvin.reorderable.rememberReorderableLazyListState(listState) { from, to ->
        val order = currentShown.toMutableList()
        val start = order.indexOfFirst { it.account.localId == from.key }
        val end = order.indexOfFirst { it.account.localId == to.key }
        if (start >= 0 && end >= 0 && start != end) { order.add(end, order.removeAt(start)); liveOrder = order }
    }
    val orderSaveFailed = stringResource(R.string.message_order_save_failed)
    LaunchedEffect(state.accounts, dragging, state.message) {
        val pending = liveOrder ?: return@LaunchedEffect
        if (!dragging && (state.orderedAccounts(nowMs).map { it.account.localId } == pending.map { it.account.localId } ||
                state.message == orderSaveFailed)) liveOrder = null
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Ground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { OverviewHeader(state, nowMs, onRefresh) }

        // Until the first emission `accounts` is empty only because nothing has arrived, so the
        // final layout is drawn in grey rather than flashing "no accounts".
        if (!state.loaded) {
            item { LeadSkeleton() }
            items(2) { AccountSkeleton() }
            return@LazyColumn
        }

        if (state.accounts.isNotEmpty()) item { LeadStrip(state, nowMs, onPercentDisplayChange) }

        item {
            SectionHeader(
                title = stringResource(R.string.overview_section_accounts),
                trailing = {
                    if (state.accounts.isNotEmpty()) {
                        TextAction(stringResource(if (reordering) R.string.action_done else R.string.overview_reorder), { reordering = !reordering }, color = UsageColors.Muted)
                    }
                },
            )
        }

        if (state.accounts.isEmpty()) {
            item { EmptyBox(stringResource(R.string.overview_no_accounts)) }
        }

        items(items = shown, key = { it.account.localId }) { usage ->
            ReorderableItem(reorderState, key = usage.account.localId) { isDragging ->
                AccountCard(usage = usage, nowMs = nowMs, staleAfterMs = state.staleAfterMs,
                    percentDisplay = state.settings.percentDisplay,
                    showTier = state.settings.showSubscriptionTier, showRenewal = state.settings.showRenewalTime,
                    modifier = Modifier.zIndex(if (isDragging) 1f else 0f),
                    onRetry = onRefresh,
                    dragHandle = if (!reordering) null else {
                        {
                            Box(Modifier.size(44.dp).draggableHandle(
                                onDragStarted = { dragging = true; liveOrder = currentShown },
                                onDragStopped = { dragging = false; liveOrder?.let { onReorder(it.map { item -> item.account.localId }) } },
                            ), contentAlignment = Alignment.Center) {
                                Icon(AppIcons.DragHandle, stringResource(R.string.overview_move_account, usage.account.label), tint = UsageColors.Muted)
                            }
                        }
                    },
                ) { if (!reordering) onAccountClick(usage.account.localId) }
            }
        }

        item { AddButton(stringResource(R.string.overview_add_account), stringResource(R.string.overview_add_account_hint), onAddAccount) }

        // Prepaid balances sit below the usage accounts: a different kind of number (money left,
        // no windows, no reset), so they are not mixed into the ranked list above.
        item { SectionHeader(title = stringResource(R.string.overview_section_credits)) }
        if (state.credits.isEmpty()) item { EmptyBox(stringResource(R.string.overview_no_credits)) }
        items(items = state.credits, key = { "credit-${it.id}" }) { entry ->
            com.usagelimits.feature.credits.CreditCard(
                entry = entry, nowMs = nowMs, staleAfterMs = state.staleAfterMs,
                onClick = { onCreditClick(entry.id) },
                onRetry = onRefresh,
            )
        }
        item { AddButton(stringResource(R.string.overview_add_credit), stringResource(R.string.overview_add_credit_hint), onAddCredit) }
    }
}

/** A full-width secondary button with its one-line hint beneath: the "add" row of a section. */
@Composable
fun AddButton(label: String, hint: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlineButton(label, onClick, Modifier.fillMaxWidth(),
            leading = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) })
        Text(hint, style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
    }
}

@Composable
private fun OverviewHeader(state: UsageUiState, nowMs: Long, onRefresh: () -> Unit) {
    val count = pluralStringResource(R.plurals.overview_accounts_monitored, state.accountCount, state.accountCount)
    val countdownTexts = rememberCountdownTexts()
    ScreenTitle(
        title = stringResource(R.string.app_name),
        // The stamp swaps text, not layout, while a refresh is in flight.
        stamp = when {
            state.isRefreshing -> stringResource(R.string.overview_refreshing)
            state.accounts.isEmpty() -> count
            else -> "$count · ${Countdown.freshnessLabel(state.lastUpdated, nowMs, countdownTexts)}"
        },
        action = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(enabled = !state.isRefreshing, role = Role.Button, onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isRefreshing) {
                    Spinner()
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.overview_refresh_usage),
                        tint = UsageColors.Muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    )
}

/**
 * Keep the account, remaining quota and countdown together so the next reset is unambiguous.
 */
@Composable
private fun LeadStrip(state: UsageUiState, nowMs: Long, onPercentDisplayChange: (PercentDisplay) -> Unit) {
    val lead = state.leadAt(nowMs)
    val window = lead?.window
    val mode = state.settings.percentDisplay
    val context = LocalContext.current
    val appIcon = remember(context) { AppIcon.current(context) }
    // The view model already marks a lead whose window has reset as stale, so no fetch time is needed.
    val p = if (lead == null) WindowPresentation.absent(mode)
        else lead.window.presentation(lead.window.readingValidity(stale = lead.stale, fetchedAt = null, nowMs = nowMs), mode)
    val percent = percentFigureText(p.token)
    // R2: the word beside the figure says which side it counts, and so does the spoken sentence.
    val side = stringResource(percentSideWordRes(mode))
    // R3: the figure flips the one global setting; the label names where it goes.
    val flipTo = if (mode == PercentDisplay.USED) PercentDisplay.LEFT else PercentDisplay.USED
    val flipLabel = stringResource(percentFlipLabelRes(mode))
    val motionEnabled = LocalMotionEnabled.current
    // A stale lead says how old it is instead of promising a reset the cache cannot vouch for.
    val countdownTexts = rememberCountdownTexts()
    val reset = if (lead?.stale == true) Countdown.freshnessLabel(lead.usage.snapshot?.fetchedAt, nowMs, countdownTexts)
        else Countdown.resetLabel(window?.resetAt, nowMs, countdownTexts)
    // The window row below names it in the user's language, so the lead does too; a label the
    // provider reported is shown as it came.
    val windowLabel = window?.let { w ->
        windowLabelRes(w.label, w.category, w.scope)?.let { stringResource(it) } ?: w.label
    }

    UsageCard {
        Column(
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(
                    lead?.let { "${it.usage.account.label}, $windowLabel, $percent $side" }, reset,
                ).joinToString(", ")
                if (lead != null) {
                    role = Role.Button
                    onClick(label = flipLabel) {
                        onPercentDisplayChange(flipTo)
                        true
                    }
                }
            }
                // A keyboard or D-pad reaches the same action: the figure's tap is pointer-only.
                .onKeyEvent { event ->
                    val confirm = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter
                    if (lead != null && confirm && event.type == KeyEventType.KeyUp) {
                        onPercentDisplayChange(flipTo); true
                    } else false
                }
                .focusable(enabled = lead != null),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (lead != null) {
                val worst = lead.window
                val severity = if (lead.stale) Severity.STALE else worst.severity
                Text("${lead.usage.account.label} · $windowLabel",
                    style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted, maxLines = 1)
                // The accessibility action lives on the card's merged node above, so a screen reader
                // finds one button. `clickable` would add a second focus stop of its own; this tap
                // is the finger's route to the same action and creates no semantics node.
                Row(
                    Modifier.pointerInput(flipTo, onPercentDisplayChange) { detectTapGestures { onPercentDisplayChange(flipTo) } },
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // No animation when the system has animations off: the figure just changes.
                    Crossfade(
                        targetState = percent,
                        animationSpec = if (motionEnabled) tween(200) else snap(),
                        label = "leadFigure",
                    ) { shown ->
                        Text(shown, style = MaterialTheme.typography.headlineMedium,
                            color = SeverityPalette.figureColor(severity))
                    }
                    Text(side, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted)
                }
                Spacer(Modifier.height(2.dp))
                UsageBar(p, Modifier.fillMaxWidth())
                if (reset != null) Text(reset, style = MaterialTheme.typography.bodySmall, color = UsageColors.Faint)
            } else {
                Text("—", style = MaterialTheme.typography.bodySmall, color = UsageColors.Faint)
            }
        }
        Spacer(Modifier.height(12.dp))
        RuleDivider()
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LeadStat(stringResource(R.string.overview_connected_accounts), "${state.healthyCountAt(nowMs)}/${state.accountCount}", UsageColors.Ink, Modifier.weight(1f))
            AppIconTile(appIcon, size = 40.dp)
        }
    }
}

@Composable
private fun LeadStat(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted, maxLines = 1)
        Text(value, style = UsageType.FigureLarge, color = color)
    }
}

/** The lead strip at its real size, in grey. */
@Composable
private fun LeadSkeleton() {
    val loading = stringResource(R.string.overview_loading_usage)
    UsageCard(Modifier.semantics { contentDescription = loading }) {
        SkeletonBar(12.dp, 0.3f)
        Spacer(Modifier.height(8.dp))
        SkeletonBar(30.dp, 0.2f)
        Spacer(Modifier.height(10.dp))
        UsageBar(null, Severity.UNKNOWN, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        RuleDivider()
        Spacer(Modifier.height(12.dp))
        SkeletonBar(16.dp, 0.4f)
    }
}

/** An account card at its real size, in grey. */
@Composable
private fun AccountSkeleton() {
    UsageCard {
        SkeletonBar(16.dp, 0.4f)
        Spacer(Modifier.height(6.dp))
        SkeletonBar(12.dp, 0.6f)
        repeat(3) {
            Spacer(Modifier.height(16.dp))
            SkeletonBar(14.dp, 0.3f)
            Spacer(Modifier.height(6.dp))
            UsageBar(null, Severity.UNKNOWN, Modifier.fillMaxWidth())
        }
    }
}

/**
 * When the account's LONG allowance comes back, as opposed to its next reset.
 *
 * The next reset is nearly always the short rolling window — five hours on Codex — and it is
 * already on the card. What is not on the card is the date the weekly or monthly allowance
 * starts over, which is the one people plan around.
 *
 * The provider does not state a subscription renewal date anywhere in the usage payload, so
 * this is derived: the longest-period window's own reset. That makes it exactly "when the big
 * bucket refills" and nothing more — it is not a billing date and does not claim to be.
 *
 * Null when there is only one window, since then the renewal IS the next reset and printing it
 * twice under different names is worse than leaving it out.
 */
@Composable
private fun renewalLabel(usage: AccountUsage, nowMs: Long): String? {
    val windows = usage.snapshot?.windows.orEmpty()
    if (windows.size < 2) return null
    // `periodSeconds` is nullable — a provider that does not state a window's duration
    // sorts below every window that does, rather than being treated as the longest.
    val longest = windows.maxByOrNull { it.periodSeconds ?: -1L } ?: return null
    val soonest = windows.mapNotNull { it.resetAt }.filter { it > nowMs }.minOrNull()
    val renewsAt = longest.resetAt?.takeIf { it > nowMs } ?: return null
    if (renewsAt == soonest) return null
    // The same words the row of this window shows: the app's own names are translated, a label
    // the provider reported is data.
    val label = windowLabelRes(longest.label, longest.category, longest.scope)
        ?.let { stringResource(it) } ?: longest.label
    return stringResource(R.string.overview_renews, label, Countdown.format(renewsAt - nowMs, rememberCountdownTexts()))
}

/**
 * One account: identity, status, and its quota rows.
 *
 * Windows are grouped when the provider supplies a group (Antigravity quota groups, Codex code
 * review), so a shared bucket is shown once with its members named rather than repeated per
 * model.
 */
@Composable
fun AccountCard(
    usage: AccountUsage,
    nowMs: Long,
    staleAfterMs: Long,
    // Required, never defaulted: a defaulted mode would leave a caller silently in LEFT.
    percentDisplay: PercentDisplay,
    showTier: Boolean = true,
    showRenewal: Boolean = false,
    // Before `onClick`, so the trailing-lambda call sites keep binding their lambda to the
    // click and not to this.
    modifier: Modifier = Modifier,
    dragHandle: (@Composable () -> Unit)? = null,
    /** Shown beside a failure inside the card; null hides the Retry. */
    onRetry: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val snapshot = usage.snapshot
    val countdownTexts = rememberCountdownTexts()
    val severity = snapshot?.severityAt(nowMs, staleAfterMs) ?: Severity.STALE
    // A card that is old or failed keeps its figures, in grey.
    val stale = severity == Severity.STALE || severity == Severity.ERROR

    UsageCard(modifier = modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp)) {
            com.usagelimits.ui.ProviderBadge(provider = usage.account.provider, size = 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = usage.account.title(showTier),
                    style = MaterialTheme.typography.titleMedium,
                    color = UsageColors.Ink,
                    maxLines = 1,
                )
                Text(
                    text = (usage.account.maskedEmail ?: usage.account.label) + " · " +
                        Countdown.freshnessLabel(usage.measurementAt, nowMs, countdownTexts),
                    style = MaterialTheme.typography.bodySmall,
                    color = UsageColors.Faint,
                    maxLines = 1,
                )
                if (showRenewal) {
                    renewalLabel(usage, nowMs)?.let { renewal ->
                        Text(
                            text = renewal,
                            style = MaterialTheme.typography.bodySmall,
                            color = UsageColors.Faint,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (dragHandle != null) dragHandle() else {
                StatusMark(severity, label = if (snapshot?.connectionStatus == com.usagelimits.core.model.ConnectionStatus.RECONNECT_REQUIRED) stringResource(R.string.status_reconnect) else null)
                // Its own 24dp slot, so figures do not jump when a row has no chevron.
                Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterEnd) {
                    Icon(
                        imageVector = AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = UsageColors.Faint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        if (!snapshot?.restrictedWindows.isNullOrEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(UsageColors.Warning, size = 6.dp)
                Text(
                    text = stringResource(R.string.restricted_windows, snapshot!!.restrictedWindows.joinToString { it.label }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.Muted,
                )
            }
        }

        val windows = snapshot?.windows.orEmpty()
        Spacer(Modifier.height(12.dp))
        if (windows.isEmpty()) {
            Text(
                text = snapshot?.errorMessage?.let { storedMessageText(it) } ?: stringResource(R.string.overview_no_usage_data),
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.Muted,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Ungrouped windows first — they are the account's headline limits.
                windows.filter { it.group == null }.take(4).forEach { window ->
                    UsageWindowRow(window, nowMs, percentDisplay, fetchedAt = snapshot?.fetchedAt, stale = stale)
                }

                val groups = windows.filter { it.group != null }.groupBy { it.group!! }
                groups.forEach { (group, groupWindows) ->
                    Text(
                        text = group,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = UsageColors.Muted,
                    )
                    groupWindows.forEach { window ->
                        UsageWindowRow(window, nowMs, percentDisplay, fetchedAt = snapshot?.fetchedAt, stale = stale)
                    }
                }
            }
        }

        // The summary line reports what the user holds; whether any of it can be spent right
        // now is a detail-screen concern, where the button lives.
        val creditCount = snapshot?.heldResetCredits ?: 0
        if (creditCount > 0) {
            Spacer(Modifier.height(12.dp))
            RuleDivider()
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.reset_credits_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.Muted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.reset_credits_available_count, creditCount),
                    style = UsageType.Figure,
                    color = UsageColors.Ink,
                )
            }
        }

        if (snapshot?.errorMessage != null && windows.isNotEmpty()) {
            val partial = snapshot.status == com.usagelimits.core.model.SnapshotStatus.PARTIAL
            ErrorBlock(
                message = stringResource(
                    if (partial) R.string.error_partial_update else R.string.error_showing_last_known,
                    storedMessageText(snapshot.errorMessage),
                ),
                modifier = Modifier.padding(top = 12.dp),
                dot = if (partial) UsageColors.Warning else UsageColors.Negative,
                onRetry = onRetry,
            )
        }
    }
}
