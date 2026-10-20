package com.usagelimits.providers.zai

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Zai
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.KeyLoginCapable
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import com.usagelimits.providers.keyIdentity
import kotlinx.serialization.json.JsonObject

/**
 * Z.ai GLM Coding Plan, reached with an API key the user pasted. See docs/providers-zai.md.
 *
 * The key is region-bound (see [ProviderRegion]) and the response names no user, so the account
 * is filed under a digest of the key. There is no refresh grant.
 */
class ZaiProvider(private val http: HttpClient) : UsageProvider, KeyLoginCapable {

    override val providerId = ProviderId.ZAI

    override val keyOnly = true

    override suspend fun beginLogin(): LoginChallenge = keyLoginChallenge()

    override suspend fun beginLogin(region: ProviderRegion): LoginChallenge = keyLoginChallenge(region)

    override fun keyLoginChallenge(region: ProviderRegion?): LoginChallenge.ApiKey {
        val chosen = region ?: ProviderRegion.default(ProviderId.ZAI)
        return LoginChallenge.ApiKey(
            consoleUrl = Zai.consoleUrl(chosen),
            hint = "Create a key in your Z.ai console and paste it here. It is stored on this " +
                "device only, in the same encrypted store as every other account.",
            region = chosen,
        )
    }

    override suspend fun completeLogin(challenge: LoginChallenge, userInput: String?): OAuthCredentials {
        val key = userInput?.trim()
        if (key.isNullOrEmpty()) throw ProviderException.LoginCancelled("No key was entered")
        val credentials = OAuthCredentials(
            accessToken = key,
            refreshToken = null,
            idToken = null,
            expiresAt = null,
            providerData = mapOf(
                ProviderRegion.KEY to ((challenge as? LoginChallenge.ApiKey)?.region
                    ?: ProviderRegion.default(ProviderId.ZAI)).id,
            ),
        )
        // Proved here, before an account row exists: a key that cannot read usage is not an account.
        fetchPayload(credentials)
        return credentials
    }

    /** A key has no refresh grant. */
    override suspend fun refresh(credentials: OAuthCredentials): OAuthCredentials = credentials

    override suspend fun fetchProfile(credentials: OAuthCredentials): ProviderProfile {
        val payload = fetchPayload(credentials)
        return ProviderProfile(
            externalAccountId = keyIdentity(credentials.accessToken),
            email = null,
            displayName = null,
            plan = ZaiUsageParser.plan(payload),
            attributes = mapOf(ProviderRegion.KEY to region(credentials).id),
        )
    }

    override suspend fun fetchUsage(account: ProviderAccount, credentials: OAuthCredentials): UsageResult =
        UsageResult(windows = ZaiUsageParser.parse(fetchPayload(credentials, account.attributes)))

    private fun region(credentials: OAuthCredentials, attributes: Map<String, String> = emptyMap()) =
        ProviderRegion.stored(ProviderId.ZAI, credentials.providerData, attributes)

    private suspend fun fetchPayload(
        credentials: OAuthCredentials,
        attributes: Map<String, String> = emptyMap(),
    ): JsonObject {
        val response = http.request(
            url = Zai.usageEndpoint(region(credentials, attributes)),
            headers = mapOf(
                "Authorization" to "Bearer ${credentials.accessToken}",
                "Accept" to "application/json",
            ),
        )
        val payload = JsonSupport.parseObject(response.body)
        // Z.ai can refuse inside a 200: {"code":401,"success":false,...}.
        if (JsonSupport.long(payload, "code") == 401L) throw ProviderException.Unauthorized()
        if (JsonSupport.obj(payload, "data") == null) throw ProviderException.MalformedPayload("quota has no data")
        return payload
    }
}
