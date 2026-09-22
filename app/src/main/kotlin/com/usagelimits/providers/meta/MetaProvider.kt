package com.usagelimits.providers.meta

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Meta
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.DeviceCodeLoginCapable
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.net.URI

/**
 * Meta Muse Code OAuth and subscription quota provider.
 *
 * Meta's device grant produces a DCA (device client access) token. That token is exchanged for
 * an LLM API key, which is what normal Muse requests use. Subscription quota is the exception:
 * `/muse-code/key` accepts the DCA token as its bearer credential and returns both profile and
 * usage fields. The two values therefore remain separate in [OAuthCredentials.providerData].
 */
class MetaProvider(
    private val http: HttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : UsageProvider, DeviceCodeLoginCapable {

    override val providerId: ProviderId = ProviderId.META

    override val supportsResetCredits = false

    override suspend fun beginLogin(): LoginChallenge {
        val response = http.request(
            url = Meta.DEVICE_AUTHORIZATION_ENDPOINT,
            method = "POST",
            headers = oauthHeaders(),
            body = HttpClient.formBody(mapOf("client_id" to Meta.CLIENT_ID)),
        )
        val payload = JsonSupport.parseObject(response.body)

        val userCode = JsonSupport.string(payload, "user_code", "userCode")
            ?: throw ProviderException.MalformedPayload("Meta device response had no user code")
        val deviceCode = JsonSupport.string(payload, "device_code", "deviceCode")
            ?: throw ProviderException.MalformedPayload("Meta device response had no device code")
        val verification = JsonSupport.string(
            payload,
            "verification_uri",
            "verificationUri",
        )?.let(::validateVerificationUri)
            ?: JsonSupport.string(
                payload,
                "verification_uri_complete",
                "verificationUriComplete",
            )?.let(::validateVerificationUri)
            ?: throw ProviderException.MalformedPayload("Meta device response had no verification URI")
        val verificationComplete = JsonSupport.string(
            payload,
            "verification_uri_complete",
            "verificationUriComplete",
        )?.let(::validateVerificationUri)

        val expiresIn = JsonSupport.long(payload, "expires_in", "expiresIn")
            ?.takeIf { it > 0 }
            ?: DEFAULT_EXPIRES_SECONDS
        val intervalSeconds = JsonSupport.long(payload, "interval")
            ?.takeIf { it > 0 }
            ?: MIN_POLL_SECONDS

        return LoginChallenge.DeviceCode(
            // The user-visible code is the first segment; keeping the device code in the
            // challenge lets completeLogin remain stateless without putting it on screen.
            userCode = "$userCode$CODE_SEPARATOR$deviceCode",
            verificationUri = verification,
            verificationUriComplete = verificationComplete,
            expiresAt = JsonSupport.expiryAfterSeconds(expiresIn, nowMs())
                ?: (nowMs() + DEFAULT_EXPIRES_SECONDS * 1000L),
            pollIntervalMs = JsonSupport.secondsToMillis(
                maxOf(intervalSeconds, MIN_POLL_SECONDS),
            ) ?: MIN_POLL_SECONDS * 1000L,
        )
    }

    override suspend fun deviceLoginChallenge(): LoginChallenge = beginLogin()

    override suspend fun completeLogin(
        challenge: LoginChallenge,
        userInput: String?,
    ): OAuthCredentials {
        require(challenge is LoginChallenge.DeviceCode) { "Meta uses the device flow" }
        val (_, deviceCode) = splitChallenge(challenge.userCode)
        val dcaToken = pollForDcaToken(challenge, deviceCode)
        val minted = mintApiKey(dcaToken)

        return OAuthCredentials(
            // The API key is the credential used for normal API requests. Quota deliberately
            // ignores this field and reads providerData["dca_token"] instead.
            accessToken = minted.apiKey,
            refreshToken = null,
            idToken = null,
            expiresAt = null,
            providerData = buildMap {
                put(DCA_TOKEN_KEY, dcaToken)
                put(API_KEY_KEY, minted.apiKey)
            },
        )
    }

    /**
     * Meta has no refresh-token grant, but a DCA-backed API key can be minted again on demand.
     *
     * The sync engine reaches this method after a 401 for a key whose expiry is not advertised.
     * A manually imported key has no DCA token to mint from, so it is handed back unchanged and
     * the original request error remains the useful diagnosis.
     */
    override suspend fun refresh(credentials: OAuthCredentials): OAuthCredentials {
        if (credentials.accessToken.isBlank()) {
            throw ProviderException.Unauthorized("Meta credentials have no API key")
        }
        val dcaToken = credentials.providerData[DCA_TOKEN_KEY]
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.none { character -> character.isWhitespace() } }
            ?: return credentials
        val minted = mintApiKey(dcaToken)
        return credentials.copy(
            accessToken = minted.apiKey,
            providerData = credentials.providerData + (API_KEY_KEY to minted.apiKey),
        )
    }

    override suspend fun fetchProfile(credentials: OAuthCredentials): ProviderProfile {
        val payload = fetchDcaPayload(credentials)
        val email = JsonSupport.string(
            payload,
            "user_email",
            "userEmail",
            "user_email_address",
            "email",
        )
        val name = JsonSupport.string(
            payload,
            "user_full_name",
            "userFullName",
            "display_name",
            "displayName",
            "name",
        )
        val accountId = JsonSupport.string(
            payload,
            "user_id",
            "userId",
            "account_id",
            "accountId",
            "subject",
            "sub",
        ) ?: email ?: stableIdentity(requireDcaToken(credentials))

        return ProviderProfile(
            externalAccountId = accountId,
            email = email,
            displayName = name,
            plan = MetaQuotaParser.parsePlan(payload),
        )
    }

    override suspend fun fetchUsage(
        account: ProviderAccount,
        credentials: OAuthCredentials,
    ): UsageResult {
        val payload = fetchDcaPayload(credentials)
        return UsageResult(windows = MetaQuotaParser.parse(payload, nowMs()))
    }

    /** Polls RFC 8628 until the user approves the device code or a terminal error arrives. */
    private suspend fun pollForDcaToken(
        challenge: LoginChallenge.DeviceCode,
        deviceCode: String,
    ): String {
        var intervalMs = challenge.pollIntervalMs.coerceAtLeast(0L)
        while (nowMs() < challenge.expiresAt) {
            val payload = try {
                val response = http.request(
                    url = Meta.TOKEN_ENDPOINT,
                    method = "POST",
                    headers = oauthHeaders(),
                    body = HttpClient.formBody(
                        mapOf(
                            "grant_type" to Meta.DEVICE_CODE_GRANT_TYPE,
                            "device_code" to deviceCode,
                            "client_id" to Meta.CLIENT_ID,
                        ),
                    ),
                    retries = 0,
                    devicePoll = true,
                )
                JsonSupport.parseObject(response.body).also { body ->
                    if (response.statusCode !in 200..299 && JsonSupport.string(body, "error") == null) {
                        throw ProviderException.MalformedPayload("Meta device error had no error code")
                    }
                }
            } catch (_: ProviderException.Offline) {
                delay(intervalMs)
                continue
            } catch (_: ProviderException.ServerError) {
                delay(intervalMs)
                continue
            } catch (_: ProviderException.RateLimited) {
                intervalMs = increasePollInterval(intervalMs)
                delay(intervalMs)
                continue
            }

            when (val error = JsonSupport.string(payload, "error")) {
                null -> {
                    return JsonSupport.string(payload, "access_token", "accessToken")
                        ?.takeIf { it.isNotBlank() }
                        ?: throw ProviderException.MalformedPayload(
                            "Meta token response had no access_token",
                        )
                }

                ERROR_AUTHORIZATION_PENDING -> delay(intervalMs)

                ERROR_SLOW_DOWN -> {
                    intervalMs = increasePollInterval(intervalMs)
                    delay(intervalMs)
                }

                ERROR_EXPIRED_TOKEN, ERROR_ACCESS_DENIED ->
                    throw ProviderException.LoginCancelled(
                        "Meta device login was not completed ($error)",
                    )

                else -> throw ProviderException.Unexpected("Meta device authorization was refused")
            }
        }
        throw ProviderException.LoginCancelled("Meta device login expired before it was approved")
    }

    /** Mints the API key once, immediately after the DCA token is issued. */
    private suspend fun mintApiKey(dcaToken: String): MintedApiKey {
        val body = JsonSupport.json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("dca_token", dcaToken) },
        )
        val response = http.request(
            url = Meta.KEY_ENDPOINT,
            method = "POST",
            headers = dcaHeaders(dcaToken),
            body = HttpClient.jsonBody(body),
        )
        val payload = JsonSupport.parseObject(response.body)
        val apiKey = JsonSupport.string(payload, "api_key", "apiKey", "key")
            ?: throw ProviderException.MalformedPayload("Meta key response had no api_key")
        return MintedApiKey(apiKey)
    }

    /** Profile and quota both use the DCA bearer and an empty JSON object. */
    private suspend fun fetchDcaPayload(credentials: OAuthCredentials): JsonObject {
        val dcaToken = requireDcaToken(credentials)
        val response = http.request(
            url = Meta.KEY_ENDPOINT,
            method = "POST",
            headers = dcaHeaders(dcaToken),
            body = HttpClient.jsonBody("{}"),
        )
        return JsonSupport.parseObject(response.body)
    }

    private fun requireDcaToken(credentials: OAuthCredentials): String =
        credentials.providerData[DCA_TOKEN_KEY]
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.none { character -> character.isWhitespace() } }
            ?: throw ProviderException.Unauthorized("Meta credentials have no DCA token")

    private fun oauthHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to Meta.USER_AGENT,
    )

    private fun dcaHeaders(dcaToken: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $dcaToken",
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "User-Agent" to Meta.USER_AGENT,
        "x-api-version" to Meta.API_VERSION,
    )

    private fun validateVerificationUri(raw: String): String {
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: throw ProviderException.Unexpected("Meta verification URI is invalid")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw ProviderException.Unexpected("Meta verification URI must use HTTPS")
        }
        val host = uri.host?.lowercase()
            ?: throw ProviderException.Unexpected("Meta verification URI has no host")
        if (host != AUTH_HOST && !host.endsWith(".$AUTH_HOST")) {
            throw ProviderException.Unexpected("Meta verification URI is not an auth.meta.com endpoint")
        }
        return raw
    }

    private fun splitChallenge(value: String): Pair<String, String> {
        val parts = value.split(CODE_SEPARATOR, limit = 2)
        require(parts.size == 2 && parts[1].isNotBlank()) { "malformed Meta device challenge" }
        return parts[0] to parts[1]
    }

    private fun increasePollInterval(currentMs: Long): Long =
        if (currentMs >= MAX_POLL_INTERVAL_MS - SLOW_DOWN_STEP_MS) MAX_POLL_INTERVAL_MS
        else currentMs + SLOW_DOWN_STEP_MS

    private fun stableIdentity(dcaToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(dcaToken.toByteArray())
        return "dca-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private data class MintedApiKey(val apiKey: String)

    companion object {
        private const val AUTH_HOST = "auth.meta.com"
        private const val CODE_SEPARATOR = "|"
        private const val MIN_POLL_SECONDS = 5L
        private const val DEFAULT_EXPIRES_SECONDS = 15L * 60
        private const val SLOW_DOWN_STEP_MS = 5_000L
        private const val MAX_POLL_INTERVAL_MS = 5L * 60 * 1000

        private const val ERROR_AUTHORIZATION_PENDING = "authorization_pending"
        private const val ERROR_SLOW_DOWN = "slow_down"
        private const val ERROR_EXPIRED_TOKEN = "expired_token"
        private const val ERROR_ACCESS_DENIED = "access_denied"

        const val DCA_TOKEN_KEY = "dca_token"
        const val API_KEY_KEY = "api_key"

        /** The user-visible half of the packed challenge value. */
        fun displayCode(packed: String): String = packed.substringBefore(CODE_SEPARATOR)
    }
}
