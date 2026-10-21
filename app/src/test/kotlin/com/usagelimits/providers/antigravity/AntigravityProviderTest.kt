package com.usagelimits.providers.antigravity

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.ProviderEndpoints.Antigravity
import com.usagelimits.core.network.ProviderException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AntigravityProviderTest {
    private val account = ProviderAccount(
        "local", ProviderId.ANTIGRAVITY, "synthetic", null, null, null, "synthetic", 0L, null,
        attributes = mapOf(AntigravityProvider.ATTR_PROJECT_ID to "synthetic-project"),
    )
    private val credentials = OAuthCredentials("synthetic", null, null, null)

    private fun group(id: String, name: String, fraction: Double = 0.5) = """
        { "displayName": "$name", "buckets": [
            { "bucketId": "$id-5h", "window": "5h", "remainingFraction": $fraction },
            { "bucketId": "$id-weekly", "window": "weekly", "remainingFraction": $fraction }
        ] }
    """

    private fun provider(replies: List<Pair<Int, String>>, requests: MutableList<String>) = AntigravityProvider(
        HttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            requests += url
            val (code, body) = replies[Antigravity.QUOTA_URLS.indexOf(url)]
            if (code == 0) throw IOException("synthetic transport failure")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("synthetic").body(body.toResponseBody(HttpClient.JSON_MEDIA_TYPE)).build()
        }.build()), nowMs = { 1L },
    )

    @Test
    fun `project and quota requests identify the supported Hub release`() = runBlocking {
        val requests = mutableListOf<String>()
        val subject = AntigravityProvider(HttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            requests += url
            val body = if (url == Antigravity.USERINFO_ENDPOINT) {
                """{"id":"synthetic"}"""
            } else {
                assertEquals(
                    "antigravity/hub/2.25.0 (aidev_client; os_type=darwin; arch=arm64)",
                    request.header("User-Agent"),
                )
                assertEquals("Bearer synthetic", request.header("Authorization"))
                assertEquals("POST", request.method)
                if (url in Antigravity.LOAD_CODE_ASSIST_URLS) {
                    """{"cloudaicompanionProject":"synthetic-project"}"""
                } else {
                    """{"groups":[${group("gemini", "Gemini Models")}]}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("synthetic").body(body.toResponseBody(HttpClient.JSON_MEDIA_TYPE)).build()
        }.build()))

        val profile = subject.fetchProfile(credentials)
        assertEquals(account.attributes, profile.attributes)
        val result = subject.fetchUsage(account, credentials)
        assertEquals(listOf(Antigravity.USERINFO_ENDPOINT, Antigravity.LOAD_CODE_ASSIST_URLS.first()) +
            Antigravity.QUOTA_URLS, requests)
        assertEquals(listOf("gemini-5h", "gemini-weekly"), result.windows.map { it.id })
    }

    @Test
    fun `all hosts contribute windows and first measurement wins for duplicate ids`() = runBlocking {
        val requests = mutableListOf<String>()
        val thirdParty = group("3p", "Claude and GPT models")
        val gemini = group("gemini", "Gemini Models")
        val result = provider(listOf(
            200 to """{"groups":[$thirdParty]}""",
            200 to """{"groups":[]}""",
            200 to """{"groups":[$gemini,${group("3p", "Claude and GPT models", 0.9)}]}""",
        ), requests).fetchUsage(account, credentials)
        assertEquals(Antigravity.QUOTA_URLS, requests)
        assertEquals(listOf("3p-5h", "3p-weekly", "gemini-5h", "gemini-weekly"), result.windows.map { it.id })
        assertEquals(50.0, result.windows.first().usedPercent!!, 0.0)
        assertEquals(SnapshotStatus.OK, result.status)
    }

    @Test
    fun `two failed hosts still allow the third host to succeed`() = runBlocking {
        val requests = mutableListOf<String>()
        val result = provider(listOf(
            403 to "{}", 401 to "{}", 200 to """{"groups":[${group("gemini", "Gemini Models")}]}""",
        ), requests).fetchUsage(account, credentials)
        assertEquals(Antigravity.QUOTA_URLS, requests)
        assertEquals(listOf("gemini-5h", "gemini-weekly"), result.windows.map { it.id })
        assertEquals(SnapshotStatus.OK, result.status)
        assertNull(result.errorMessage)
    }

    @Test
    fun `later host failures do not discard an earlier success`() = runBlocking {
        val requests = mutableListOf<String>()
        val result = provider(listOf(
            200 to """{"groups":[${group("gemini", "Gemini Models")}]}""", 403 to "{}", 401 to "{}",
        ), requests).fetchUsage(account, credentials)
        assertEquals(Antigravity.QUOTA_URLS, requests)
        assertEquals(2, result.windows.size)
        assertEquals(SnapshotStatus.OK, result.status)
        assertNull(result.errorMessage)
    }

    @Test
    fun `permanent rejections in either host order do not mark a successful union partial`() = runBlocking {
        val real = 200 to """{"groups":[${group("gemini", "Gemini Models")}]}"""
        for (code in listOf(401, 403, 404)) {
            for (replies in listOf(listOf(code to "{}", real, 200 to "{}"), listOf(real, 200 to "{}", code to "{}"))) {
                val result = provider(replies, mutableListOf()).fetchUsage(account, credentials)
                assertEquals(2, result.windows.size)
                assertEquals(SnapshotStatus.OK, result.status)
                assertNull(result.errorMessage)
            }
        }
    }

    @Test
    fun `transient failures in either host order keep a successful union healthy`() = runBlocking {
        val real = 200 to """{"groups":[${group("gemini", "Gemini Models")}]}"""
        for (code in listOf(0, 429, 503)) {
            for (replies in listOf(listOf(code to "{}", real, 404 to "{}"), listOf(real, 404 to "{}", code to "{}"))) {
                val result = provider(replies, mutableListOf()).fetchUsage(account, credentials)
                assertEquals(2, result.windows.size)
                assertEquals(SnapshotStatus.OK, result.status)
                assertNull(result.errorMessage)
            }
        }
    }

    @Test
    fun `real unknown window survives placeholder replacement in either host order`() = runBlocking {
        val placeholder = """{"groups":[{"displayName":"Gemini Models"}]}"""
        val real = """{"groups":[{"displayName":"Gemini Models","buckets":[
            {"window":"unknown","remainingFraction":0.25},
            {"window":"weekly","remainingFraction":0.5}
        ]}]}"""
        for (bodies in listOf(listOf(placeholder, real, "{}"), listOf(real, placeholder, "{}"))) {
            val result = provider(bodies.map { 200 to it }, mutableListOf()).fetchUsage(account, credentials)
            assertEquals(setOf("gemini-models-unknown", "gemini-models-weekly"), result.windows.map { it.id }.toSet())
            assertEquals(2, result.windows.size)
            assertEquals(75.0, result.windows.single { it.id == "gemini-models-unknown" }.usedPercent!!, 0.0)
        }
    }

    @Test
    fun `empty bucket hosts do not add unknown rows beside real windows`() = runBlocking {
        val empty = """{"groups":[{"displayName":"Gemini Models","buckets":[]}]}"""
        val real = """{"groups":[${group("gemini", "Gemini Models")}]}"""
        val result = provider(listOf(200 to empty, 200 to real, 200 to empty), mutableListOf())
            .fetchUsage(account, credentials)
        assertEquals(listOf("gemini-5h", "gemini-weekly"), result.windows.map { it.id })
        assertEquals(SnapshotStatus.OK, result.status)
    }

    @Test
    fun `real windows replace unknown schema rows for the same group in either host order`() = runBlocking {
        val unknown = """{"groups":[{"displayName":"Gemini Models"}]}"""
        val real = """{"groups":[${group("gemini", "Gemini Models")}]}"""
        for (replies in listOf(listOf(unknown, real, "{}"), listOf(real, unknown, "{}"))) {
            val result = provider(replies.map { 200 to it }, mutableListOf()).fetchUsage(account, credentials)
            assertEquals(listOf("gemini-5h", "gemini-weekly"), result.windows.map { it.id })
        }
    }

    @Test
    fun `secondary hosts are requested once without retries`() = runBlocking {
        val requests = mutableListOf<String>()
        val real = 200 to """{"groups":[${group("gemini", "Gemini Models")}]}"""
        provider(listOf(real, 503 to "{}", 429 to "{}"), requests).fetchUsage(account, credentials)
        assertEquals(Antigravity.QUOTA_URLS, requests)
    }

    @Test
    fun `auth rejection outranks a later server failure when all hosts fail`() = runBlocking {
        try {
            provider(listOf(401 to "{}", 503 to "{}", 404 to "{}"), mutableListOf())
                .fetchUsage(account, credentials)
            fail("expected auth rejection")
        } catch (_: ProviderException.Unauthorized) {
        }
    }

    @Test
    fun `empty union retains the first auth rejection`() = runBlocking {
        try {
            provider(listOf(403 to "{}", 200 to "{}", 401 to "{}"), mutableListOf())
                .fetchUsage(account, credentials)
            fail("expected the first auth rejection")
        } catch (_: ProviderException.Forbidden) {
        }
    }

    @Test
    fun `an empty union without auth rejections reports the first host's failure`() = runBlocking {
        try {
            provider(listOf(404 to "{}", 503 to "{}", 0 to "{}"), mutableListOf())
                .fetchUsage(account, credentials)
            fail("expected the first host's failure")
        } catch (error: ProviderException.Unexpected) {
            assertEquals(404, error.statusCode)
        }
    }

    @Test
    fun `all empty hosts retain the malformed payload error`() = runBlocking {
        try {
            provider(List(3) { 200 to """{"groups":[{"displayName":"Gemini Models","buckets":[]}]}""" }, mutableListOf())
                .fetchUsage(account, credentials)
            fail("expected no quota groups error")
        } catch (error: ProviderException.MalformedPayload) {
            assertEquals("Antigravity returned no quota groups for this project", error.message)
        }
    }
}
