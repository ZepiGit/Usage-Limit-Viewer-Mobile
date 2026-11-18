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
import com.usagelimits.navigation.UsageLimitsNavigation
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UsageLimitsActivity : ComponentActivity() {
    private val requestedAccount = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** Lets activity-level tests await the decision, including the path that does not ask. */
    @androidx.annotation.VisibleForTesting
    internal var notificationPermissionCheck: kotlinx.coroutines.Job? = null
        private set

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedAccount.value = intent?.getStringExtra("accountId")
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
            UsageLimitsTheme(providerIcons = container.settingsStore.settings.collectAsState(initial = com.usagelimits.core.settings.AppSettings()).value.providerIcons) {
                UsageLimitsNavigation(container, windowSizeClass, requestedAccount.value) { requestedAccount.value = null }
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
        requestedAccount.value = intent.getStringExtra("accountId")
    }
}
