package com.usagelimits.providers.minimax

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.core.network.ProviderEndpoints.MiniMax
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.RecordedRequest
import com.usagelimits.providers.keyIdentity
import com.usagelimits.providers.scriptedHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MiniMax runs an international (minimax.io) and a China (minimaxi.com) platform with separate
 * accounts, so a plan key only works on the host that issued it. An account with no stored region
 * is international, the region is written with the credential, and the two services file two
 * accounts for one key. All values synthetic.
 */
class MiniMaxRegionTest {

    private val usage = """{"base_resp":{"status_code":0},"model_remains":[
        {"model_name":"general","current_interval_total_count":100,"current_interval_usage_count":40}]}"""

    private fun subject(vararg replies: Pair<Int, String>, seen: MutableList<RecordedRequest>) =
        MiniMaxProvider(scriptedHttp(*replies, seen = seen))

    private fun account(attributes: Map<String, String> = emptyMap()) = ProviderAccount(
        localId = "a", provider = ProviderId.MINIMAX, externalAccountId = "x", email = null,
        displayName = null, plan = null, credentialReference = "minimax_a", createdAt = 0,
        lastSuccessfulSync = null, attributes = attributes,
    )

    @Test
    fun `the hosts and consoles of each region`() {
        assertEquals("https://www.minimax.io/v1/token_plan/remains", MiniMax.urls(ProviderRegion.INTL).usageEndpoint)
        assertEquals("https://www.minimaxi.com/v1/token_plan/remains", MiniMax.urls(ProviderRegion.CN).usageEndpoint)
        assertEquals("https://platform.minimax.io", MiniMax.urls(ProviderRegion.INTL).consoleUrl)
        assertEquals("https://platform.minimaxi.com", MiniMax.urls(ProviderRegion.CN).consoleUrl)
    }

    @Test
    fun `region options, default and labels`() {
        assertEquals(listOf(ProviderRegion.INTL, ProviderRegion.CN), ProviderRegion.options(ProviderId.MINIMAX))
        assertEquals(ProviderRegion.INTL, ProviderRegion.default(ProviderId.MINIMAX))
        assertEquals("International (minimax.io)", ProviderRegion.INTL.label(ProviderId.MINIMAX))
        assertEquals("China (minimaxi.com)", ProviderRegion.CN.label(ProviderId.MINIMAX))
    }

    @Test
    fun `a pasted key is proved on the chosen region and stores it`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val subject = subject(200 to usage, seen = seen)

        val challenge = subject.keyLoginChallenge(ProviderRegion.CN)
        assertEquals(MiniMax.CHINA.consoleUrl, challenge.consoleUrl)
        val credentials = subject.completeLogin(challenge, "mm-synthetic")

        assertEquals("www.minimaxi.com", seen.single().request.url.host)
        assertEquals(mapOf("region" to "cn"), credentials.providerData)
        assertEquals(mapOf("region" to "cn"), subject.fetchProfile(credentials).attributes)
    }

    @Test
    fun `usage reads credentials first, then attributes, then the international default`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val subject = subject(200 to usage, seen = seen)
        val plain = OAuthCredentials("mm", null, null, null)

        subject.fetchUsage(account(), plain)
        subject.fetchUsage(account(mapOf("region" to "cn")), plain)
        subject.fetchUsage(account(), OAuthCredentials("mm", null, null, null, providerData = mapOf("region" to "cn")))
        // The credential wins over a conflicting attribute.
        subject.fetchUsage(
            account(mapOf("region" to "intl")),
            OAuthCredentials("mm", null, null, null, providerData = mapOf("region" to "cn")),
        )
        subject.fetchUsage(account(mapOf("region" to "junk")), plain)

        assertEquals(
            listOf("www.minimax.io", "www.minimaxi.com", "www.minimaxi.com", "www.minimaxi.com", "www.minimax.io"),
            seen.map { it.request.url.host },
        )
    }

    @Test
    fun `the default login is the international one and has no other flow`() = runBlocking {
        val subject = subject(200 to usage, seen = mutableListOf())

        val challenge = subject.beginLogin()
        assertEquals(MiniMax.INTERNATIONAL.consoleUrl, (challenge as LoginChallenge.ApiKey).consoleUrl)
        assertEquals(ProviderRegion.INTL, challenge.region)
        assertEquals(true, subject.keyOnly)
    }

    @Test
    fun `the same key in both regions files two accounts and the international one stays bare`() = runBlocking {
        val subject = subject(200 to usage, seen = mutableListOf())
        val intl = subject.completeLogin(subject.keyLoginChallenge(ProviderRegion.INTL), "mm-synthetic")
        val china = subject.completeLogin(subject.keyLoginChallenge(ProviderRegion.CN), "mm-synthetic")

        assertEquals(keyIdentity("mm-synthetic"), subject.fetchProfile(intl).externalAccountId)
        assertEquals("cn:${keyIdentity("mm-synthetic")}", subject.fetchProfile(china).externalAccountId)
    }
}
