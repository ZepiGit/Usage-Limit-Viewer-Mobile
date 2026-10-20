package com.usagelimits.providers.opencode

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

/** OpenCode Go connects with a pasted key, proved against the usage route; there are no regions. */
class OpenCodeProviderTest {

    private val usage = """{"usage":{"rolling":{"status":"ok","percent":40,"resetsAt":"2026-10-07T22:03:52.000Z"}}}"""

    @Test
    fun `the key is proved against the usage route and stored with no region`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val subject = OpenCodeProvider(scriptedHttp(200 to usage, seen = seen))

        val credentials = subject.completeLogin(subject.beginLogin(), " oc-synthetic ")

        val request = seen.single().request
        assertEquals("https://opencode.ai/zen/go/v1/usage", request.url.toString())
        assertEquals("Bearer oc-synthetic", request.header("Authorization"))
        assertEquals("oc-synthetic", credentials.accessToken)
        assertTrue(credentials.providerData.isEmpty())

        val profile = subject.fetchProfile(credentials)
        assertEquals(keyIdentity("oc-synthetic"), profile.externalAccountId)
        assertNull(profile.plan)
    }

    @Test
    fun `a rejected key never becomes credentials`() = runBlocking {
        val subject = OpenCodeProvider(scriptedHttp(401 to "{}"))

        val failure = runCatching { subject.completeLogin(subject.beginLogin(), "oc-bad") }.exceptionOrNull()

        assertTrue("expected Unauthorized, got $failure", failure is ProviderException.Unauthorized)
    }

    @Test
    fun `a body without usage is malformed`() = runBlocking {
        val subject = OpenCodeProvider(scriptedHttp(200 to "{}"))

        val failure = runCatching { subject.completeLogin(subject.beginLogin(), "oc") }.exceptionOrNull()

        assertTrue(failure is ProviderException.MalformedPayload)
    }

    @Test
    fun `a key has no refresh grant`() = runBlocking {
        val credentials = OAuthCredentials("oc", null, null, null)
        assertEquals(credentials, OpenCodeProvider(scriptedHttp(200 to usage)).refresh(credentials))
    }
}
