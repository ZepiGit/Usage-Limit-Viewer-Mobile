package com.usagelimits.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.usagelimits.R
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.title
import com.usagelimits.ui.components.RuleDivider
import com.usagelimits.ui.components.ToggleRow
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors

/**
 * The "Status bar icons" section: the switch, then a single choice among the usage accounts.
 *
 * State in, events out: [onEnabledChange] reports the switch, [onPinnedChange] the chosen account
 * id, or null when the chosen one is tapped again. Only usage accounts are offered; credit balances
 * have no percentage and live in their own list, so they never reach [accounts].
 *
 * The stored form is already an ordered list of up to three ids; this section chooses one at a
 * time, and choosing another replaces it.
 *
 * Turning the switch on while notifications are not allowed asks through [permission], the
 * same flow the notification card uses. The switch itself is stored either way; once the
 * permission is given, the screen publishes the cached state and the icon appears. The section
 * says, politely announced, whether icons can be shown.
 */
@Composable
internal fun StatusIconSettings(
    enabled: Boolean,
    pinnedAccountIds: List<String>,
    accounts: List<AccountUsage>,
    permission: NotificationPermissionState,
    onEnabledChange: (Boolean) -> Unit,
    onPinnedChange: (String?) -> Unit,
) {
    UsageCard {
        ToggleRow(
            title = stringResource(R.string.settings_status_icons_switch),
            subtitle = stringResource(R.string.settings_status_icons_subtitle),
            checked = enabled,
            onChange = { on ->
                onEnabledChange(on)
                if (on && !permission.allowed) permission.request()
            },
        )
        Text(
            stringResource(if (permission.allowed) R.string.settings_status_icons_allowed
                else R.string.settings_status_icons_blocked),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = if (permission.allowed) UsageColors.Muted else UsageColors.Warning,
        )
        if (enabled) {
            RuleDivider()
            Text(
                stringResource(R.string.settings_status_icons_choose),
                style = MaterialTheme.typography.titleMedium,
                color = UsageColors.Ink,
            )
            if (accounts.isEmpty()) {
                Text(
                    stringResource(R.string.settings_status_icons_no_accounts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.Muted,
                )
            }
            androidx.compose.foundation.layout.Column(Modifier.selectableGroup()) {
                accounts.forEach { usage ->
                    val id = usage.account.localId
                    val selected = id in pinnedAccountIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onPinnedChange(if (selected) null else id) },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = UsageColors.Ink, unselectedColor = UsageColors.Muted),
                        )
                        Spacer(Modifier.width(10.dp))
                        androidx.compose.foundation.layout.Column {
                            Text(usage.account.label, style = MaterialTheme.typography.bodyMedium, color = UsageColors.Ink)
                            Text(usage.account.title(), style = MaterialTheme.typography.bodySmall, color = UsageColors.Muted)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.settings_status_icons_note),
            style = MaterialTheme.typography.bodySmall,
            color = UsageColors.Faint,
        )
    }
}
