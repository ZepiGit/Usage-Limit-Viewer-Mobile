package com.usagelimits.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
 * Turning the switch on while notifications are not allowed starts the same permission flow as
 * the notification card: the system dialog while Android still offers it, otherwise this app's
 * notification settings. The switch itself is stored either way, so the icons appear as soon as
 * the permission is given.
 */
@Composable
internal fun StatusIconSettings(
    enabled: Boolean,
    pinnedAccountIds: List<String>,
    accounts: List<AccountUsage>,
    permissionAlreadyAsked: Boolean,
    onPermissionAsked: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPinnedChange: (String?) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun allowed() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    var notificationsAllowed by remember { mutableStateOf(allowed()) }
    var asked by remember(permissionAlreadyAsked) { mutableStateOf(permissionAlreadyAsked) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = allowed()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsAllowed = allowed()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun requestPermission() {
        val requestable = !asked || activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS)
        } == true
        if (requestable) {
            asked = true
            onPermissionAsked()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings(context)
        }
    }

    UsageCard {
        ToggleRow(
            title = stringResource(R.string.settings_status_icons_switch),
            subtitle = stringResource(R.string.settings_status_icons_subtitle),
            checked = enabled,
            onChange = { on ->
                onEnabledChange(on)
                if (on && !allowed()) requestPermission()
            },
        )
        if (!notificationsAllowed) {
            Text(
                stringResource(R.string.settings_status_icons_blocked),
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.Warning,
            )
        }
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
