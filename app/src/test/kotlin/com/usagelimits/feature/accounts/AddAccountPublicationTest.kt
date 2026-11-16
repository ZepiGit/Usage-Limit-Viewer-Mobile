package com.usagelimits.feature.accounts

import android.Manifest
import android.app.NotificationManager
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.usagelimits.UsageLimitsApp
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.credits.MemoryCredentialStore
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.ProviderRegistry
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = UsageLimitsApp::class, sdk = [36])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AddAccountPublicationTest {
    private val app: UsageLimitsApp get() = ApplicationProvider.getApplicationContext()
    private val viewModels = ViewModelStore()

    @Before fun setUp() = runBlocking {
        app.startupJob?.join()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        viewModels.clear()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        ShadowLooper.idleMainLooper()
        Dispatchers.resetMain()
    }

    @Test fun `adding an account publishes an alert-worthy first reading`() = runBlocking {
        val provider = object : UsageProvider {
            override val providerId = ProviderId.CLAUDE
            override suspend fun beginLogin() = LoginChallenge.ApiKey("https://example.test", "Key")
            override suspend fun completeLogin(challenge: LoginChallenge, userInput: String?) = OAuthCredentials("fixture", null, null, null)
            override suspend fun refresh(credentials: OAuthCredentials) = credentials
            override suspend fun fetchProfile(credentials: OAuthCredentials) =
                ProviderProfile("fixture", "test@example.com", null, "Pro")
            override suspend fun fetchUsage(account: ProviderAccount, credentials: OAuthCredentials) =
                UsageResult(listOf(UsageWindow("weekly", "Weekly", WindowCategory.WEEKLY,
                    usedPercent = 95.0, resetAt = System.currentTimeMillis() + 3_600_000,
                    periodSeconds = 604_800, exhausted = false)))
        }
        val container = object : AppContainer(app) {
            override val credentialStore = MemoryCredentialStore()
            override val providerRegistry = ProviderRegistry(mapOf(ProviderId.CLAUDE to provider))
        }
        val vm = AddAccountViewModel(container)
        viewModels.put("add", vm)

        vm.startLogin(app, ProviderId.CLAUDE)
        vm.state.first { it is AddAccountState.AwaitingApiKey }
        vm.submitApiKey("fixture")
        val finished = vm.state.first { it is AddAccountState.Success || it is AddAccountState.Failed }

        assertTrue("Login must finish before checking its publication: $finished", finished is AddAccountState.Success)
        val alerts = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
            .filter { it.channelId == "usage_alerts" }
        assertEquals("The first low reading must not wait for another sync", 1, alerts.size)
    }
}
