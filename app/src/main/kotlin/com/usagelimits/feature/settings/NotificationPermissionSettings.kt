package com.usagelimits.feature.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.usagelimits.R
import com.usagelimits.core.settings.SettingsStore
import com.usagelimits.ui.components.OutlineButton
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors
import kotlinx.coroutines.launch

/** OS authorization is separate from the alert preferences, which remain editable when denied. */
@Composable
internal fun NotificationPermissionSettings(alreadyAsked: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val store = remember(context) { SettingsStore(context) }
    fun allowed() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    var asked by remember(alreadyAsked) { mutableStateOf(alreadyAsked) }
    fun canRequest() = !asked || ActivityCompat.shouldShowRequestPermissionRationale(
        activity, Manifest.permission.POST_NOTIFICATIONS,
    )
    var notificationsAllowed by remember { mutableStateOf(allowed()) }
    var requestable by remember(alreadyAsked) { mutableStateOf(canRequest()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = allowed()
        requestable = canRequest()
    }
    DisposableEffect(lifecycle, alreadyAsked) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = allowed()
                requestable = canRequest()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    UsageCard {
        Text(
            stringResource(if (notificationsAllowed) R.string.settings_notifications_allowed
                else R.string.settings_notifications_not_allowed),
            style = MaterialTheme.typography.titleMedium,
            color = UsageColors.Ink,
        )
        if (!notificationsAllowed) {
            Text(
                stringResource(R.string.settings_notifications_blocked_note),
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.Muted,
            )
            Spacer(Modifier.height(10.dp))
            OutlineButton(
                stringResource(if (requestable) R.string.settings_allow_notifications
                    else R.string.settings_open_notification_settings),
                onClick = {
                    if (requestable) {
                        scope.launch {
                            store.recordNotificationPermissionAsked()
                            asked = true
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                },
            )
        }
    }
}

private tailrec fun Context.activity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> error("Notification settings need an Activity")
}
