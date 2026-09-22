package com.usagelimits.providers.meta

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.ProviderEndpoints.Meta
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.LoginChallenge
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** Synthetic device-flow and DCA quota interactions; no live Meta account is contacted. */
class MetaProviderTest {

    private data class Recorded(val request: Request, val body: String)

    private val deviceJson =
        """
        {"device_code":"device-synthetic","user_code":"MUSE-ABCD",
         "verification_uri":"https://auth.meta.com/device",
         "verification_uri_complete":"https://auth.meta.com/device?code=MUSE-ABCD",
         "expires_in":900,"interval":5}
        """.trimIndent()
    private val tokenJson = """{"access_token":"dca:synthetic-token","token_type":"Bearer","expires_in":3600}"""
    private val mintedJson = """{"api_key":"muse-api-synthetic","user_email":"muse@example.test","subs_tier_name":"Muse Test"}"""
    private val quotaJson =
        """
        {"user_email":"muse@example.test","subs_tier_name":"Muse Test","subs_usage":{
          "window":{"used_percent":10,"window_duration_mins":300,"resets_at":1789678120},
          "weekly":{"used_percent":25,"resets_at":1789948800}
        }}
        """.trimIndent()

    private fun scripted(
        vararg replies: Pair<Int, String>,
        seen: MutableList<Recorded> = mutableListOf(),
    ): HttpClient = HttpClient(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
                seen += Recorded(request, body)
                val (code, responseBody) = replies.getOrElse(seen.size - 1) { replies.last() }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("synthetic")
                    .body(responseBody.toResponseBody(HttpClient.JSON_MEDIA_TYPE))
                    .build()
            }
            .build(),
    )

    private fun form(body: String): Map<String, String> = body.split('&')
        .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun `device flow mints a key while retaining dca token separately`() = runBlocking {
        val seen = mutableListOf<Recorded>()
        val provider = MetaProvider(
            scripted(
                200 to deviceJson,
                400 to "{\"error\":\"authorization_pending\"}",
                200 to tokenJson,
                200 to mintedJson,
                seen = seen,
            ),
            nowMs = { 1_000_000L },
        )

        val challenge = provider.beginLogin() as LoginChallenge.DeviceCode
        assertEquals("MUSE-ABCD", MetaProvider.displayCode(challenge.userCode))
        assertEquals("https://auth.meta.com/device?code=MUSE-ABCD", challenge.verificationUriComplete)
        assertEquals(1_000_000L + 900_000L, challenge.expiresAt)
        val credentials = provider.completeLogin(challenge.copy(pollIntervalMs = 0), null)

        assertEquals("muse-api-synthetic", credentials.accessToken)
        assertEquals("dca:synthetic-token", credentials.providerData[MetaProvider.DCA_TOKEN_KEY])
        assertEquals("muse-api-synthetic", credentials.providerData[MetaProvider.API_KEY_KEY])
        assertTrue(credentials.toString().contains("accessToken=***"))
        assertFalse(credentials.toString().contains("synthetic-token"))
        assertFalse(credentials.toString().contains("muse-api-synthetic"))

        val device = form(seen[0].body)
        assertEquals(Meta.CLIENT_ID, device["client_id"])
        assertEquals("application/x-www-form-urlencoded", seen[0].request.header("Content-Type"))
        assertEquals(Meta.USER_AGENT, seen[0].request.header("User-Agent"))

        val poll = form(seen[1].body)
        assertEquals(Meta.DEVICE_CODE_GRANT_TYPE, poll["grant_type"])
        assertEquals("device-synthetic", poll["device_code"])
        assertEquals(Meta.CLIENT_ID, poll["client_id"])

        val mint = seen[3]
        assertEquals("Bearer dca:synthetic-token", mint.request.header("Authorization"))
        assertEquals(Meta.API_VERSION, mint.request.header("x-api-version"))
        assertEquals("{\"dca_token\":\"dca:synthetic-token\"}", mint.body)
    }

    @Test
    fun `profile and quota calls use dca token and empty body, never api key`() = runBlocking {
        val seen = mutableListOf<Recorded>()
        val provider = MetaProvider(
            scripted(200 to quotaJson, 200 to quotaJson, seen = seen),
        )
        val credentials = OAuthCredentials(
            accessToken = "muse-api-synthetic",
            refreshToken = null,
            idToken = null,
            expiresAt = null,
            providerData = mapOf(
                MetaProvider.DCA_TOKEN_KEY to "dca:synthetic-token",
                MetaProvider.API_KEY_KEY to "muse-api-synthetic",
            ),
        )

        val profile = provider.fetchProfile(credentials)
        val usage = provider.fetchUsage(
            ProviderAccount(
                localId = "local",
                provider = ProviderId.META,
                externalAccountId = profile.externalAccountId,
                email = profile.email,
                displayName = profile.displayName,
                plan = profile.plan,
                credentialReference = "meta_local",
                createdAt = 0,
                lastSuccessfulSync = null,
            ),
            credentials,
        )

        assertEquals("muse@example.test", profile.email)
        assertEquals("Muse Test", profile.plan)
        assertEquals(2, usage.windows.size)
        assertEquals("Bearer dca:synthetic-token", seen[0].request.header("Authorization"))
        assertEquals("Bearer dca:synthetic-token", seen[1].request.header("Authorization"))
        assertEquals("{}", seen[0].body)
        assertEquals("{}", seen[1].body)
        assertEquals(Meta.API_VERSION, seen[0].request.header("x-api-version"))
        assertFalse(seen.any { it.request.header("Authorization") == "Bearer muse-api-synthetic" })
    }

    @Test
    fun `missing dca token is rejected instead of falling back to api key`() = runBlocking {
        val provider = MetaProvider(scripted(200 to quotaJson))
        val credentials = OAuthCredentials(
            accessToken = "muse-api-synthetic",
            refreshToken = null,
            idToken = null,
            expiresAt = null,
        )

        val failure = runCatching { provider.fetchUsage(syntheticAccount(), credentials) }
            .exceptionOrNull()

        assertTrue(failure is ProviderException.Unauthorized)
    }

    @Test
    fun `refresh preserves the separated tokens`() = runBlocking {
        val seen = mutableListOf<Recorded>()
        val provider = MetaProvider(scripted(200 to mintedJson, seen = seen))
        val credentials = OAuthCredentials(
            accessToken = "muse-api-synthetic",
            refreshToken = null,
            idToken = null,
            expiresAt = null,
            providerData = mapOf(MetaProvider.DCA_TOKEN_KEY to "dca:synthetic-token"),
        )

        val refreshed = provider.refresh(credentials)
        assertEquals("muse-api-synthetic", refreshed.accessToken)
        assertEquals("dca:synthetic-token", refreshed.providerData[MetaProvider.DCA_TOKEN_KEY])
        assertEquals("muse-api-synthetic", refreshed.providerData[MetaProvider.API_KEY_KEY])
        assertEquals("Bearer dca:synthetic-token", seen.single().request.header("Authorization"))
        assertEquals("{\"dca_token\":\"dca:synthetic-token\"}", seen.single().body)
    }

    private fun syntheticAccount() = ProviderAccount(
        localId = "local",
        provider = ProviderId.META,
        externalAccountId = "synthetic",
        email = null,
        displayName = null,
        plan = null,
        credentialReference = "meta_local",
        createdAt = 0,
        lastSuccessfulSync = null,
    )
}
