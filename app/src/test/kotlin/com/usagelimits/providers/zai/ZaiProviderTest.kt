package com.usagelimits.providers.zai

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.RecordedRequest
import com.usagelimits.providers.keyIdentity
import com.usagelimits.providers.scriptedHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Z.ai connects with a pasted key, proved against the quota route of the region it was issued on. */
class ZaiProviderTest {

    private val quota = """{"code":200,"data":{"limits":[{"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":12}],"level":"max"},"success":true}"""

    private fun account(attributes: Map<String, String> = emptyMap()) = ProviderAccount(
        localId = "a", provider = ProviderId.ZAI, externalAccountId = "x", email = null, displayName = null,
        plan = null, credentialReference = "zai_a", createdAt = 0, lastSuccessfulSync = null, attributes = attributes,
    )

    private fun key(region: String? = null) = OAuthCredentials(
        "zk-synthetic", null, null, null, providerData = region?.let { mapOf("region" to it) } ?: emptyMap(),
    )

    @Test
    fun `the key is proved on the chosen region's host with a bearer header`() = runBlocking {
        for ((region, host) in listOf(ProviderRegion.INTL to "api.z.ai", ProviderRegion.CN to "open.bigmodel.cn")) {
            val seen = mutableListOf<RecordedRequest>()
            val subject = ZaiProvider(scriptedHttp(200 to quota, seen = seen))

            val challenge = subject.beginLogin(region) as LoginChallenge.ApiKey
            val credentials = subject.completeLogin(challenge, " zk-synthetic ")

            val request = seen.single().request
            assertEquals("https://$host/api/monitor/usage/quota/limit", request.url.toString())
            assertEquals("Bearer zk-synthetic", request.header("Authorization"))
            assertEquals(region.id, credentials.providerData["region"])
            assertEquals("zk-synthetic", credentials.accessToken)
        }
    }

    @Test
    fun `Z ai defaults to the international region`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val subject = ZaiProvider(scriptedHttp(200 to quota, seen = seen))

        val credentials = subject.completeLogin(subject.beginLogin(), "zk-synthetic")

        assertEquals("api.z.ai", seen.single().request.url.host)
        assertEquals("intl", credentials.providerData["region"])
    }

    @Test
    fun `a key the server rejects never becomes credentials`() = runBlocking {
        val subject = ZaiProvider(scriptedHttp(401 to """{"error":"nope"}"""))

        val failure = runCatching { subject.completeLogin(subject.keyLoginChallenge(), "zk-bad") }.exceptionOrNull()

        assertTrue("expected Unauthorized, got $failure", failure is ProviderException.Unauthorized)
    }

    @Test
    fun `a 401 inside a 200 is a rejected key`() = runBlocking {
        val subject = ZaiProvider(scriptedHttp(200 to """{"code":401,"msg":"token expired","success":false}"""))

        val failure = runCatching { subject.completeLogin(subject.keyLoginChallenge(), "zk-bad") }.exceptionOrNull()

        assertTrue("expected Unauthorized, got $failure", failure is ProviderException.Unauthorized)
    }

    @Test
    fun `a body without data is malformed and a blank key is refused locally`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val subject = ZaiProvider(scriptedHttp(200 to """{"code":200,"success":true}""", seen = seen))

        val malformed = runCatching { subject.completeLogin(subject.keyLoginChallenge(), "zk") }.exceptionOrNull()
        val blank = runCatching { subject.completeLogin(subject.keyLoginChallenge(), "  ") }.exceptionOrNull()

        assertTrue(malformed is ProviderException.MalformedPayload)
        assertTrue(blank is ProviderException.LoginCancelled)
        assertEquals("no request for a blank key", 1, seen.size)
    }

    @Test
    fun `usage and profile use the stored region, the digest as identity and the plan`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val subject = ZaiProvider(scriptedHttp(200 to quota, seen = seen))

        val usage = subject.fetchUsage(account(), key("cn"))
        val profile = subject.fetchProfile(key("cn"))
        // Credentials without a region fall back to the account's attribute.
        subject.fetchUsage(account(mapOf("region" to "cn")), key())

        assertEquals(12.0, usage.windows.single().usedPercent!!, 0.0)
        assertEquals(listOf("open.bigmodel.cn", "open.bigmodel.cn", "open.bigmodel.cn"), seen.map { it.request.url.host })
        // International is the default region and keeps the bare digest; China is namespaced.
        assertEquals("cn:" + keyIdentity("zk-synthetic"), profile.externalAccountId)
        assertEquals(keyIdentity("zk-synthetic"), subject.fetchProfile(key("intl")).externalAccountId)
        assertEquals(keyIdentity("zk-synthetic"), subject.fetchProfile(key()).externalAccountId)
        assertFalse(profile.externalAccountId.contains("zk-synthetic"))
        assertEquals("Max", profile.plan)
        assertEquals(mapOf("region" to "cn"), profile.attributes)
    }

    @Test
    fun `a key has no refresh grant`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val credentials = key("intl")

        assertEquals(credentials, ZaiProvider(scriptedHttp(200 to quota, seen = seen)).refresh(credentials))
        assertTrue(seen.isEmpty())
    }
}
