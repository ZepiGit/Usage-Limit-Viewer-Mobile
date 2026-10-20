package com.usagelimits.providers.opencode

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.OpenCode
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.KeyLoginCapable
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import com.usagelimits.providers.keyIdentity
import kotlinx.serialization.json.JsonObject

/**
 * OpenCode Go, reached with an API key the user pasted. No regions, no refresh grant. The
 * response names no user, so the account is filed under a digest of the key. See
 * docs/providers-opencode.md.
 */
class OpenCodeProvider(private val http: HttpClient) : UsageProvider, KeyLoginCapable {

    override val providerId = ProviderId.OPENCODE

    override val keyOnly = true

    override suspend fun beginLogin(): LoginChallenge = keyLoginChallenge()

    override fun keyLoginChallenge(region: ProviderRegion?) = LoginChallenge.ApiKey(
        consoleUrl = OpenCode.CONSOLE_URL,
        hint = "Create an OpenCode Go key in your OpenCode console and paste it here. It is stored on " +
            "this device only, in the same encrypted store as every other account.",
    )

    override suspend fun completeLogin(challenge: LoginChallenge, userInput: String?): OAuthCredentials {
        val key = userInput?.trim()
        if (key.isNullOrEmpty()) throw ProviderException.LoginCancelled("No key was entered")
        val credentials = OAuthCredentials(accessToken = key, refreshToken = null, idToken = null, expiresAt = null)
        // Proved here, before an account row exists: a key that cannot read usage is not an account.
        fetchPayload(credentials)
        return credentials
    }

    /** A key has no refresh grant. */
    override suspend fun refresh(credentials: OAuthCredentials): OAuthCredentials = credentials

    override suspend fun fetchProfile(credentials: OAuthCredentials): ProviderProfile {
        fetchPayload(credentials)
        return ProviderProfile(
            externalAccountId = keyIdentity(credentials.accessToken),
            email = null,
            displayName = null,
            plan = null,
        )
    }

    override suspend fun fetchUsage(account: ProviderAccount, credentials: OAuthCredentials): UsageResult =
        UsageResult(windows = OpenCodeUsageParser.parse(fetchPayload(credentials)))

    private suspend fun fetchPayload(credentials: OAuthCredentials): JsonObject {
        val response = http.request(
            url = OpenCode.USAGE_ENDPOINT,
            headers = mapOf(
                "Authorization" to "Bearer ${credentials.accessToken}",
                "Accept" to "application/json",
            ),
        )
        val payload = JsonSupport.parseObject(response.body)
        if (JsonSupport.obj(payload, "usage") == null) throw ProviderException.MalformedPayload("usage missing")
        return payload
    }
}
