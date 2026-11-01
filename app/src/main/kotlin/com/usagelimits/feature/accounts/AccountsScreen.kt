package com.usagelimits.feature.accounts

import com.usagelimits.ui.theme.LocalMotionEnabled
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.usagelimits.R
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.ProviderId
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.overview.AccountCard
import com.usagelimits.feature.overview.AddButton
import com.usagelimits.ui.components.EmptyBox
import com.usagelimits.ui.components.ScreenTitle
import com.usagelimits.ui.components.Segment
import com.usagelimits.ui.components.SegmentedRow
import com.usagelimits.ui.theme.UsageColors

/** Filters over the account list. Kept local to the screen — it is view state, not app state. */
internal data class AccountFilter(
    val provider: ProviderId? = null,
    val onlyProblems: Boolean = false,
) {
    fun toggleProblems() = AccountFilter(onlyProblems = !onlyProblems)

    fun toggleProvider(p: ProviderId) = AccountFilter(provider = if (provider == p) null else p)

    fun matches(usage: AccountUsage): Boolean {
        if (provider != null && usage.account.provider != provider) return false
        if (onlyProblems) {
            return usage.snapshot?.connectionStatus == com.usagelimits.core.model.ConnectionStatus.RECONNECT_REQUIRED
        }
        return true
    }
}

/**
 * The full account list with provider and status filters.
 *
 * Reuses the Overview card so an account looks identical wherever it appears — two divergent
 * renderings of the same thing is how a list stops feeling like one app.
 */
@Composable
fun AccountsScreen(
    state: UsageUiState,
    nowMs: Long,
    onAccountClick: (String) -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier,
    /** Retry beside a failed account; null hides it. */
    onRefresh: (() -> Unit)? = null,
) {
    var filter by remember { mutableStateOf(AccountFilter()) }
    val visible = state.accounts.filter(filter::matches)
    val presentProviders = state.accounts.map { it.account.provider }.distinct()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Ground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenTitle(
                stringResource(R.string.accounts_title),
                stamp = stringResource(R.string.accounts_shown, visible.size, state.accountCount),
            )
        }

        item {
            SegmentedRow {
                Segment(stringResource(R.string.accounts_filter_all), filter.provider == null && !filter.onlyProblems) {
                    filter = AccountFilter()
                }
                Segment(stringResource(R.string.accounts_filter_attention), filter.onlyProblems) {
                    filter = filter.toggleProblems()
                }
                presentProviders.forEach { provider ->
                    Segment(provider.displayName, filter.provider == provider) {
                        filter = filter.toggleProvider(provider)
                    }
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                EmptyBox(
                    stringResource(
                        if (state.accounts.isEmpty()) R.string.overview_no_accounts else R.string.accounts_no_match,
                    ),
                )
            }
        }

        items(visible, key = { it.account.localId }) { usage ->
            AccountCard(usage, nowMs, state.staleAfterMs, state.settings.percentDisplay,
                modifier = if (LocalMotionEnabled.current) Modifier.animateItem() else Modifier,
                onRetry = onRefresh,
            ) {
                onAccountClick(usage.account.localId)
            }
        }

        item { AddButton(stringResource(R.string.overview_add_account), stringResource(R.string.overview_add_account_hint), onAddAccount) }
    }
}
