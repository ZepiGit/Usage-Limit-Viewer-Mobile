package com.usagelimits.feature.accounts

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.di.AppContainer
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.network.ProviderException
import com.usagelimits.core.oauth.SignInKeepAliveService
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.ProviderRegistry
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A browser redirect lands on a socket in this process, which the cached-apps freezer would
 * freeze with the browser in front; the keep-alive service is up for exactly that wait.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignInKeepAliveTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Hands out [challenge], then parks in `completeLogin` until [gate] opens and it fails. */
    private class FakeProvider(
        private val challenge: LoginChallenge,
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : UsageProvider {
        override val providerId = ProviderId.CLAUDE
        override suspend fun beginLogin() = challenge
        override suspend fun completeLogin(challenge: LoginChallenge, userInput: String?): OAuthCredentials {
            gate.await()
            throw ProviderException.Unexpected("boom")
        }
        override suspend fun refresh(credentials: OAuthCredentials) = credentials
        override suspend fun fetchProfile(credentials: OAuthCredentials): ProviderProfile = error("unused")
        override suspend fun fetchUsage(account: ProviderAccount, credentials: OAuthCredentials): UsageResult =
            error("unused")
    }

    private fun viewModelFor(provider: FakeProvider): AddAccountViewModel {
        val container = object : AppContainer(app) {
            override val providerRegistry = ProviderRegistry(mapOf(ProviderId.CLAUDE to provider))
        }
        return AddAccountViewModel(container)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val redirect = LoginChallenge.Redirect("https://example.test/auth", "http://localhost:1/cb")

    private fun startedService() = shadowOf(app).nextStartedService?.component?.className

    /** Plays the system: runs the started service up to startForeground. */
    private fun runService() = Robolectric.buildService(SignInKeepAliveService::class.java).create().startCommand(0, 1)

    @Test
    fun `a redirect login starts the service and a failed login stops it`() {
        val provider = FakeProvider(redirect)
        val vm = viewModelFor(provider)
        vm.startLogin(app, ProviderId.CLAUDE)
        idle()

        assertEquals(SignInKeepAliveService::class.java.name, startedService())
        val service = runService()
        assertNull(shadowOf(app).nextStoppedService)

        provider.gate.complete(Unit)
        idle()

        assertEquals(SignInKeepAliveService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        assertEquals(true, vm.state.value is AddAccountState.Failed)
        service.destroy()
    }

    @Test
    fun `cancelling a redirect login stops the service`() {
        val vm = viewModelFor(FakeProvider(redirect))
        vm.startLogin(app, ProviderId.CLAUDE)
        idle()
        assertEquals(SignInKeepAliveService::class.java.name, startedService())
        val service = runService()

        vm.cancel()
        idle()

        assertEquals(SignInKeepAliveService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        service.destroy()
    }

    @Test
    fun `device code and key logins never start the service`() {
        val challenges = listOf(
            LoginChallenge.DeviceCode("ABCD|x", "https://example.test/d", null, 0L, 5000L),
            LoginChallenge.ApiKey("https://example.test/keys", "paste it"),
        )
        for (challenge in challenges) {
            val vm = viewModelFor(FakeProvider(challenge))
            vm.startLogin(app, ProviderId.CLAUDE)
            idle()
            assertNull(shadowOf(app).nextStartedService)
            vm.cancel()
            idle()
        }
    }

    @Test
    fun `the service goes foreground and stops itself when the system times it out`() {
        val controller = runService()
        val service = controller.get()
        assertNotNull(shadowOf(service).lastForegroundNotification)

        service.onTimeout(1)
        assertEquals(true, shadowOf(service).isStoppedBySelf)

        controller.destroy()

        val secondController = runService()
        val second = secondController.get()
        second.onTimeout(1, 0)
        assertEquals(true, shadowOf(second).isStoppedBySelf)
        secondController.destroy()
    }

    @Test
    fun `a stop that beats the service to the foreground waits for startForeground`() {
        SignInKeepAliveService.stop(app, SignInKeepAliveService.start(app))

        // No stopService before the service is foreground: that is the crash.
        assertNull(shadowOf(app).nextStoppedService)

        val controller = runService()
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        assertEquals(true, shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun `a stale lease cannot stop a newer login's service`() {
        val a = SignInKeepAliveService.start(app)
        val b = SignInKeepAliveService.start(app)
        val controller = runService()

        SignInKeepAliveService.stop(app, a)
        assertNull(shadowOf(app).nextStoppedService)

        SignInKeepAliveService.stop(app, b)
        assertEquals(SignInKeepAliveService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        controller.destroy()
    }

    private fun hints() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        .filter { it.extras.getString(Notification.EXTRA_TEXT) == "Return to Usage Limits to finish signing in" }

    @Test
    fun `the three-minute timeout leaves a hint to return to the app and ending the login clears it`() {
        val token = SignInKeepAliveService.start(app)
        val controller = runService()

        controller.get().onTimeout(1)

        assertEquals(1, hints().size)
        assertNotNull(hints().single().contentIntent)
        assertEquals(true, shadowOf(controller.get()).isStoppedBySelf)

        SignInKeepAliveService.stop(app, token)
        assertEquals(0, hints().size)
        controller.destroy()
    }

    @Test
    fun `the timeout hint is skipped quietly when notifications are off`() {
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val controller = runService()

        controller.get().onTimeout(1)

        assertEquals(0, hints().size)
        assertEquals(true, shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }
}
