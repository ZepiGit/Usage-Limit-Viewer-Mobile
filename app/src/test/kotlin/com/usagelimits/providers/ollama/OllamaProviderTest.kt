package com.usagelimits.providers.ollama

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.RecordedRequest
import com.usagelimits.providers.keyIdentity
import com.usagelimits.providers.scriptedHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ollama Cloud connects with a pasted key, proved against the balance route; there are no regions. */
class OllamaProviderTest {

    private val balance = """{"included":{"balance_usd":72.5,"allowance_usd":100,
        "period":{"from":"2026-09-15T09:30:00Z","until":"2026-10-15T09:30:00Z"}},
        "purchased":{"balance_usd":25}}"""

    private val account = ProviderAccount(
        localId = "ollama",
        provider = ProviderId.OLLAMA,
        externalAccountId = "key-ollama",
        email = null,
        displayName = null,
        plan = null,
        credentialReference = "ollama",
        createdAt = 0L,
        lastSuccessfulSync = null,
    )

    @Test
    fun `the key is proved against the balance route and stored with no region`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val subject = OllamaProvider(scriptedHttp(200 to balance, seen = seen))

        val credentials = subject.completeLogin(subject.beginLogin(), " ollama-synthetic ")

        val request = seen.single().request
        assertEquals("https://ollama.com/api/balance", request.url.toString())
        assertEquals("Bearer ollama-synthetic", request.header("Authorization"))
        assertEquals("ollama-synthetic", credentials.accessToken)
        assertTrue(credentials.providerData.isEmpty())

        val profile = subject.fetchProfile(credentials)
        assertEquals(keyIdentity("ollama-synthetic"), profile.externalAccountId)
        assertNull(profile.plan)
    }

    @Test
    fun `usage is the included allowance read from the same call`() = runBlocking {
        val subject = OllamaProvider(scriptedHttp(200 to balance))
        val credentials = OAuthCredentials("ollama-synthetic", null, null, null)

        val windows = subject.fetchUsage(account, credentials).windows

        assertEquals(listOf("included"), windows.map { it.id })
        assertEquals(27.5, windows[0].usedPercent!!, 1e-9)
    }

    @Test
    fun `a rejected key never becomes credentials`() = runBlocking {
        val subject = OllamaProvider(scriptedHttp(401 to """{"error":"invalid credentials"}"""))

        val failure = runCatching { subject.completeLogin(subject.beginLogin(), "ollama-bad") }.exceptionOrNull()

        assertTrue("expected Unauthorized, got $failure", failure is ProviderException.Unauthorized)
    }

    @Test
    fun `a body with neither block is malformed rather than an account without limits`() = runBlocking {
        val subject = OllamaProvider(scriptedHttp(200 to "{}"))

        val failure = runCatching { subject.completeLogin(subject.beginLogin(), "ollama") }.exceptionOrNull()

        assertTrue(failure is ProviderException.MalformedPayload)
    }

    @Test
    fun `a key has no refresh grant`() = runBlocking {
        val credentials = OAuthCredentials("ollama", null, null, null)
        assertEquals(credentials, OllamaProvider(scriptedHttp(200 to balance)).refresh(credentials))
    }
}
