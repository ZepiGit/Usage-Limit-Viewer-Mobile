package com.usagelimits.providers.xai

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.ProviderException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Synthetic endpoint responses; no network or live account is used. */
class XaiProviderUsageTest {
    private fun provider(billingCode: Int, billingBody: String = "{}", creditsCode: Int = 200) = XaiProvider(
        HttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            val credits = chain.request().url.queryParameter("format") == "credits"
            val code = if (credits) creditsCode else billingCode
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("synthetic").apply { if (code == 429) header("Retry-After", "3600") }
                .body((if (credits) """{"creditUsagePercent":20,"usagePeriodType":"weekly"}""" else billingBody)
                    .toResponseBody(HttpClient.JSON_MEDIA_TYPE)).build()
        }.build()), nowMs = { 1L },
    )
    private val account = ProviderAccount("local", ProviderId.XAI, "synthetic", null, null, null, "synthetic", 0L, null)
    private val credentials = OAuthCredentials(accessToken = "synthetic", refreshToken = null, idToken = null, expiresAt = null)

    @Test fun `failed optional billing fetch preserves weekly measurement and reports partial failure`() = runBlocking {
        val result = provider(403).fetchUsage(account, credentials)
        assertEquals(SnapshotStatus.PARTIAL, result.status)
        assertEquals(80.0, result.windows.single().remainingPercent!!, 0.0)
        assertNotNull(result.errorMessage)
    }

    @Test fun `a throttled billing endpoint keeps usage from the other endpoint`() = runTest {
        val result = provider(429).fetchUsage(account, credentials)

        assertEquals(SnapshotStatus.PARTIAL, result.status)
        assertEquals(80.0, result.windows.single().remainingPercent!!, 0.0)
    }

    @Test fun `both views fail and the throttled view determines the retry delay`() = runTest {
        val error = runCatching { provider(404, creditsCode = 429).fetchUsage(account, credentials) }
            .exceptionOrNull()

        assertEquals(3_600_000L, (error as? ProviderException.RateLimited)?.retryAfterMs)
    }

    @Test fun `one authentication rejection remains visible without claiming the other view needs reconnecting`() = runBlocking {
        val result = provider(401).fetchUsage(account, credentials)
        assertEquals(SnapshotStatus.PARTIAL, result.status)
        assertEquals(ConnectionStatus.UNKNOWN, result.connectionStatus)
        assertTrue(result.errorMessage!!.contains("authentication"))
    }

    @Test fun `unknown optional monthly spend is successful and distinct from malformed spend`() = runBlocking {
        val unknown = provider(200, """{"monthlyLimit":1000}""").fetchUsage(account, credentials)
        assertEquals(SnapshotStatus.OK, unknown.status)
        assertTrue(unknown.windows.none { it.id == "xai-monthly" })
        assertEquals(80.0, unknown.windows.single().remainingPercent!!, 0.0)
        val malformed = provider(200, """{"monthlyLimit":1000,"used":"bad"}""").fetchUsage(account, credentials)
        assertEquals(SnapshotStatus.PARTIAL, malformed.status)
        assertEquals(80.0, malformed.windows.first().remainingPercent!!, 0.0)
    }
}
