package com.usagelimits.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.usagelimits.R
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.MAX_PINNED_ACCOUNTS
import com.usagelimits.core.model.title
import com.usagelimits.ui.components.RuleDivider
import com.usagelimits.ui.components.ToggleRow
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors

/**
 * The "Status bar icons" section: the switch, then a pin toggle for each usage account.
 *
 * State in, events out: [onEnabledChange] reports the switch, [onPinnedChange] the whole new pin
 * list. A newly pinned account goes after the ones already pinned; the order the icons show in
 * is the app's account order, decided where they are published. Only usage accounts are offered;
 * credit balances have no percentage and live in their own list, so they never reach [accounts].
 *
 * At most [MAX_PINNED_ACCOUNTS] can be pinned. With that many pinned, the section says so and the
 * toggles of the other accounts are disabled, so a fourth pin is refused here, before it is
 * stored. The note under the list states what Android decides on its own.
 *
 * Turning the switch on while notifications are not allowed asks through [permission], the
 * same flow the notification card uses. The switch itself is stored either way; once the
 * permission is given, the screen publishes the cached state and the icons appear. The section
 * says, politely announced, whether icons can be shown.
 */
@Composable
internal fun StatusIconSettings(
    enabled: Boolean,
    pinnedAccountIds: List<String>,
    accounts: List<AccountUsage>,
    permission: NotificationPermissionState,
    onEnabledChange: (Boolean) -> Unit,
    onPinnedChange: (List<String>) -> Unit,
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
            // Pins of accounts that are gone do not count against the limit or show here.
            val pinned = pinnedAccountIds.filter { id -> accounts.any { it.account.localId == id } }
            val full = pinned.size >= MAX_PINNED_ACCOUNTS
            RuleDivider()
            Text(
                stringResource(R.string.settings_status_icons_choose),
                style = MaterialTheme.typography.titleMedium,
                color = UsageColors.Ink,
            )
            Text(
                stringResource(R.string.settings_status_icons_pinned_count, pinned.size, MAX_PINNED_ACCOUNTS),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.Muted,
            )
            if (accounts.isEmpty()) {
                Text(
                    stringResource(R.string.settings_status_icons_no_accounts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = UsageColors.Muted,
                )
            }
            accounts.forEach { usage ->
                val id = usage.account.localId
                val isPinned = id in pinned
                ToggleRow(
                    title = usage.account.label,
                    subtitle = usage.account.title(),
                    checked = isPinned,
                    onChange = { pin -> onPinnedChange(if (pin) pinned + id else pinned - id) },
                    // A disabled fourth switch says why, so a screen reader does not just hear "dimmed".
                    description = if (full && !isPinned) {
                        stringResource(R.string.settings_status_icons_pin_full, usage.account.label, MAX_PINNED_ACCOUNTS)
                    } else {
                        stringResource(R.string.settings_status_icons_pin, usage.account.label)
                    },
                    enabled = isPinned || !full,
                )
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
