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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.usagelimits.R
import com.usagelimits.ui.components.OutlineButton
import com.usagelimits.ui.components.UsageCard
import com.usagelimits.ui.theme.UsageColors

/**
 * The notification permission as Settings sees it: whether it is allowed, whether Android still
 * offers its dialog, and the one way to ask.
 *
 * Shared by every Settings card that depends on the permission, so they cannot disagree about
 * it. [request] shows the system dialog while Android still offers it (once, before any answer,
 * or after a denial it allows a rationale for), and otherwise opens this app's notification
 * settings, because Android reports dismissal and permanent denial alike. The state is reread
 * after the dialog answers and whenever the screen resumes, which is how a return from the system
 * settings is noticed.
 */
@Stable
internal class NotificationPermissionState(
    private val context: Context,
    alreadyAsked: Boolean,
) {
    private val activity = context.activity()

    /** Set by [rememberNotificationPermission]; null only before its first composition. */
    internal var launch: (() -> Unit)? = null
    internal var onAsked: () -> Unit = {}
    internal var onAllowed: () -> Unit = {}

    /** Whether the dialog has been shown, here or by the first launch (#27). */
    internal var asked: Boolean = alreadyAsked
        set(value) {
            field = value
            requestable = canRequest()
        }

    var allowed by mutableStateOf(readAllowed())
        private set
    var requestable by mutableStateOf(canRequest())
        private set

    /** True while the dialog is open, so a second tap cannot stack a second request. */
    var requesting by mutableStateOf(false)
        private set

    fun request() {
        if (requesting) return
        if (requestable) {
            requesting = true
            asked = true
            onAsked()
            launch?.invoke()
        } else {
            openNotificationSettings(context)
        }
    }

    /** The dialog answered. */
    internal fun answered() {
        requesting = false
        reread()
    }

    /** Reads the grant again; tells [onAllowed] when it has just been given. */
    internal fun reread() {
        val now = readAllowed()
        val became = now && !allowed
        allowed = now
        requestable = canRequest()
        if (became) onAllowed()
    }

    private fun readAllowed() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    // Android reports dismissal and permanent denial alike; system settings safely handles both.
    private fun canRequest() = !asked || activity?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS)
    } == true
}

/**
 * Remembers the [NotificationPermissionState] for this screen. [alreadyAsked] is the stored
 * history of #27, recorded through [onAsked] before the dialog opens; [onAllowed] runs each time
 * the permission turns from not allowed to allowed, wherever the person granted it.
 */
@Composable
internal fun rememberNotificationPermission(
    alreadyAsked: Boolean,
    onAsked: () -> Unit,
    onAllowed: () -> Unit = {},
): NotificationPermissionState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // One state per screen, not per stored history: recording the request changes the history
    // while the dialog is open, and the in-flight guard must survive that.
    val state = remember(context) { NotificationPermissionState(context, alreadyAsked) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state.answered() }
    state.launch = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    state.onAsked = onAsked
    state.onAllowed = onAllowed
    LaunchedEffect(alreadyAsked) { if (alreadyAsked != state.asked) state.asked = alreadyAsked }
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.reread()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}

/** OS authorization is separate from the alert preferences, which remain editable when denied. */
@Composable
internal fun NotificationPermissionSettings(permission: NotificationPermissionState) {
    UsageCard {
        Text(
            stringResource(if (permission.allowed) R.string.settings_notifications_allowed
                else R.string.settings_notifications_not_allowed),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleMedium,
            color = UsageColors.Ink,
        )
        if (!permission.allowed) {
            Text(
                stringResource(R.string.settings_notifications_blocked_note),
                style = MaterialTheme.typography.bodyMedium,
                color = UsageColors.Muted,
            )
            Spacer(Modifier.height(10.dp))
            OutlineButton(
                stringResource(if (permission.requestable) R.string.settings_allow_notifications
                    else R.string.settings_open_notification_settings),
                enabled = !permission.requesting,
                onClick = permission::request,
            )
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null))
    context.startFirstAvailable(notifications, details)
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
