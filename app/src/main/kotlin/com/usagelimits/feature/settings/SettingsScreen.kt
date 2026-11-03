package com.usagelimits.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.usagelimits.R
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.Severity
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.core.sync.BackgroundRestrictions
import com.usagelimits.core.time.Countdown
import com.usagelimits.feature.UsageUiState
import com.usagelimits.ui.components.Dot
import com.usagelimits.ui.components.OutlineButton
import com.usagelimits.ui.components.RuleDivider
import com.usagelimits.ui.components.ScreenTitle
import com.usagelimits.ui.components.Segment
import com.usagelimits.ui.components.SegmentedRow
import com.usagelimits.ui.components.SectionHeader
import com.usagelimits.ui.components.TextAction
import com.usagelimits.ui.components.ToggleRow
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.percentSideCaptionRes
import com.usagelimits.ui.text.percentFigureText
import com.usagelimits.ui.text.rememberCountdownTexts
import com.usagelimits.ui.theme.SeverityPalette
import com.usagelimits.ui.theme.UsageColors

/** Sync cadence choices. 15 minutes is WorkManager's floor for periodic work. */
private val INTERVAL_CHOICES = listOf(15, 30, 60, 180)

@Composable
fun SettingsScreen(
    state: UsageUiState,
    onSyncIntervalChange: (Int) -> Unit,
    onNotifyBelow20: (Boolean) -> Unit,
    onNotifyBelow10: (Boolean) -> Unit,
    onNotifyExhausted: (Boolean) -> Unit,
    onNotifyResetCredit: (Boolean) -> Unit,
    onNotifyAuthExpired: (Boolean) -> Unit,
    onNotifyResetApproaching: (Boolean) -> Unit,
    onNotifyCreditExpiring: (Boolean) -> Unit,
    onShowTier: (Boolean) -> Unit,
    onShowRenewal: (Boolean) -> Unit,
    onPercentDisplayChange: (PercentDisplay) -> Unit,
    modifier: Modifier = Modifier,
    onProviderIconChange: (com.usagelimits.core.model.ProviderId, String) -> Unit = { _, _ -> },
) {
    val settings = state.settings
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(UsageColors.Ground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenTitle(stringResource(R.string.settings_title)) }

        item { ProviderIconSettings(settings.providerIcons, onProviderIconChange) }
        item { AppIconSettings() }
        item { SectionHeader(stringResource(R.string.settings_section_sync)) }
        item {
            UsageCard {
                Text(
                    text = stringResource(R.string.settings_interval_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = UsageColors.Ink,
                )
                Text(
                    text = stringResource(R.string.settings_interval_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.Muted,
                )
                Spacer(Modifier.height(10.dp))
                SegmentedRow {
                    INTERVAL_CHOICES.forEach { minutes ->
                        Segment(
                            label = if (minutes >= 60) stringResource(R.string.countdown_hours, minutes / 60)
                                else stringResource(R.string.countdown_minutes, minutes),
                            selected = settings.syncIntervalMinutes == minutes,
                            onClick = { onSyncIntervalChange(minutes) },
                        )
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_overview)) }
        item {
            UsageCard {
                ToggleRow(
                    title = stringResource(R.string.settings_show_tier_title),
                    subtitle = stringResource(R.string.settings_show_tier_subtitle),
                    checked = settings.showSubscriptionTier,
                    onChange = onShowTier,
                )
                RuleDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_show_renewal_title),
                    subtitle = stringResource(R.string.settings_show_renewal_subtitle),
                    checked = settings.showRenewalTime,
                    onChange = onShowRenewal,
                )
                RuleDivider()
                Text(
                    text = stringResource(R.string.settings_percent_display_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = UsageColors.Ink,
                )
                Text(
                    text = stringResource(R.string.settings_percent_display_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.Muted,
                )
                Spacer(Modifier.height(10.dp))
                SegmentedRow {
                    PercentDisplay.entries.forEach { mode ->
                        Segment(
                            label = stringResource(percentSideCaptionRes(mode)),
                            selected = settings.percentDisplay == mode,
                            onClick = { onPercentDisplayChange(mode) },
                        )
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_notifications)) }
        item {
            // The thresholds stay on remaining (the same two crossings); only their wording
            // follows the display mode, so the toggles read in the numbers the user is watching.
            val used = settings.percentDisplay == PercentDisplay.USED
            val first = percentFigureText(if (used) "80" else "20")
            val second = percentFigureText(if (used) "90" else "10")
            UsageCard {
                ToggleRow(
                    title = stringResource(
                        if (used) R.string.settings_notify_above_used_title else R.string.settings_notify_below_left_title,
                        first,
                    ),
                    subtitle = stringResource(
                        if (used) R.string.settings_notify_first_used_subtitle else R.string.settings_notify_first_left_subtitle,
                    ),
                    checked = settings.notifyBelow20Percent,
                    onChange = onNotifyBelow20,
                )
                RuleDivider()
                ToggleRow(
                    title = stringResource(
                        if (used) R.string.settings_notify_above_used_title else R.string.settings_notify_below_left_title,
                        second,
                    ),
                    subtitle = stringResource(R.string.settings_notify_second_subtitle, first),
                    checked = settings.notifyBelow10Percent,
                    onChange = onNotifyBelow10,
                )
                RuleDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_notify_exhausted_title),
                    subtitle = stringResource(R.string.settings_notify_exhausted_subtitle),
                    checked = settings.notifyOnExhausted,
                    onChange = onNotifyExhausted,
                )
                RuleDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_notify_reset_credit_title),
                    subtitle = stringResource(R.string.settings_notify_reset_credit_subtitle),
                    checked = settings.notifyOnResetCreditAvailable,
                    onChange = onNotifyResetCredit,
                )
                RuleDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_notify_reset_approaching_title),
                    subtitle = pluralStringResource(
                        R.plurals.settings_notify_reset_approaching_subtitle,
                        settings.resetApproachingMinutes,
                        settings.resetApproachingMinutes,
                    ),
                    checked = settings.notifyOnResetApproaching,
                    onChange = onNotifyResetApproaching,
                )
                RuleDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_notify_credit_expiring_title),
                    subtitle = stringResource(R.string.settings_notify_credit_expiring_subtitle),
                    checked = settings.notifyOnResetCreditExpiring,
                    onChange = onNotifyCreditExpiring,
                )
                RuleDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_notify_auth_expired_title),
                    subtitle = stringResource(R.string.settings_notify_auth_expired_subtitle),
                    checked = settings.notifyOnAuthExpired,
                    onChange = onNotifyAuthExpired,
                )
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_thresholds)) }
        item {
            UsageCard {
                Text(
                    text = stringResource(R.string.settings_thresholds_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.Muted,
                )
                Spacer(Modifier.height(10.dp))
                val healthyAbove = Severity.HEALTHY_ABOVE.toInt().toString()
                val mediumAbove = Severity.MEDIUM_ABOVE.toInt().toString()
                ThresholdRow(
                    stringResource(SeverityPalette.labelRes(Severity.HEALTHY)),
                    stringResource(R.string.settings_threshold_above, percentFigureText(healthyAbove)),
                    UsageColors.Positive,
                )
                ThresholdRow(
                    stringResource(SeverityPalette.labelRes(Severity.MEDIUM)),
                    stringResource(R.string.settings_threshold_range, mediumAbove, percentFigureText(healthyAbove)),
                    UsageColors.Warning,
                )
                ThresholdRow(
                    stringResource(SeverityPalette.labelRes(Severity.LOW)),
                    stringResource(R.string.settings_threshold_range, "1", percentFigureText(mediumAbove)),
                    UsageColors.Warning,
                )
                ThresholdRow(
                    stringResource(SeverityPalette.labelRes(Severity.EXHAUSTED)),
                    percentFigureText("0"),
                    UsageColors.Negative,
                )
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_diagnostics)) }
        item {
            val now = System.currentTimeMillis()
            UsageCard {
                InfoRow(stringResource(R.string.settings_diag_accounts), state.accountCount.toString())
                InfoRow(stringResource(R.string.settings_diag_last_sync), diagnosticAge(state.lastUpdated, now))
                InfoRow(stringResource(R.string.settings_diag_last_attempt), diagnosticAge(state.diagnostics.lastSyncAttemptAt, now))
                InfoRow(stringResource(R.string.settings_diag_widgets_repainted), diagnosticAge(state.diagnostics.lastWidgetRefreshAt, now))
                InfoRow(
                    stringResource(R.string.settings_diag_sync_interval),
                    stringResource(R.string.settings_diag_interval_value, settings.syncIntervalMinutes),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_diag_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = UsageColors.Faint,
                )
            }
        }
        item { BackgroundRefreshCard() }
        item { TextAction("GitHub · ZepiGit", { uriHandler.openUri("https://github.com/ZepiGit") }, color = UsageColors.Muted) }
    }
}

/**
 * "37m ago" for a timestamp the diagnostics show, "Never" for none.
 *
 * Its own strings rather than [Countdown.freshnessLabel] with the "Updated" stripped off: cutting a
 * prefix only works in the one language it was written for.
 */
@Composable
private fun diagnosticAge(at: Long?, nowMs: Long): String {
    if (at == null) return stringResource(R.string.settings_diag_never)
    val age = nowMs - at
    if (age < 60_000) return stringResource(R.string.settings_diag_just_now)
    return stringResource(R.string.settings_diag_ago, Countdown.format(age, rememberCountdownTexts()))
}

/**
 * What the device lets this app do in the background, and where to change it.
 *
 * The widgets can only be as current as the background refresh the device allows. A phone
 * that has put the app in a rare standby bucket or restricted its background use defers the
 * refresh by hours, and the one place that could say so was silent: the app showed fresh
 * numbers whenever it was opened, the home screen showed the day the widget was placed, and
 * nothing named the cause. This card does.
 */
@Composable
private fun BackgroundRefreshCard() {
    val context = LocalContext.current
    val restrictions = remember { BackgroundRestrictions.read(context) }
    UsageCard {
        Text(
            text = stringResource(R.string.settings_background_title),
            style = MaterialTheme.typography.titleMedium,
            color = UsageColors.Ink,
        )
        Text(
            text = stringResource(R.string.settings_background_body),
            style = MaterialTheme.typography.bodyMedium,
            color = UsageColors.Muted,
        )
        Spacer(Modifier.height(6.dp))
        InfoRow(
            stringResource(R.string.settings_background_use),
            stringResource(
                if (restrictions.backgroundRestricted) R.string.settings_background_restricted
                else R.string.settings_background_allowed,
            ),
        )
        InfoRow(
            stringResource(R.string.settings_battery_optimisation),
            stringResource(
                if (restrictions.batteryOptimised) R.string.settings_battery_on else R.string.settings_battery_off,
            ),
        )
        restrictions.standbyBucket?.let { bucket ->
            InfoRow(stringResource(R.string.settings_standby_bucket), stringResource(standbyBucketRes(bucket)))
        }
        if (restrictions.severe) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(UsageColors.Warning, Modifier.padding(top = 7.dp), size = 6.dp)
                Text(
                    text = stringResource(R.string.settings_background_held_back),
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.Muted,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlineButton(stringResource(R.string.settings_open_battery_settings), { openBatterySettings(context) })
    }
}

@StringRes
private fun standbyBucketRes(bucket: BackgroundRestrictions.StandbyBucket): Int = when (bucket) {
    BackgroundRestrictions.StandbyBucket.ACTIVE -> R.string.settings_standby_active
    BackgroundRestrictions.StandbyBucket.WORKING_SET -> R.string.settings_standby_working_set
    BackgroundRestrictions.StandbyBucket.FREQUENT -> R.string.settings_standby_frequent
    BackgroundRestrictions.StandbyBucket.RARE -> R.string.settings_standby_rare
    BackgroundRestrictions.StandbyBucket.RESTRICTED -> R.string.settings_standby_restricted
    BackgroundRestrictions.StandbyBucket.UNKNOWN -> R.string.settings_standby_unknown
}

/**
 * The system's battery-optimisation list, falling back to this app's details page.
 *
 * The list screen needs no permission. The per-app exemption dialog does, and asking for it
 * is a Play policy matter for an app whose background work is a quota check.
 */
private fun openBatterySettings(context: android.content.Context) {
    val list = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val details = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null),
    )
    for (intent in listOf(list, details)) {
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (opened) return
    }
}

@Composable
private fun ThresholdRow(label: String, range: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = UsageColors.Ink)
        Spacer(Modifier.weight(1f))
        Text(range, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = UsageColors.Muted)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = UsageColors.Ink)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = UsageColors.Muted,
        )
    }
}
