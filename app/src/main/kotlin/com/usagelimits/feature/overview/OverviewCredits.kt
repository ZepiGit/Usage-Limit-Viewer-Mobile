package com.usagelimits.feature.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usagelimits.R
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.CreditFormat
import com.usagelimits.core.model.overviewCreditSummary
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.OverviewCreditLayout
import com.usagelimits.core.settings.OverviewShowFirst
import com.usagelimits.feature.credits.CreditCard
import com.usagelimits.ui.AppIcons
import com.usagelimits.ui.components.Dot
import com.usagelimits.ui.components.EmptyBox
import com.usagelimits.ui.components.TextAction
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageMotion
import com.usagelimits.ui.theme.itemMotion

internal val AppSettings.creditsFirst: Boolean
    get() = overviewShowCredits && overviewShowFirst == OverviewShowFirst.CREDIT_ACCOUNTS

internal fun AppSettings.showCompactCredits(credits: List<CreditEntry>): Boolean =
    overviewCreditLayout == OverviewCreditLayout.COMPACT && credits.isNotEmpty()

internal fun LazyListScope.overviewCredits(
    credits: List<CreditEntry>,
    nowMs: Long,
    staleAfterMs: Long,
    compact: Boolean,
    expanded: Boolean,
    motion: UsageMotion,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAdd: () -> Unit,
    onManage: (() -> Unit)?,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
) {
    if (compact && !expanded) {
        item(key = "credits-compact") {
            CompactCreditsRow(credits, nowMs, staleAfterMs, itemMotion(motion).padding(top = 12.dp), onExpand)
        }
        return
    }
    item(key = "credits-header") {
        CreditsHeading(itemMotion(motion).padding(top = 12.dp), onAdd, onCollapse.takeIf { compact && expanded })
    }
    creditCards(credits, nowMs, staleAfterMs, motion, onSelect, onRetry)
    if (credits.isNotEmpty() && onManage != null) {
        item(key = "credits-manage") {
            TextAction(stringResource(R.string.credit_manage), onManage, itemMotion(motion).heightIn(min = 48.dp))
        }
    }
}

internal fun LazyListScope.creditCards(
    credits: List<CreditEntry>,
    nowMs: Long,
    staleAfterMs: Long,
    motion: UsageMotion,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
) {
    if (credits.isEmpty()) {
        item(key = "credits-empty") {
            EmptyBox(stringResource(R.string.overview_no_credits), itemMotion(motion).padding(top = 8.dp))
        }
    }
    items(credits, key = { "credit-${it.id}" }) { entry ->
        CreditCard(entry, nowMs, staleAfterMs, itemMotion(motion).padding(top = 8.dp),
            onClick = { onSelect(entry.id) }, onRetry = onRetry)
    }
}

@Composable
internal fun OverviewCreditsPreview(credits: List<CreditEntry>, settings: AppSettings, nowMs: Long, staleAfterMs: Long) {
    if (settings.showCompactCredits(credits)) {
        CompactCreditsRow(credits, nowMs, staleAfterMs)
    } else {
        CreditsHeading()
        for (entry in credits) {
            CreditCard(entry, nowMs, staleAfterMs, compact = true)
        }
    }
}

@Composable
internal fun CreditsHeading(modifier: Modifier = Modifier, onAdd: (() -> Unit)? = null, onCollapse: (() -> Unit)? = null) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.overview_section_credits), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink)
        if (onCollapse != null) {
            TextAction(stringResource(R.string.overview_credit_collapse), onCollapse, Modifier.heightIn(min = 48.dp))
        }
        if (onAdd != null) AddCreditAction(onAdd)
    }
}

@Composable
internal fun AddCreditAction(onAdd: () -> Unit) {
    TextAction(stringResource(R.string.overview_add_credit), onAdd, Modifier.heightIn(min = 48.dp))
}

@Composable
internal fun CompactCreditsRow(
    credits: List<CreditEntry>,
    nowMs: Long,
    staleAfterMs: Long,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
) {
    val summary = credits.overviewCreditSummary(nowMs, staleAfterMs)
    val count = pluralStringResource(R.plurals.overview_credit_count, summary.count, summary.count)
    val locale = LocalConfiguration.current.locales[0]
    val hint = summary.winner?.let { winner ->
        stringResource(R.string.overview_credit_hint, winner.account.label,
            CreditFormat.string(winner.balance.amount, winner.balance.currency, locale))
    }
    val spokenHint = hint?.let { stringResource(R.string.overview_credit_low_balance, it) }
    val expandLabel = stringResource(R.string.overview_credit_expand)
    val disclosure = if (onExpand == null) Modifier else Modifier.clickable(
        onClickLabel = expandLabel, role = Role.Button, onClick = onExpand,
    )
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(UsageColors.Surface)
            .then(disclosure)
            .clearAndSetSemantics {
                contentDescription = if (spokenHint == null) count else "$count, $spokenHint"
                if (onExpand != null) {
                    role = Role.Button
                    semanticsOnClick(label = expandLabel) { onExpand(); true }
                }
            }
            .heightIn(min = if (onExpand == null) 0.dp else 48.dp)
            .padding(horizontal = if (onExpand == null) 8.dp else 16.dp, vertical = if (onExpand == null) 6.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Wallet, null, Modifier.size(if (onExpand == null) 16.dp else 22.dp), tint = UsageColors.Muted)
        FlowRow(
            Modifier.weight(1f), horizontalArrangement = if (hint == null) Arrangement.Start else Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(count, style = MaterialTheme.typography.bodyMedium.copy(fontSize = if (onExpand == null) 12.sp else 14.sp),
                color = UsageColors.Ink)
            if (hint != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(UsageColors.Warning)
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
                }
            }
        }
        if (onExpand != null) Icon(AppIcons.ChevronRight, null, Modifier.size(20.dp), tint = UsageColors.Faint)
    }
}
