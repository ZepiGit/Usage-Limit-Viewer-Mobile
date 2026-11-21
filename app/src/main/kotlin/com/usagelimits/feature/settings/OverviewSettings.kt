package com.usagelimits.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usagelimits.R
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ReadingValidity
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.model.presentation
import com.usagelimits.core.model.title
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.settings.OverviewCreditLayout
import com.usagelimits.core.settings.OverviewLayout
import com.usagelimits.core.settings.OverviewShowFirst
import com.usagelimits.feature.overview.OverviewLayoutPreview
import com.usagelimits.feature.overview.windowLabel
import com.usagelimits.ui.components.FigureWithSide
import com.usagelimits.ui.components.RuleDivider
import com.usagelimits.ui.components.Segment
import com.usagelimits.ui.components.SegmentedRow
import com.usagelimits.ui.components.TextAction
import com.usagelimits.ui.components.ToggleRow
import com.usagelimits.ui.components.UsageBar
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.percentSideCaptionRes
import com.usagelimits.ui.percentSideWordRes
import com.usagelimits.ui.text.percentFigureText
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors
import com.usagelimits.ui.theme.UsageType
import java.math.BigDecimal
import java.time.ZoneId
import androidx.compose.foundation.layout.heightIn

@Composable
internal fun DisplaySettings(mode: PercentDisplay, onModeChange: (PercentDisplay) -> Unit) {
    UsageCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingTitle(R.string.settings_percent_display_title, R.string.settings_percent_display_subtitle)
            SegmentedRow {
                for (choice in PercentDisplay.entries) {
                    Segment(stringResource(percentSideCaptionRes(choice)), mode == choice, { onModeChange(choice) })
                }
            }
            SettingsPreview {
                val claude = PreviewAccounts.first()
                val window = claude.snapshot!!.windows.first()
                val reading = window.presentation(ReadingValidity.CURRENT, mode)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_display_preview_sample,
                            claude.account.title(showTier = true), windowLabel(window)),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = UsageColors.Muted,
                    )
                    FigureWithSide(percentFigureText(reading.token), stringResource(percentSideWordRes(reading.side)),
                        SeverityPalette.figureColor(window.severity), UsageType.PreviewFigure,
                        MaterialTheme.typography.bodySmall, spacing = 4.dp)
                }
                UsageBar(reading, Modifier.fillMaxWidth(), height = 6.dp)
            }
        }
    }
}

@Composable
internal fun OverviewSettings(
    settings: AppSettings,
    onLayoutChange: (OverviewLayout) -> Unit,
    onShowTier: (Boolean) -> Unit,
    onCreditLayoutChange: (OverviewCreditLayout) -> Unit,
    onShowFirstChange: (OverviewShowFirst) -> Unit,
    onShowCredits: (Boolean) -> Unit,
    onManageCredits: (() -> Unit)?,
) {
    UsageCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingTitle(R.string.settings_overview_layout_title, R.string.settings_overview_layout_subtitle)
            SegmentedRow {
                Segment(stringResource(R.string.settings_overview_list), settings.overviewLayout == OverviewLayout.LIST,
                    { onLayoutChange(OverviewLayout.LIST) })
                Segment(stringResource(R.string.settings_overview_dials), settings.overviewLayout == OverviewLayout.DIALS,
                    { onLayoutChange(OverviewLayout.DIALS) })
            }
            RuleDivider()
            ToggleRow(
                title = stringResource(R.string.settings_show_credits_title),
                subtitle = stringResource(R.string.settings_show_credits_subtitle),
                checked = settings.overviewShowCredits,
                onChange = onShowCredits,
            )
            if (settings.overviewShowCredits) {
                RuleDivider()
                SettingTitle(R.string.settings_credit_layout_title, R.string.settings_credit_layout_subtitle)
                SegmentedRow {
                    Segment(stringResource(R.string.settings_credit_normal), settings.overviewCreditLayout == OverviewCreditLayout.NORMAL,
                        { onCreditLayoutChange(OverviewCreditLayout.NORMAL) })
                    Segment(stringResource(R.string.settings_credit_compact), settings.overviewCreditLayout == OverviewCreditLayout.COMPACT,
                        { onCreditLayoutChange(OverviewCreditLayout.COMPACT) })
                }
                RuleDivider()
                SettingTitle(R.string.settings_overview_show_first_title, R.string.settings_overview_show_first_subtitle)
                val choices: @Composable () -> Unit = {
                    Segment(stringResource(R.string.settings_overview_usage_providers), settings.overviewShowFirst == OverviewShowFirst.USAGE_PROVIDERS,
                        { onShowFirstChange(OverviewShowFirst.USAGE_PROVIDERS) })
                    Segment(stringResource(R.string.settings_credit_layout_title), settings.overviewShowFirst == OverviewShowFirst.CREDIT_ACCOUNTS,
                        { onShowFirstChange(OverviewShowFirst.CREDIT_ACCOUNTS) })
                }
                if (LocalDensity.current.fontScale >= 1.4f) {
                    FlowRow(Modifier.fillMaxWidth().selectableGroup()) { choices() }
                } else {
                    SegmentedRow(content = choices)
                }
            }
            RuleDivider()
            ToggleRow(
                title = stringResource(R.string.settings_show_tier_title),
                subtitle = stringResource(R.string.settings_show_tier_subtitle),
                checked = settings.showSubscriptionTier,
                onChange = onShowTier,
            )
            if (onManageCredits != null) {
                RuleDivider()
                TextAction(stringResource(R.string.credit_manage), onManageCredits, Modifier.heightIn(min = 48.dp))
            }
            SettingsPreview { OverviewLayoutPreview(PreviewAccounts, PreviewCredits, settings, PreviewNow, PreviewZone) }
        }
    }
}

@Composable
private fun SettingTitle(title: Int, subtitle: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = UsageColors.Ink)
        Text(stringResource(subtitle), style = MaterialTheme.typography.bodyMedium, color = UsageColors.Muted)
    }
}

@Composable
private fun SettingsPreview(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(UsageColors.Ground).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.settings_preview),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, letterSpacing = 0.66.sp), color = UsageColors.Faint)
        content()
    }
}

private const val PreviewNow = 1_794_783_360_000L // 22:56 UTC, 15 November 2026
private val PreviewZone = ZoneId.of("Etc/UTC")
private val PreviewAccounts = listOf(
    previewAccount(ProviderId.CLAUDE, "Pro", 18.0, 35, 61.0, 6070),
    previewAccount(ProviderId.CODEX, "Plus", 72.0, 128, 44.0, 4553),
)

private val PreviewCredits = listOf(
    previewCredit(CreditProviderId.DEEPSEEK, "DeepSeek", "3.18"),
    previewCredit(CreditProviderId.OPENROUTER, "OpenRouter", "12.40"),
    previewCredit(CreditProviderId.MOONSHOT, "Moonshot", "25.00"),
)

private fun previewCredit(provider: CreditProviderId, label: String, amount: String): CreditEntry {
    val id = provider.id
    return CreditEntry(CreditAccount(id, provider, label, ""),
        CreditSnapshot(id, listOf(CreditBalance("USD", BigDecimal(amount))), PreviewNow))
}

private fun previewAccount(provider: ProviderId, plan: String, short: Double, shortReset: Int, weekly: Double, weeklyReset: Int): AccountUsage {
    val id = provider.id
    val account = ProviderAccount(id, provider, id, null, null, plan, "", PreviewNow, PreviewNow)
    val windows = listOf(
        UsageWindow("5h", "5h limit", WindowCategory.FIVE_HOUR, 100.0 - short, 18_000,
            PreviewNow + shortReset * 60_000L, false),
        UsageWindow("weekly", "Weekly", WindowCategory.WEEKLY, 100.0 - weekly, 604_800,
            PreviewNow + weeklyReset * 60_000L, false),
    )
    return AccountUsage(account, UsageSnapshot(id, PreviewNow, SnapshotStatus.OK, windows))
}
