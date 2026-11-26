package com.usagelimits.providers.minimax

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.RecordedRequest
import com.usagelimits.providers.keyIdentity
import com.usagelimits.providers.scriptedHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MiniMax's plan key is proved against the quota route; the envelope, not the HTTP status, decides. */
class MiniMaxProviderTest {

    private val usage = """{"base_resp":{"status_code":0},"model_remains":[
        {"model_name":"general","current_interval_total_count":100,"current_interval_usage_count":40}]}"""

    @Test
    fun `the key is proved against the international host and stored with its region`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val subject = MiniMaxProvider(scriptedHttp(200 to usage, seen = seen))

        val credentials = subject.completeLogin(subject.beginLogin(), " mm-synthetic ")

        val request = seen.single().request
        assertEquals("https://www.minimax.io/v1/token_plan/remains", request.url.toString())
        assertEquals("Bearer mm-synthetic", request.header("Authorization"))
        // The CLI's own User-Agent is not copied: this app sends its own default one.
        assertNull(request.header("X-Msh-Platform"))
        assertEquals("mm-synthetic", credentials.accessToken)
        assertEquals(mapOf("region" to "intl"), credentials.providerData)

        val profile = subject.fetchProfile(credentials)
        assertEquals(keyIdentity("mm-synthetic"), profile.externalAccountId)
        assertEquals(mapOf("region" to "intl"), profile.attributes)
        assertNull(profile.plan)
    }

    @Test
    fun `a rejected key is a 200 with base_resp 1004 and never becomes credentials`() = runBlocking {
        val subject = MiniMaxProvider(
            scriptedHttp(200 to """{"base_resp":{"status_code":1004,"status_msg":"login fail"}}"""),
        )

        val failure = runCatching { subject.completeLogin(subject.beginLogin(), "mm-bad") }.exceptionOrNull()

        assertTrue("expected Unauthorized, got $failure", failure is ProviderException.Unauthorized)
    }

    @Test
    fun `any other envelope code is an unreadable answer, not an empty plan`() = runBlocking {
        val subject = MiniMaxProvider(scriptedHttp(200 to """{"base_resp":{"status_code":2013}}"""))

        val failure = runCatching { subject.completeLogin(subject.beginLogin(), "mm") }.exceptionOrNull()

        assertTrue("expected MalformedPayload, got $failure", failure is ProviderException.MalformedPayload)
    }

    @Test
    fun `a body without model_remains is malformed`() = runBlocking {
        val subject = MiniMaxProvider(scriptedHttp(200 to """{"base_resp":{"status_code":0}}"""))

        val failure = runCatching { subject.completeLogin(subject.beginLogin(), "mm") }.exceptionOrNull()

        assertTrue("expected MalformedPayload, got $failure", failure is ProviderException.MalformedPayload)
    }

    @Test
    fun `no key entered cancels the login before any call`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val subject = MiniMaxProvider(scriptedHttp(200 to usage, seen = seen))

        val failure = runCatching { subject.completeLogin(subject.beginLogin(), "   ") }.exceptionOrNull()

        assertTrue("expected LoginCancelled, got $failure", failure is ProviderException.LoginCancelled)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a key has no refresh grant`() = runBlocking {
        val credentials = OAuthCredentials("mm", null, null, null)
        assertEquals(credentials, MiniMaxProvider(scriptedHttp(200 to usage)).refresh(credentials))
    }
}
