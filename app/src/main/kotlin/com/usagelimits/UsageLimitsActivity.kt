package com.usagelimits

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.usagelimits.core.notifications.shouldAskNotificationPermission
import com.usagelimits.feature.settings.AppIcon
import com.usagelimits.core.oauth.SignInKeepAliveService
import com.usagelimits.core.sync.SyncWorker
import com.usagelimits.navigation.ExternalRequest
import com.usagelimits.navigation.UsageLimitsNavigation
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UsageLimitsActivity : ComponentActivity() {
    /**
     * What the latest entry from outside asked for, until the shell has handled it. A new
     * [ExternalRequest] instance per entry: the shell keys its handling by identity, so the same
     * account asked for twice is handled twice.
     */
    private val pending = androidx.compose.runtime.mutableStateOf<ExternalRequest?>(null)

    /** Lets activity-level tests await the decision, including the path that does not ask. */
    @androidx.annotation.VisibleForTesting
    internal var notificationPermissionCheck: kotlinx.coroutines.Job? = null
        private set

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity continues the request it had not handled yet, and only that: the
        // intent it was started with was handled before, and is not read again. Its first start
        // reads the intent.
        pending.value = if (savedInstanceState != null) ExternalRequest.readFrom(savedInstanceState) else ExternalRequest.from(intent)
        // The app keeps its dark palette even when Android uses a light theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val container = (application as UsageLimitsApp).container

        // Persist before opening the optional dialog: recreation must not ask again, even
        // while the first request is still awaiting a response. Settings offers the next try.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionCheck = lifecycleScope.launch {
                val settings = container.settingsStore.settings.first()
                val granted = ContextCompat.checkSelfPermission(
                    this@UsageLimitsActivity, Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                if (!settings.notificationPermissionHandled) container.settingsStore.recordNotificationPermissionHandled()
                if (shouldAskNotificationPermission(Build.VERSION.SDK_INT, settings.notificationPermissionHandled, granted)) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        setContent {
            // Recomputed on every configuration change, so unfolding a foldable, entering
            // split-screen or resizing a freeform window re-lays-out the shell immediately.
            val windowSizeClass = calculateWindowSizeClass(this)
            val settings = container.settingsStore.settings.collectAsState(initial = com.usagelimits.core.settings.AppSettings()).value
            UsageLimitsTheme(providerIcons = settings.providerIcons) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.usagelimits.ui.LocalAnimateClaudeCodeIcon provides settings.animateClaudeCodeIcon,
                ) {
                    UsageLimitsNavigation(container, windowSizeClass, pending.value, ::handled)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SyncWorker.syncOnResume(this)
    }

    override fun onStop() {
        super.onStop()
        // Disabling the launcher alias mid sign-in can drop the task under the browser tab.
        if (!isChangingConfigurations && !SignInKeepAliveService.signingIn) AppIcon.applyPending(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A new tap is a new request, also for the account asked for last time.
        ExternalRequest.from(intent)?.let { pending.value = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pending.value?.writeTo(outState)
    }

    /**
     * The shell handled [request]. A newer one that arrived meanwhile stays pending. The activity's
     * own intent loses the request too, so anything that later reads getIntent() does not see it
     * again. The intent the system recorded for the task is not changed by this; a launch from
     * Recents carries the history flag and is ignored by [ExternalRequest.from].
     */
    private fun handled(request: ExternalRequest) {
        if (pending.value !== request) return
        pending.value = null
        intent = android.content.Intent(intent).apply {
            removeExtra(ExternalRequest.EXTRA_ACCOUNT_ID)
            if (action == ExternalRequest.ACTION_OPEN_OVERVIEW) action = null
        }
    }
}
