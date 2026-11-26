package com.usagelimits.providers.minimax

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.MiniMax
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.KeyLoginCapable
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import com.usagelimits.providers.keyIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * MiniMax Token Plan, reached with a plan key the user pasted. The response names no user, so the
 * account is filed under a digest of the key.
 *
 * The key is region-bound: a key minted on the international platform answers only on
 * minimax.io, a China key only on minimaxi.com, so the region is stored with the credential and
 * every call goes to the host that issued it.
 *
 * A business error arrives as HTTP 200 with `base_resp.status_code != 0`, which is why the
 * envelope is read here rather than trusting the status code.
 */
class MiniMaxProvider(private val http: HttpClient) : UsageProvider, KeyLoginCapable {

    override val providerId = ProviderId.MINIMAX

    override val keyOnly = true

    override suspend fun beginLogin(): LoginChallenge = beginLogin(ProviderRegion.default(ProviderId.MINIMAX))

    override suspend fun beginLogin(region: ProviderRegion): LoginChallenge = keyLoginChallenge(region)

    override fun keyLoginChallenge(region: ProviderRegion?): LoginChallenge.ApiKey {
        val chosen = region ?: ProviderRegion.default(ProviderId.MINIMAX)
        return LoginChallenge.ApiKey(
            consoleUrl = MiniMax.urls(chosen).consoleUrl,
            hint = "Create a MiniMax key in your MiniMax console and paste it here. It is stored on " +
                "this device only, in the same encrypted store as every other account.",
            region = chosen,
        )
    }

    override suspend fun completeLogin(challenge: LoginChallenge, userInput: String?): OAuthCredentials =
        when (challenge) {
            is LoginChallenge.ApiKey -> connectWithKey(userInput, challenge.region)
            is LoginChallenge.DeviceCode -> throw ProviderException.Unexpected("MiniMax has no device flow")
            is LoginChallenge.Redirect -> throw ProviderException.Unexpected("MiniMax does not redirect")
        }

    /** A key has no refresh grant. */
    override suspend fun refresh(credentials: OAuthCredentials): OAuthCredentials = credentials

    override suspend fun fetchProfile(credentials: OAuthCredentials): ProviderProfile {
        fetchPayload(credentials)
        val region = ProviderRegion.stored(ProviderId.MINIMAX, credentials.providerData)
        return ProviderProfile(
            // The same key on the two services is two subscriptions, so the id is region-scoped.
            externalAccountId = ProviderRegion.scopedId(
                ProviderId.MINIMAX,
                region,
                keyIdentity(credentials.accessToken),
            ),
            email = null,
            displayName = null,
            plan = null,
            attributes = mapOf(ProviderRegion.KEY to region.id),
        )
    }

    override suspend fun fetchUsage(account: ProviderAccount, credentials: OAuthCredentials): UsageResult =
        UsageResult(windows = MiniMaxUsageParser.parse(fetchPayload(credentials, account.attributes)))

    private suspend fun connectWithKey(userInput: String?, region: ProviderRegion?): OAuthCredentials {
        val key = userInput?.trim()
        if (key.isNullOrEmpty()) throw ProviderException.LoginCancelled("No key was entered")

        val credentials = OAuthCredentials(
            accessToken = key,
            refreshToken = null,
            idToken = null,
            expiresAt = null,
            // Stored with the key, where a later call can read it.
            providerData = mapOf(ProviderRegion.KEY to (region ?: ProviderRegion.default(ProviderId.MINIMAX)).id),
        )
        // Proved here, before an account row exists: a key that cannot read usage is not an account.
        fetchPayload(credentials)
        return credentials
    }

    /**
     * The quota call, with the region's envelope checked first.
     *
     * `model_remains` is required: a body without it is not this endpoint's answer and must not
     * read as "this plan has no limits".
     */
    private suspend fun fetchPayload(
        credentials: OAuthCredentials,
        attributes: Map<String, String> = emptyMap(),
    ): JsonObject {
        val region = ProviderRegion.stored(ProviderId.MINIMAX, credentials.providerData, attributes)
        val response = http.request(
            url = MiniMax.urls(region).usageEndpoint,
            // Only the user's own key, under the auth header the CLI uses. No client identity is
            // sent: the CLI's `User-Agent: mmx-cli/<version>` is deliberately not copied.
            headers = mapOf(
                "Authorization" to "Bearer ${credentials.accessToken}",
                "Accept" to "application/json",
            ),
        )
        val payload = JsonSupport.parseObject(response.body)
        envelopeFailure(payload)?.let { throw it }
        if (JsonSupport.field(payload, "model_remains") !is JsonArray) {
            throw ProviderException.MalformedPayload("model_remains is missing")
        }
        return payload
    }

    /**
     * MiniMax's envelope carries its own status on an HTTP 200.
     *
     * 1004 is the rejected-key code the unauthenticated probe received; every other non-zero code
     * is a failure this parser knows nothing about, so it stays unknown rather than empty.
     */
    private fun envelopeFailure(payload: JsonObject): ProviderException? {
        val envelope = JsonSupport.obj(payload, "base_resp") ?: return null
        val code = JsonSupport.long(envelope, "status_code") ?: return null
        if (code == 0L) return null
        if (code == STATUS_CODE_LOGIN_FAILED) return ProviderException.Unauthorized()
        val message = JsonSupport.string(envelope, "status_msg") ?: "no message"
        return ProviderException.MalformedPayload("base_resp $code: $message")
    }

    private companion object {
        /** `base_resp.status_code` for "the request carried no usable API secret key". */
        const val STATUS_CODE_LOGIN_FAILED = 1004L
    }
}
