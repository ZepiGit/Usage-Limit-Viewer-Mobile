package com.usagelimits.feature.settings

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.feature.UsageUiState
import com.usagelimits.ui.theme.UsageLimitsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatusIconSettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun usage(id: String, name: String, provider: ProviderId) = AccountUsage(
        ProviderAccount(id, provider, "ext-$id", null, name, null, "cred-$id", 0L, null), snapshot = null,
    )

    private val accounts = listOf(usage("a", "Claude work", ProviderId.CLAUDE), usage("b", "Codex home", ProviderId.CODEX))

    private var enabled: Boolean? = null
    private var pinned: String? = "untouched"

    private fun render(settings: AppSettings, credits: List<CreditEntry> = emptyList()) {
        compose.setContent {
            UsageLimitsTheme {
                SettingsScreen(
                    state = UsageUiState(accounts = accounts, settings = settings, credits = credits),
                    onSyncIntervalChange = {}, onNotificationPermissionAsked = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {},
                    onNotifyExhausted = {}, onNotifyResetCredit = {}, onNotifyAuthExpired = {}, onNotifyResetApproaching = {},
                    onNotifyCreditExpiring = {}, onShowTier = {}, onShowRenewal = {}, onPercentDisplayChange = {},
                    onStatusIconsChange = { enabled = it },
                    onPinnedAccountChange = { pinned = it },
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun `the switch is off by default and turning it on asks for nothing when notifications are allowed`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings())
        scrollTo("Show status bar icons")
        compose.onNodeWithContentDescription("Show status bar icons").assertIsOff().performClick()
        assertEquals(true, enabled)
        assertNull(shadowOf(compose.activity).lastRequestedPermission)
    }

    @Test
    fun `choosing an account replaces the previous choice and choosing it again clears it`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(statusIconsEnabled = true, pinnedAccountIds = listOf("a")))
        scrollTo("Codex home")
        compose.onNodeWithText("Claude work").assertIsSelected()
        compose.onNodeWithText("Codex home").assertIsNotSelected().performClick()
        assertEquals("b", pinned)
        compose.onNodeWithText("Claude work").performClick()
        assertEquals("tapping the chosen account clears the choice", null, pinned)
    }

    @Test
    fun `credit accounts are not offered and the choice is hidden while the feature is off`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(statusIconsEnabled = false))
        scrollTo("Show status bar icons")
        compose.onNodeWithText("Claude work").assertDoesNotExist()
    }

    @Test
    fun `turning it on without permission starts the notification permission flow and says icons cannot be shown`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(notificationPermissionHandled = false))
        scrollTo("Status bar icons cannot be shown until notifications are allowed.")
        compose.onNodeWithText("Status bar icons cannot be shown until notifications are allowed.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show status bar icons").performClick()
        assertEquals(true, enabled)
        compose.waitUntil(10_000) { shadowOf(compose.activity).lastRequestedPermission != null }
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, shadowOf(compose.activity).lastRequestedPermission.requestedPermissions.single())
    }

    @Test
    fun `when Android no longer shows the dialog, turning it on opens notification settings`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(notificationPermissionHandled = true))
        scrollTo("Show status bar icons")
        compose.onNodeWithContentDescription("Show status bar icons").performClick()
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
    }

    @Test
    fun `an enabled switch reads as on`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(statusIconsEnabled = true))
        scrollTo("Show status bar icons")
        compose.onNodeWithContentDescription("Show status bar icons").assertIsOn()
    }

    @Test
    @Config(qualifiers = "de")
    fun `German Settings offers the section and its blocked note`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(statusIconsEnabled = true, pinnedAccountIds = listOf("b")))
        scrollTo("Statusleisten-Symbole können erst angezeigt werden, wenn Benachrichtigungen erlaubt sind.")
        compose.onNodeWithContentDescription("Statusleisten-Symbole anzeigen").assertIsOn()
        compose.onNodeWithText("Statusleisten-Symbole können erst angezeigt werden, wenn Benachrichtigungen erlaubt sind.").assertIsDisplayed()
        scrollTo("Codex home")
        compose.onNodeWithText("Codex home").assertIsSelected()
    }
}
