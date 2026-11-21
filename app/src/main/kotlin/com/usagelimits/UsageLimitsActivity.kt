package com.usagelimits

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.annotation.StyleRes
import androidx.compose.runtime.SideEffect
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
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.ui.theme.colors
import com.usagelimits.feature.settings.AppIcon
import com.usagelimits.core.oauth.SignInKeepAliveService
import com.usagelimits.navigation.ExternalRequest
import com.usagelimits.navigation.UsageLimitsNavigation
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@get:StyleRes
internal val AppTheme.splashStyle: Int get() = when (this) {
    AppTheme.WARM_DARK -> R.style.Theme_UsageLimits_App_WarmDark
    AppTheme.PURE_BLACK -> R.style.Theme_UsageLimits_App
    AppTheme.COOL_GRAPHITE -> R.style.Theme_UsageLimits_App_CoolGraphite
    AppTheme.LIGHT_PAPER -> R.style.Theme_UsageLimits_App_LightPaper
}

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
        var appliedTheme: AppTheme? = null
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (appliedTheme == null) return false
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })

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
            // DataStore loads on its own dispatcher. No default Composition or overlay is
            // installed until it has answered, even if the account and credit stores are slower.
            val settings = container.settingsStore.settings.collectAsState(initial = null).value ?: return@setContent
            val theme = settings.appTheme
            val windowSizeClass = calculateWindowSizeClass(this)
            UsageLimitsTheme(
                appTheme = theme, providerIcons = settings.providerIcons,
                textFont = settings.textFont, figureFont = settings.figureFont,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.usagelimits.ui.LocalAnimateClaudeCodeIcon provides settings.animateClaudeCodeIcon,
                    com.usagelimits.ui.LocalHoorayPlayback provides (application as UsageLimitsApp).hoorayPlayback,
                ) {
                    UsageLimitsNavigation(container, windowSizeClass, pending.value, ::handled)
                }
            }
            SideEffect {
                if (appliedTheme != theme) {
                    applyAppearance(theme)
                    appliedTheme = theme
                }
            }
        }
    }

    private fun applyAppearance(theme: AppTheme) {
        window.setBackgroundDrawableResource(theme.colors.groundRes)
        val bars = if (theme.isLight) SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
            else SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            splashScreen.setSplashScreenTheme(theme.splashStyle)
        }
    }

    override fun onStart() {
        super.onStart()
        (application as UsageLimitsApp).mainOpening.activityStarted(main = true)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) (application as UsageLimitsApp).mainOpening.interrupt()
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
