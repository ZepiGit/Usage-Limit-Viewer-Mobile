package com.usagelimits.providers.devin

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Devin
import com.usagelimits.core.network.ProviderException
import com.usagelimits.core.oauth.LoopbackServer
import com.usagelimits.core.oauth.Pkce
import com.usagelimits.core.oauth.PkceCodes
import com.usagelimits.providers.LoginChallenge
import com.usagelimits.providers.ProviderProfile
import com.usagelimits.providers.UsageProvider
import com.usagelimits.providers.UsageResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Devin / Cognition subscription quota.
 *
 * Devin uses the same browser PKCE flow as its CLI, but accepts a dynamic loopback callback.
 * The callback listener binds an ephemeral port before the authorization URL is opened, so a
 * phone does not have to reserve a globally known port and an unrelated local process cannot
 * steal the redirect. Quota is read through the JSON Connect-RPC shape used by CPAMC's quota
 * viewer; the token is put in the request's metadata and also sent in the Basic header expected
 * by the first-party seat-management client.
 */
class DevinProvider(
    private val http: HttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : UsageProvider {

    override val providerId = ProviderId.DEVIN

    /** Devin session tokens do not expire or expose a refresh grant. */
    override suspend fun refresh(credentials: OAuthCredentials): OAuthCredentials {
        if (credentials.accessToken.isBlank()) {
            throw ProviderException.Unauthorized("No Devin session token stored")
        }
        return credentials
    }

    /** The browser flow is available on every Android device with a web browser. */
    override val isLoginAvailable: Boolean get() = true

    // PKCE/state/listener are process-local by design. They never enter Room or the credential
    // store, and are cleared immediately after the one redirect is handled.
    @Volatile
    private var server: LoopbackServer? = null

    @Volatile
    private var pendingPkce: PkceCodes? = null

    @Volatile
    private var pendingState: String? = null

    @Volatile
    private var pendingRedirectUri: String? = null

    override suspend fun beginLogin(): LoginChallenge {
        // A previous attempt may have been cancelled while its cleanup was still unwinding.
        // Closing it here is safe: completeLogin only clears fields when they still refer to the
        // same listener, so a retry cannot have its PKCE pair wiped by the old attempt.
        server?.close()

        val boundServer = LoopbackServer(0)
        try {
            boundServer.start()
            val port = boundServer.localPort
            if (port <= 0) throw ProviderException.Unexpected("Devin callback listener got no port")

            val codes = Pkce.generate()
            val state = Pkce.generateState()
            val redirectUri = "http://127.0.0.1:$port$CALLBACK_PATH"

            // Publish the listener before publishing the matching PKCE fields. If an earlier
            // completeLogin is unwinding at this exact point, its identity check will now see a
            // different listener and leave this attempt's fields alone. Publishing the fields
            // first created a narrow retry race where the old finally block cleared the new
            // verifier just before beginLogin returned its challenge.
            server = boundServer
            pendingPkce = codes
            pendingState = state
            pendingRedirectUri = redirectUri

            return LoginChallenge.Redirect(
                authorizationUrl = authorizationUrl(
                    redirectUri = redirectUri,
                    codeChallenge = codes.codeChallenge,
                    state = state,
                ),
                redirectUri = redirectUri,
            )
        } catch (e: CancellationException) {
            boundServer.close()
            if (server === boundServer) {
                server = null
                pendingPkce = null
                pendingState = null
                pendingRedirectUri = null
            }
            throw e
        } catch (e: ProviderException) {
            boundServer.close()
            if (server === boundServer) {
                server = null
                pendingPkce = null
                pendingState = null
                pendingRedirectUri = null
            }
            throw e
        } catch (e: Exception) {
            boundServer.close()
            if (server === boundServer) {
                server = null
                pendingPkce = null
                pendingState = null
                pendingRedirectUri = null
            }
            throw ProviderException.Unexpected("Could not start Devin login", e)
        }
    }

    private fun authorizationUrl(
        redirectUri: String,
        codeChallenge: String,
        state: String,
    ): String {
        // Keep this order aligned with CLIProxyAPI's BuildAuthorizationURL. Devin's endpoint
        // does not require the order, but preserving the first-party shape makes diagnostics
        // and synthetic request tests easier to compare.
        val params = linkedMapOf(
            "redirect_uri" to redirectUri,
            "state" to state,
            "prompt" to "select_account",
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
        )
        return params.entries.joinToString(
            "&",
            prefix = "${Devin.APP_BASE_URL}${Devin.AUTHORIZE_PATH}?",
        ) { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }

    override suspend fun completeLogin(
        challenge: LoginChallenge,
        userInput: String?,
    ): OAuthCredentials {
        require(challenge is LoginChallenge.Redirect) { "Devin uses the redirect flow" }

        val codes = pendingPkce
        val expectedState = pendingState
        val expectedRedirect = pendingRedirectUri
        val listener = server
        if (codes == null || expectedState == null || expectedRedirect == null || listener == null) {
            throw ProviderException.Unexpected("Login was not started on this provider instance")
        }
        if (challenge.redirectUri != expectedRedirect) {
            throw ProviderException.Unexpected("Devin redirect does not match the active login")
        }

        val response = try {
            // Reject unrelated loopback requests at the listener. A callback with a different
            // state gets a 400 response and the browser's real callback can still arrive.
            listener.awaitRedirect(REDIRECT_TIMEOUT_MS) { callback ->
                callback.state?.let { Pkce.constantTimeEquals(expectedState, it) } == true
            }
        } finally {
            listener.close()
            // Only this attempt's fields may be cleared. A retry can already have installed a
            // new listener and verifier by the time a cancelled attempt reaches this finally.
            if (server === listener) {
                server = null
                pendingPkce = null
                pendingState = null
                pendingRedirectUri = null
            }
        }

        response.error?.let { error ->
            throw ProviderException.LoginCancelled(response.errorDescription ?: error)
        }
        val returnedState = response.state
            ?: throw ProviderException.LoginCancelled("Redirect carried no state")
        if (!Pkce.constantTimeEquals(expectedState, returnedState)) {
            throw ProviderException.Unexpected("Devin redirect state did not match the request")
        }
        val code = response.code
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ProviderException.LoginCancelled("No Devin authorization code was returned")

        return exchangeCode(code, codes.codeVerifier)
    }

    /** Exchanges one browser authorization code for Devin's permanent session token. */
    internal suspend fun exchangeCode(code: String, codeVerifier: String): OAuthCredentials {
        val response = http.request(
            url = "${Devin.API_BASE_URL}${Devin.TOKEN_PATH}",
            method = "POST",
            headers = jsonHeaders(),
            body = HttpClient.jsonBody(
                JsonSupport.json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("code", code)
                        put("code_verifier", codeVerifier)
                    },
                ),
            ),
            // Devin authorization codes are one-time grants. Do not replay a request whose
            // delivery is unknown after the server may already have consumed the code.
            oneTimeGrant = true,
        )
        val rawToken = JsonSupport.string(
            responsePayload(response.body),
            "token",
            "session_token",
            "sessionToken",
            "access_token",
            "accessToken",
        )
            ?: throw ProviderException.MalformedPayload("Devin token response had no token")
        val sessionToken = formatSessionToken(rawToken)
        return OAuthCredentials(
            accessToken = sessionToken,
            refreshToken = null,
            idToken = null,
            expiresAt = null,
        )
    }

    override suspend fun fetchProfile(credentials: OAuthCredentials): ProviderProfile {
        val response = http.request(
            url = "${Devin.API_BASE_URL}${Devin.PROFILE_PATH}",
            headers = mapOf(
                "Authorization" to "Bearer ${credentials.accessToken}",
                "Accept" to "application/json",
            ),
        )
        val payload = responsePayload(response.body)

        val userId = JsonSupport.string(payload, "user_id", "userId", "id")
        val userName = JsonSupport.string(
            payload,
            "user_name",
            "userName",
            "username",
            "display_name",
            "displayName",
            "name",
        )
        val email = JsonSupport.string(payload, "email", "user_email", "userEmail")
        val orgId = JsonSupport.string(payload, "org_id", "orgId", "organization_id", "organizationId")
        val accountId = userId ?: userName ?: email ?: stableAccountId(credentials.accessToken)
        // `/v3/self` is the stable identity endpoint but normally omits the subscription tier.
        // Ask the same status endpoint used by sync only as a fallback, so a newly added Devin
        // account can show its plan immediately while identity remains available if quota is
        // temporarily unavailable.
        val directPlan = DevinQuotaParser.parsePlan(payload)
            ?: JsonSupport.string(payload, "plan", "plan_name", "planName")
        val plan = directPlan ?: run {
            val statusPayload = try {
                fetchStatusPayload(credentials.accessToken)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ProviderException) {
                null
            }
            statusPayload?.let(DevinQuotaParser::parsePlan)
        }

        val attributes = buildMap {
            userId?.let { put(ATTR_USER_ID, it) }
            orgId?.let { put(ATTR_ORG_ID, it) }
        }

        return ProviderProfile(
            externalAccountId = accountId,
            email = email,
            displayName = userName,
            plan = plan,
            attributes = attributes,
        )
    }

    override suspend fun fetchUsage(
        account: ProviderAccount,
        credentials: OAuthCredentials,
    ): UsageResult {
        val token = credentials.accessToken.trim()
        if (token.isEmpty() || token.any { it.isWhitespace() }) {
            throw ProviderException.Unauthorized("No valid Devin session token stored")
        }

        val payload = fetchStatusPayload(token)
        val windows = DevinQuotaParser.parse(payload, nowMs())
        if (windows.isEmpty()) {
            throw ProviderException.MalformedPayload("Devin response had no quota windows")
        }
        return UsageResult(windows = windows)
    }

    private suspend fun fetchStatusPayload(token: String): JsonObject {
        val response = http.request(
            url = "${Devin.SERVER_BASE_URL}${Devin.STATUS_PATH}",
            method = "POST",
            headers = statusHeaders(token),
            body = HttpClient.jsonBody(statusBody(token)),
        )
        return responsePayload(response.body)
    }

    private fun statusBody(token: String): String =
        JsonSupport.json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("metadata", buildJsonObject {
                    // This is CPAMC's JSON Connect-RPC metadata shape. CPAMC replaces its
                    // `$TOKEN$` marker before sending; the mobile client has the token already,
                    // so it performs that substitution locally and keeps the wire shape intact.
                    put("ideName", Devin.IDE_NAME)
                    put("ideVersion", Devin.IDE_VERSION)
                    put("apiKey", token)
                    put("locale", Devin.LOCALE)
                    // CPAMC's request is the compatibility reference. Devin accepts this value
                    // even when the caller is the Android quota viewer.
                    put("os", "darwin")
                    put("extensionVersion", Devin.IDE_VERSION)
                    put("clientName", Devin.IDE_NAME)
                })
            },
        )

    private fun jsonHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
    )

    private fun statusHeaders(token: String): Map<String, String> = jsonHeaders() + mapOf(
        "Connect-Protocol-Version" to "1",
        "User-Agent" to Devin.USER_AGENT,
        // This is the header used by CLIProxyAPI's first-party Devin implementation. The
        // request body still follows CPAMC's JSON shape for the current endpoint.
        "Authorization" to "Basic $token-$token",
    )

    private fun responsePayload(raw: String): JsonObject = JsonSupport.parseObject(raw)

    private fun formatSessionToken(raw: String): String {
        val token = raw.trim()
        if (token.isEmpty()) throw ProviderException.MalformedPayload("Devin token response had an empty token")
        return when {
            token.startsWith(Devin.SESSION_TOKEN_PREFIX) -> token
            token.startsWith("eyJ") -> Devin.SESSION_TOKEN_PREFIX + token
            else -> token
        }
    }

    private fun stableAccountId(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "devin-$digest"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val ATTR_USER_ID = "user_id"
        const val ATTR_ORG_ID = "org_id"

        private const val CALLBACK_PATH = "/callback"
        private const val REDIRECT_TIMEOUT_MS = 5L * 60 * 1000
    }
}
