package com.usagelimits.providers.codex

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.network.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Synthetic endpoint responses; no network or live account is used. */
class CodexProviderUsageTest {
    private val usageWithoutCredits =
        """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":40}}}"""

    private fun provider(
        usageBody: String = usageWithoutCredits,
        credits: () -> Pair<Int, String>,
    ) = CodexProvider(
        HttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            val creditsCall = chain.request().url.encodedPath.contains("reset-credits")
            val (code, body) = if (creditsCall) credits() else 200 to usageBody
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("synthetic")
                .body(body.toResponseBody(HttpClient.JSON_MEDIA_TYPE)).build()
        }.build()),
        nowMs = { 0L },
    )

    private val account = ProviderAccount("local", ProviderId.CODEX, "synthetic", null, null, null, "synthetic", 0L, null)
    private val credentials = OAuthCredentials(accessToken = "synthetic", refreshToken = null, idToken = null, expiresAt = null)

    @Test fun `a failed optional credit call without an embedded copy leaves the count unknown`() = runBlocking {
        for (code in listOf(401, 403, 404)) {
            val result = provider { code to "{}" }.fetchUsage(account, credentials)
            assertNull("HTTP $code must not become a confirmed zero", result.resetCreditCount)
            assertEquals(SnapshotStatus.PARTIAL, result.status)
            assertNotNull(result.errorMessage)
            // The usage windows themselves still refreshed.
            assertEquals(60.0, result.windows.single().remainingPercent!!, 0.0)
        }
    }

    @Test fun `a malformed credit response without an embedded copy leaves the count unknown`() = runBlocking {
        val result = provider { 200 to "not json" }.fetchUsage(account, credentials)
        assertNull(result.resetCreditCount)
        assertEquals(SnapshotStatus.PARTIAL, result.status)
    }

    @Test fun `a failed credit call falls back to the embedded count`() = runBlocking {
        val usage = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":40}},
            "rate_limit_reset_credits":{"available_count":2,"applicable_available_count":1}}"""
        val result = provider(usage) { 403 to "{}" }.fetchUsage(account, credentials)
        assertEquals(2, result.resetCreditCount)
        assertEquals(1, result.applicableResetCreditCount)
        assertEquals(SnapshotStatus.OK, result.status)
    }

    @Test fun `a measured zero from the credit endpoint stays zero`() = runBlocking {
        val result = provider { 200 to """{"credits":[],"available_count":0}""" }.fetchUsage(account, credentials)
        assertEquals(0, result.resetCreditCount)
        assertEquals(SnapshotStatus.OK, result.status)
        assertNull(result.errorMessage)
    }

    @Test fun `a listed credit array without a count is counted by its available rows`() = runBlocking {
        val body = """{"credits":[
            {"id":"a","status":"available","reset_type":"codex_rate_limits"},
            {"id":"b","status":"consumed","reset_type":"codex_rate_limits"}]}"""
        val result = provider { 200 to body }.fetchUsage(account, credentials)
        assertEquals(1, result.resetCreditCount)
    }

    @Test fun `a credit response with neither count nor list leaves the count unknown`() = runBlocking {
        val result = provider { 200 to "{}" }.fetchUsage(account, credentials)
        assertNull(result.resetCreditCount)
        // The endpoint answered; nothing failed.
        assertEquals(SnapshotStatus.OK, result.status)
    }

    @Test fun `cancellation of the optional credit call is not turned into a fallback`() = runBlocking {
        try {
            provider { throw CancellationException("cancelled by caller") }.fetchUsage(account, credentials)
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertTrue(e.message!!.contains("cancelled"))
        }
    }
}
