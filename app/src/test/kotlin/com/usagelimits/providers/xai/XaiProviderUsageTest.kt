package com.usagelimits.providers.xai

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.network.HttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Synthetic endpoint responses; no network or live account is used. */
class XaiProviderUsageTest {
    private fun provider(billingCode: Int, billingBody: String = "{}") = XaiProvider(
        HttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            val credits = chain.request().url.queryParameter("format") == "credits"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (credits) 200 else billingCode).message("synthetic")
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

    @Test fun `one authentication rejection remains visible without claiming the other view needs reconnecting`() = runBlocking {
        val result = provider(401).fetchUsage(account, credentials)
        assertEquals(SnapshotStatus.PARTIAL, result.status)
        assertEquals(ConnectionStatus.UNKNOWN, result.connectionStatus)
        assertTrue(result.errorMessage!!.contains("authentication"))
    }

    @Test fun `unknown optional monthly spend is successful and distinct from malformed spend`() = runBlocking {
        val unknown = provider(200, """{"monthlyLimit":1000}""").fetchUsage(account, credentials)
        assertEquals(SnapshotStatus.OK, unknown.status)
        assertNull(unknown.windows.single { it.id == "xai-monthly" }.remainingPercent)
        val malformed = provider(200, """{"monthlyLimit":1000,"used":"bad"}""").fetchUsage(account, credentials)
        assertEquals(SnapshotStatus.PARTIAL, malformed.status)
        assertEquals(80.0, malformed.windows.first().remainingPercent!!, 0.0)
    }
}
