package com.usagelimits.providers.kimi

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.core.network.ProviderEndpoints.Kimi
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.RecordedRequest
import com.usagelimits.providers.formFields
import com.usagelimits.providers.scriptedHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kimi runs a China (kimi.com) and an international (kimi.ai) service with separate accounts.
 * Every call after the region is picked must reach the host that issued the credential, and an
 * account saved before the choice existed must stay on kimi.com. All values synthetic.
 */
class KimiRegionTest {

    private val deviceJson = """{"device_code":"dev-1","user_code":"KIMI-ABCD","verification_uri":"https://x/device","expires_in":900,"interval":5}"""
    private val tokenJson = """{"access_token":"acc","refresh_token":"ref2","expires_in":3600}"""
    private val usageJson = """{"userId":"kimi-user-7","usage":{"limit":100,"used":10}}"""

    private fun subject(vararg replies: Pair<Int, String>, seen: MutableList<RecordedRequest>) =
        KimiProvider(scriptedHttp(*replies, seen = seen), nowMs = { 1_000_000L }, version = "9.9.9")

    private fun account(attributes: Map<String, String> = emptyMap()) = ProviderAccount(
        localId = "a", provider = ProviderId.KIMI, externalAccountId = "x", email = null, displayName = null,
        plan = null, credentialReference = "kimi_a", createdAt = 0, lastSuccessfulSync = null, attributes = attributes,
    )

    @Test
    fun `the hosts of each region`() {
        assertEquals("https://auth.kimi.com/api/oauth/device_authorization", Kimi.urls(ProviderRegion.CN).deviceCodeUrl)
        assertEquals("https://auth.kimi.com/api/oauth/token", Kimi.urls(ProviderRegion.CN).tokenUrl)
        assertEquals("https://api.kimi.com/coding/v1/usages", Kimi.urls(ProviderRegion.CN).usageEndpoint)
        assertEquals("https://auth.kimi.ai/api/oauth/device_authorization", Kimi.urls(ProviderRegion.INTL).deviceCodeUrl)
        assertEquals("https://auth.kimi.ai/api/oauth/token", Kimi.urls(ProviderRegion.INTL).tokenUrl)
        assertEquals("https://api.kimi.ai/coding/v1/usages", Kimi.urls(ProviderRegion.INTL).usageEndpoint)
    }

    @Test
    fun `international device login begins and polls on kimi ai and records the region`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val kimi = subject(200 to deviceJson, 200 to tokenJson, seen = seen)

        val challenge = (kimi.beginLogin(ProviderRegion.INTL) as LoginChallenge.DeviceCode).copy(pollIntervalMs = 0)
        val credentials = kimi.completeLogin(challenge, null)

        assertEquals("KIMI-ABCD", KimiProvider.displayCode(challenge.userCode))
        assertEquals("auth.kimi.ai", seen[0].request.url.host)
        assertEquals("auth.kimi.ai", seen[1].request.url.host)
        assertEquals("dev-1", formFields(seen[1].body)["device_code"])
        assertEquals("intl", credentials.providerData["region"])
    }

    @Test
    fun `the default login and a challenge from before the choice stay on kimi com`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val kimi = subject(200 to deviceJson, 200 to tokenJson, 200 to tokenJson, seen = seen)

        val begun = (kimi.beginLogin() as LoginChallenge.DeviceCode).copy(pollIntervalMs = 0)
        assertEquals("auth.kimi.com", seen[0].request.url.host)
        assertEquals("cn", kimi.completeLogin(begun, null).providerData["region"])

        val legacy = begun.copy(userCode = "KIMI-ABCD|dev-1")
        val credentials = kimi.completeLogin(legacy, null)
        assertEquals("auth.kimi.com", seen[2].request.url.host)
        assertEquals("cn", credentials.providerData["region"])
    }

    @Test
    fun `refresh goes to the issuing host, keeps the region, and a legacy credential refreshes on kimi com`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val kimi = subject(200 to tokenJson, seen = seen)

        val intl = kimi.refresh(
            OAuthCredentials("old", "ref1", null, 0, providerData = mapOf("region" to "intl", "other" to "kept")),
        )
        assertEquals("auth.kimi.ai", seen[0].request.url.host)
        assertEquals("ref2", intl.refreshToken)
        assertEquals(mapOf("region" to "intl", "other" to "kept"), intl.providerData)

        val legacy = kimi.refresh(OAuthCredentials("old", "ref1", null, 0))
        assertEquals("auth.kimi.com", seen[1].request.url.host)
        assertEquals(emptyMap<String, String>(), legacy.providerData)
    }

    @Test
    fun `usage reads credentials first, then attributes, then the China default`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val kimi = subject(200 to usageJson, seen = seen)
        val plain = OAuthCredentials("sk", null, null, null)

        kimi.fetchUsage(account(), OAuthCredentials("sk", null, null, null, providerData = mapOf("region" to "intl")))
        kimi.fetchUsage(account(mapOf("region" to "intl")), plain)
        kimi.fetchUsage(account(), plain)
        kimi.fetchUsage(account(mapOf("region" to "junk")), plain)
        // The credential wins over a conflicting attribute.
        kimi.fetchUsage(
            account(mapOf("region" to "intl")),
            OAuthCredentials("sk", null, null, null, providerData = mapOf("region" to "cn")),
        )

        assertEquals(
            listOf("api.kimi.ai", "api.kimi.ai", "api.kimi.com", "api.kimi.com", "api.kimi.com"),
            seen.map { it.request.url.host },
        )
    }

    @Test
    fun `a pasted key is proved on the chosen region and stores it`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val kimi = subject(200 to usageJson, seen = seen)

        val challenge = kimi.keyLoginChallenge(ProviderRegion.INTL)
        assertEquals(Kimi.INTERNATIONAL.consoleUrl, challenge.consoleUrl)
        val credentials = kimi.completeLogin(challenge, "sk-synthetic")

        assertEquals("api.kimi.ai", seen.single().request.url.host)
        assertEquals("intl", credentials.providerData["region"])
        assertEquals(mapOf("region" to "intl"), kimi.fetchProfile(credentials).attributes)
    }

    @Test
    fun `the same user id in both regions files two accounts, and the China id stays as it was`() = runBlocking {
        val kimi = subject(200 to usageJson, seen = mutableListOf())
        val china = OAuthCredentials("tok-cn", "ref", null, 0, providerData = mapOf("region" to "cn"))
        val intl = OAuthCredentials("tok-intl", "ref", null, 0, providerData = mapOf("region" to "intl"))
        val legacy = OAuthCredentials("tok-old", "ref", null, 0)

        assertEquals("kimi-user-7", kimi.fetchProfile(china).externalAccountId)
        assertEquals("kimi-user-7", kimi.fetchProfile(legacy).externalAccountId)
        assertEquals("intl:kimi-user-7", kimi.fetchProfile(intl).externalAccountId)
    }

    @Test
    fun `the same key in both regions files two accounts`() = runBlocking {
        val kimi = subject(200 to """{"usage":{"limit":100,"used":10}}""", seen = mutableListOf())
        val cn = kimi.completeLogin(kimi.keyLoginChallenge(ProviderRegion.CN), "sk-synthetic")
        val intl = kimi.completeLogin(kimi.keyLoginChallenge(ProviderRegion.INTL), "sk-synthetic")

        val china = kimi.fetchProfile(cn).externalAccountId
        assertEquals(kimi.identity(JsonObject(emptyMap()), "sk-synthetic"), china)
        assertEquals("intl:$china", kimi.fetchProfile(intl).externalAccountId)
    }

    @Test
    fun `region lookup and options`() {
        assertEquals(listOf(ProviderRegion.CN, ProviderRegion.INTL), ProviderRegion.options(ProviderId.KIMI))
        assertEquals(listOf(ProviderRegion.INTL, ProviderRegion.CN), ProviderRegion.options(ProviderId.ZAI))
        assertEquals(emptyList<ProviderRegion>(), ProviderRegion.options(ProviderId.OPENCODE))
        assertEquals(ProviderRegion.CN, ProviderRegion.default(ProviderId.KIMI))
        assertEquals(ProviderRegion.INTL, ProviderRegion.default(ProviderId.ZAI))
        assertEquals("International (kimi.ai)", ProviderRegion.INTL.label(ProviderId.KIMI))
        assertEquals("China (bigmodel.cn)", ProviderRegion.CN.label(ProviderId.ZAI))
    }
}
