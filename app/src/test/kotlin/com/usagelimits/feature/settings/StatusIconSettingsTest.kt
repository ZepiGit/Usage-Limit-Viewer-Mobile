package com.usagelimits.feature.settings

import android.Manifest
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.sync.resetTestWorkManager
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import org.robolectric.shadows.ShadowLooper
import com.usagelimits.core.database.AccountUsage
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.settings.AppSettings
import com.usagelimits.feature.UsageUiState
import com.usagelimits.feature.waitDrained
import com.usagelimits.ui.theme.UsageLimitsTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [34])
class StatusIconSettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()

    @Before fun prepare(): Unit = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        app.container.settingsStore.setStatusIconsEnabled(false)
        app.container.settingsStore.setPinnedAccounts(emptyList())
        app.container.settingsStore.recordStatusIconEligibility(true)
    }

    @After fun drain() {
        lateinit var barrier: kotlinx.coroutines.Job
        compose.runOnUiThread { barrier = app.observeStatusIconEligibility() }
        compose.waitDrained { barrier.isCompleted }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        resetTestWorkManager()
    }

    private fun usage(id: String, name: String, provider: ProviderId) = AccountUsage(
        ProviderAccount(id, provider, "ext-$id", null, name, null, "cred-$id", 0L, null), snapshot = null,
    )

    private val accounts = listOf(
        usage("a", "Claude work", ProviderId.CLAUDE),
        usage("b", "Codex home", ProviderId.CODEX),
        usage("c", "Grok side", ProviderId.XAI),
        usage("d", "Kimi spare", ProviderId.KIMI),
    )

    private val credit = CreditEntry(CreditAccount("credit-1", CreditProviderId.DEEPSEEK, "DeepSeek balance", "credit:credit-1"), snapshot = null)

    private var enabled: Boolean? = null
    private var pinned: Pair<String, Boolean>? = null

    private fun render(settings: AppSettings, credits: List<CreditEntry> = emptyList()) {
        compose.setContent {
            UsageLimitsTheme {
                SettingsScreen(
                    state = UsageUiState(accounts = accounts, settings = settings, credits = credits),
                    onSyncIntervalChange = {}, onNotificationPermissionHandled = {}, onAppThemeChange = {}, onNotifyBelow20 = {}, onNotifyBelow10 = {},
                    onNotifyExhausted = {}, onNotifyResetCredit = {}, onNotifyAuthExpired = {}, onNotifyResetApproaching = {},
                    onNotifyCreditExpiring = {}, onShowTier = {}, onOverviewLayoutChange = {}, onPercentDisplayChange = {},
                    onStatusIconsChange = { enabled = it },
                    onPinnedChange = { id, pin -> pinned = id to pin },
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
    fun `each toggle reports its own account and the wanted state`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(statusIconsEnabled = true, pinnedAccountIds = listOf("b")))
        scrollTo("Claude work")
        compose.onNodeWithContentDescription("Pin Codex home").assertIsOn()
        compose.onNodeWithContentDescription("Pin Claude work").assertIsOff().performClick()
        assertEquals("a" to true, pinned)
        scrollTo("Codex home")
        compose.onNodeWithContentDescription("Pin Codex home").performClick()
        assertEquals("b" to false, pinned)
    }

    @Test
    fun `with three pinned the section says so and refuses a fourth`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(statusIconsEnabled = true, pinnedAccountIds = listOf("a", "b", "c")))
        scrollTo("3 of 3 pinned")
        compose.onNodeWithText("3 of 3 pinned").assertIsDisplayed()
        scrollTo("Kimi spare")
        compose.onNodeWithContentDescription("Pin Kimi spare. 3 of 3 are pinned; unpin one first.")
            .assertIsOff().assertIsNotEnabled().performClick()
        assertEquals("a fourth pin is refused", null, pinned)
        compose.onNodeWithContentDescription("Pin Grok side").assertIsOn().assertIsEnabled()
    }

    @Test
    fun `a credit account is never offered for pinning`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(statusIconsEnabled = true), credits = listOf(credit))
        scrollTo("Kimi spare")
        compose.onNodeWithContentDescription("Pin Kimi spare").assertExists()
        compose.onNodeWithContentDescription("Pin DeepSeek balance").assertDoesNotExist()
    }

    @Test
    fun `the account list is hidden while the feature is off`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(statusIconsEnabled = false))
        scrollTo("Show status bar icons")
        compose.onNodeWithContentDescription("Pin Claude work").assertDoesNotExist()
    }

    @Test
    fun `the section states the platform's limits`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(statusIconsEnabled = true))
        val note = "Android decides how many icons fit. Notification settings control their visibility."
        scrollTo(note)
        compose.onNodeWithText(note).assertIsDisplayed()
    }

    @Test
    fun `turning it on without permission starts the notification permission flow and says icons cannot be shown`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(notificationPermissionHandled = false))
        scrollTo("Status bar icons cannot be shown until notifications are allowed.")
        compose.onNodeWithText("Status bar icons cannot be shown until notifications are allowed.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show status bar icons").performClick()
        assertEquals(true, enabled)
        compose.waitDrained { shadowOf(compose.activity).lastRequestedPermission != null }
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
    fun `German Settings offers the section, its blocked note, the pins and the limit`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        render(AppSettings(statusIconsEnabled = true, pinnedAccountIds = listOf("a", "b", "c")))
        scrollTo("Statusleisten-Symbole können erst angezeigt werden, wenn Benachrichtigungen erlaubt sind.")
        compose.onNodeWithContentDescription("Statusleisten-Symbole anzeigen").assertIsOn()
        compose.onNodeWithText("Statusleisten-Symbole können erst angezeigt werden, wenn Benachrichtigungen erlaubt sind.").assertIsDisplayed()
        scrollTo("3 von 3 angeheftet")
        compose.onNodeWithText("3 von 3 angeheftet").assertIsDisplayed()
        scrollTo("Kimi spare")
        compose.onNodeWithContentDescription("Codex home anheften").assertIsOn()
        compose.onNodeWithContentDescription("Kimi spare anheften. 3 von 3 sind angeheftet; löse zuerst eines.")
            .assertIsOff().assertIsNotEnabled()
    }
}
