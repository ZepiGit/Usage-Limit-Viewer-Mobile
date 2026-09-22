package com.usagelimits.providers.devin

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.core.network.ProviderEndpoints.Devin
import com.usagelimits.providers.LoginChallenge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder

/** Synthetic Devin responses and tokens only; no network or real credentials are used. */
@RunWith(RobolectricTestRunner::class)
class DevinProviderTest {

    private data class Recorded(val request: Request, val body: String)

    private val tokenJson = """{"token":"eyJsynth-devin-token"}"""
    private val profileJson = """
        {
          "user_id": "user-synthetic-42",
          "user_name": "Synthetic Devin",
          "email": "synthetic@example.test",
          "org_id": "org-synthetic",
          "plan": "Team"
        }
    """.trimIndent()
    private val statusJson = """
        {
          "userStatus": {
            "planStatus": {
              "planInfo": { "planName": "Team" },
              "dailyQuotaRemainingPercent": 61,
              "weeklyQuotaRemainingPercent": 84,
              "dailyQuotaResetAtUnix": "1789372800",
              "weeklyQuotaResetAtUnix": 1789891200
            }
          }
        }
    """.trimIndent()

    private fun httpFor(
        seen: MutableList<Recorded> = mutableListOf(),
        responder: (Request) -> Pair<Int, String> = { request ->
            when (request.url.encodedPath) {
                Devin.TOKEN_PATH -> 200 to tokenJson
                Devin.PROFILE_PATH -> 200 to profileJson
                Devin.STATUS_PATH -> 200 to statusJson
                else -> 404 to "{}"
            }
        },
    ): HttpClient = HttpClient(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
                seen += Recorded(request, body)
                val (code, json) = responder(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("synthetic")
                    .body(json.toResponseBody(HttpClient.JSON_MEDIA_TYPE))
                    .build()
            }
            .build(),
    )

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.orEmpty().split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            }

    private fun send(port: Int, requestLine: String) {
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.getOutputStream().write("$requestLine\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            runCatching { socket.getInputStream().readBytes() }
        }
    }

    private fun syntheticAccount() = ProviderAccount(
        localId = "local-devin",
        provider = ProviderId.DEVIN,
        externalAccountId = "user-synthetic-42",
        email = "synthetic@example.test",
        displayName = "Synthetic Devin",
        plan = "Team",
        credentialReference = "credential-devin",
        createdAt = 0,
        lastSuccessfulSync = null,
    )

    @Test(timeout = 30_000)
    fun `begin and complete use dynamic loopback PKCE and exchange one code`() = runBlocking {
        val seen = mutableListOf<Recorded>()
        val provider = DevinProvider(httpFor(seen))
        val challenge = provider.beginLogin()
        assertTrue(challenge is LoginChallenge.Redirect)
        challenge as LoginChallenge.Redirect

        val params = query(challenge.authorizationUrl)
        val redirect = URI(challenge.redirectUri)
        assertEquals("127.0.0.1", redirect.host)
        assertTrue(redirect.port > 0)
        assertEquals(challenge.redirectUri, params["redirect_uri"])
        assertEquals("select_account", params["prompt"])
        assertEquals("S256", params["code_challenge_method"])
        assertFalse(params["state"].isNullOrBlank())
        assertFalse(params["code_challenge"].isNullOrBlank())

        val completing = async(Dispatchers.IO) { provider.completeLogin(challenge) }
        withContext(Dispatchers.IO) {
            send(
                redirect.port,
                "GET /callback?code=synthetic-code&state=${params.getValue("state")} HTTP/1.1",
            )
        }
        val credentials = completing.await()

        assertEquals(Devin.SESSION_TOKEN_PREFIX + "eyJsynth-devin-token", credentials.accessToken)
        assertNull(credentials.refreshToken)
        assertNull(credentials.expiresAt)

        val exchange = seen.single()
        assertEquals(Devin.TOKEN_PATH, exchange.request.url.encodedPath)
        assertEquals("application/json", exchange.request.header("Content-Type"))
        val exchangeBody = JsonSupport.parseObject(exchange.body)
        assertEquals("synthetic-code", JsonSupport.string(exchangeBody, "code"))
        assertTrue(JsonSupport.string(exchangeBody, "code_verifier")!!.isNotBlank())

        // The listener is closed after the one response, so the OS can immediately reuse it.
        assertTrue(
            runCatching {
                ServerSocket(redirect.port, 1, InetAddress.getByName("127.0.0.1")).use { }
            }.isSuccess,
        )
    }

    @Test(timeout = 30_000)
    fun `unrelated loopback callback is ignored until matching state arrives`() = runBlocking {
        val seen = mutableListOf<Recorded>()
        val provider = DevinProvider(httpFor(seen))
        val challenge = provider.beginLogin() as LoginChallenge.Redirect
        val params = query(challenge.authorizationUrl)
        val redirect = URI(challenge.redirectUri)

        val completing = async(Dispatchers.IO) { provider.completeLogin(challenge) }
        withContext(Dispatchers.IO) {
            send(redirect.port, "GET /callback?code=wrong&state=wrong-state HTTP/1.1")
            send(
                redirect.port,
                "GET /callback?code=real-code&state=${params.getValue("state")} HTTP/1.1",
            )
        }
        assertEquals(
            Devin.SESSION_TOKEN_PREFIX + "eyJsynth-devin-token",
            completing.await().accessToken,
        )
        assertEquals("real callback only", "real-code", JsonSupport.string(
            JsonSupport.parseObject(seen.single().body), "code",
        ))
    }

    @Test
    fun `status call uses CPAMC JSON metadata and Basic compatibility header`() = runBlocking {
        val seen = mutableListOf<Recorded>()
        val provider = DevinProvider(httpFor(seen))
        val credentials = OAuthCredentials(
            accessToken = "devin-session-token\$synthetic",
            refreshToken = null,
            idToken = null,
            expiresAt = null,
        )

        val result = provider.fetchUsage(syntheticAccount(), credentials)

        assertEquals(listOf("devin-daily", "devin-weekly"), result.windows.map { it.id })
        assertEquals(39.0, result.windows[0].usedPercent!!, 0.0001)
        assertEquals(16.0, result.windows[1].usedPercent!!, 0.0001)

        val status = seen.single { it.request.url.encodedPath == Devin.STATUS_PATH }
        assertEquals("application/json", status.request.header("Content-Type"))
        assertEquals("application/json", status.request.header("Accept"))
        assertEquals("1", status.request.header("Connect-Protocol-Version"))
        assertEquals(
            "Basic devin-session-token\$synthetic-devin-session-token\$synthetic",
            status.request.header("Authorization"),
        )
        val metadata = JsonSupport.obj(JsonSupport.parseObject(status.body), "metadata")!!
        assertEquals("chisel", JsonSupport.string(metadata, "ideName"))
        assertEquals(Devin.IDE_VERSION, JsonSupport.string(metadata, "ideVersion"))
        assertEquals("devin-session-token\$synthetic", JsonSupport.string(metadata, "apiKey"))
        assertEquals("darwin", JsonSupport.string(metadata, "os"))
    }

    @Test
    fun `profile maps stable identity and plan fields`() = runBlocking {
        val provider = DevinProvider(httpFor())
        val credentials = OAuthCredentials("synthetic-session", null, null, null)

        val profile = provider.fetchProfile(credentials)

        assertEquals("user-synthetic-42", profile.externalAccountId)
        assertEquals("synthetic@example.test", profile.email)
        assertEquals("Synthetic Devin", profile.displayName)
        assertEquals("Team", profile.plan)
        assertEquals("user-synthetic-42", profile.attributes[DevinProvider.ATTR_USER_ID])
        assertEquals("org-synthetic", profile.attributes[DevinProvider.ATTR_ORG_ID])
    }

    @Test
    fun `refresh keeps permanent session token without a network call`() = runBlocking {
        val seen = mutableListOf<Recorded>()
        val provider = DevinProvider(httpFor(seen))
        val credentials = OAuthCredentials("synthetic-session", null, null, null)

        assertEquals(credentials, provider.refresh(credentials))
        assertTrue(seen.isEmpty())
    }
}
