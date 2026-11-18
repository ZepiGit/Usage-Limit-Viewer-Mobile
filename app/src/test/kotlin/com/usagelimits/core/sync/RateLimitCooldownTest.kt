package com.usagelimits.core.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.credits.MemoryCredentialStore
import com.usagelimits.core.database.CreditRepository
import com.usagelimits.core.database.RoomTransactionRunner
import com.usagelimits.core.database.UsageLimitsDatabase
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.model.ConnectionStatus
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.network.ProviderEndpoints.Antigravity
import com.usagelimits.core.network.ProviderEndpoints.Codex
import com.usagelimits.core.network.ProviderEndpoints.Xai
import com.usagelimits.providers.ProviderRegistry
import com.usagelimits.providers.antigravity.AntigravityProvider
import com.usagelimits.providers.credits.CreditRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RateLimitCooldownTest {
    private data class Reply(val code: Int, val body: String = "{}", val retryAfter: String? = null)

    private val start = 1_700_000_000_000L
    private val xaiTokenEndpoint = "${Xai.ISSUER}/oauth/token"
    private val xaiCredits = Reply(200, """{"creditUsagePercent":20,"usagePeriodType":"weekly"}""")
    private var now = start
    private var code = 200
    private var retryAfter: String? = null
    private var requests = 0
    private var xaiRenewals = 0
    private var replies = emptyMap<String, Reply>()
    private val credentials = MemoryCredentialStore()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val databaseName = "rate-limit-cooldown-test.db"
    private lateinit var database: UsageLimitsDatabase
    private lateinit var usage: UsageRepository
    private lateinit var credit: CreditRepository
    private lateinit var usageSync: SyncEngine
    private lateinit var creditSync: CreditSyncEngine

    private val http = HttpClient(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests++
        val url = request.url.toString()
        if (url == xaiTokenEndpoint) xaiRenewals++
        val reply = replies[url] ?: when {
            code != 200 -> Reply(code, retryAfter = retryAfter)
            request.url.host.contains("novita") -> Reply(200, """{"availableBalance":"250000"}""")
            else -> Reply(200, """{"usage":{"rolling":{"status":"ok","percent":42}}}""")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.code).message("scripted")
            .body(reply.body.toResponseBody(HttpClient.JSON_MEDIA_TYPE))
            .apply { reply.retryAfter?.let { header("Retry-After", it) } }
            .build()
    }.build())

    @Before fun setUp() {
        context.deleteDatabase(databaseName)
        openDatabase()
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, UsageLimitsDatabase::class.java, databaseName).build()
        val transactions = RoomTransactionRunner(database)
        usage = UsageRepository(database.accountDao(), database.usageSnapshotDao(), transactions = transactions)
        credit = CreditRepository(database.creditDao(), transactions = transactions)
        usageSync = SyncEngine(usage, credentials, ProviderRegistry(http)) { now }
        creditSync = CreditSyncEngine(credit, credentials, CreditRegistry(http)) { now }
    }

    private suspend fun addUsage(
        externalId: String = "synthetic-usage",
        provider: ProviderId = ProviderId.OPENCODE,
        attributes: Map<String, String> = emptyMap(),
    ): String {
        val account = usage.upsertFromLogin(provider, externalId, null, null, null, attributes)
        val oauth = if (provider == ProviderId.XAI) {
            OAuthCredentials(externalId, "synthetic-refresh", null, null, tokenEndpoint = xaiTokenEndpoint)
        } else {
            OAuthCredentials(externalId, null, null, null)
        }
        credentials.save(account.credentialReference, oauth)
        return account.localId
    }

    private suspend fun addCredit() {
        credit.upsert(CreditAccount("credit", CreditProviderId.NOVITA, "Novita", "credit-key", createdAt = now))
        credentials.save("credit-key", OAuthCredentials("synthetic-credit", null, null, null))
    }

    private fun rateLimit(seconds: String? = "3600") {
        code = 429
        retryAfter = seconds
    }

    @Test fun `automatic usage pass does not read within the hour after the final 429`() = runTest {
        addUsage()
        rateLimit()
        usageSync.syncAll(automatic = true)
        val attempts = requests

        code = 200
        now = start + 3_599_999
        val outcomes = usageSync.syncAll(automatic = true)

        assertEquals(attempts to emptyList<SyncOutcome>(), requests to outcomes)
    }

    @Test fun `xAI keeps the partial reading and skips the five minute pass after a 429 without a header`() = runTest {
        val id = addUsage(provider = ProviderId.XAI)
        replies = mapOf(Xai.BILLING_CREDITS_URL to xaiCredits, Xai.BILLING_URL to Reply(429))

        assertTrue(usageSync.syncAll(automatic = true).single().success)
        val reading = checkNotNull(usage.snapshot(id))
        assertEquals(SnapshotStatus.PARTIAL, reading.status)
        assertEquals(80.0, reading.windows.single().remainingPercent!!, 0.0)

        database.close()
        openDatabase()
        // Same clock and no-header scenario as the shared partial-reading vector.
        now = start + 300_000
        val attempts = requests
        val outcomes = usageSync.syncAll(automatic = true)
        assertEquals(attempts to emptyList<SyncOutcome>(), requests to outcomes)
        assertEquals(reading, usage.snapshot(id))
        assertEquals(start + 900_000, usage.backgroundNotBefore(id))
    }

    @Test fun `Codex keeps its OK reading and honors the reset credit hour hint even after a manual read`() = runTest {
        val id = addUsage(provider = ProviderId.CODEX)
        replies = mapOf(
            Codex.USAGE_URL to Reply(200, """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":40}},
                "rate_limit_reset_credits":{"available_count":2,"applicable_available_count":1}}"""),
            Codex.RESET_CREDITS_URL to Reply(429, retryAfter = "3600"),
        )

        assertTrue(usageSync.syncAll(automatic = true).single().success)
        val reading = checkNotNull(usage.snapshot(id))
        assertEquals(SnapshotStatus.OK, reading.status)
        assertEquals(60.0, reading.windows.single().remainingPercent!!, 0.0)
        assertEquals(2, reading.resetCreditCount)

        now = start + 900_000
        val attempts = requests
        val outcomes = usageSync.syncAll(automatic = true)
        assertEquals(attempts to emptyList<SyncOutcome>(), requests to outcomes)
        assertEquals(reading, usage.snapshot(id))
        assertEquals(start + 3_600_000, usage.backgroundNotBefore(id))

        assertTrue(usageSync.syncAccount(id).success)
        assertEquals(SnapshotStatus.OK, usage.snapshot(id)?.status)
        assertEquals(now + 3_600_000, usage.backgroundNotBefore(id))
        now = start + 3_600_000
        val attemptsAfterManualRead = requests
        assertTrue(usageSync.syncAll(automatic = true).isEmpty())
        assertEquals(attemptsAfterManualRead, requests)

        replies = replies + (Codex.RESET_CREDITS_URL to Reply(200, """{"available_count":2}"""))
        assertTrue(usageSync.syncAccount(id).success)
        assertTrue(usageSync.syncAll(automatic = true).single().success)
    }

    @Test fun `mixed xAI 401 and 429 fails without renewing and only fresh rejections count after the pause`() = runTest {
        val id = addUsage(provider = ProviderId.XAI)
        replies = mapOf(Xai.BILLING_CREDITS_URL to xaiCredits, Xai.BILLING_URL to Reply(200))
        assertTrue(usageSync.syncAll(automatic = true).single().success)
        val reading = checkNotNull(usage.snapshot(id))

        now = start + 60_000
        replies = mapOf(
            Xai.BILLING_CREDITS_URL to Reply(401),
            Xai.BILLING_URL to Reply(429),
            xaiTokenEndpoint to Reply(200, """{"access_token":"synthetic-renewed"}"""),
        )
        assertEquals(false, usageSync.syncAll(automatic = true).single().success)
        assertEquals("the 429 must prevent immediate token renewal", 0, xaiRenewals)
        val failed = checkNotNull(usage.snapshot(id))
        assertEquals(SnapshotStatus.FAILED, failed.status)
        assertEquals(reading.connectionStatus, failed.connectionStatus)
        assertEquals(reading.fetchedAt, failed.fetchedAt)
        assertEquals(reading.windows, failed.windows)

        now = start + 360_000
        val attempts = requests
        val outcomes = usageSync.syncAll(automatic = true)
        assertEquals(attempts to emptyList<SyncOutcome>(), requests to outcomes)
        assertEquals(failed, usage.snapshot(id))
        assertEquals(start + 960_000, usage.backgroundNotBefore(id))

        now = start + 960_000
        replies = replies + mapOf(Xai.BILLING_CREDITS_URL to xaiCredits, Xai.BILLING_URL to Reply(200))
        assertTrue(usageSync.syncAll(automatic = true).single().success)
        assertEquals(0, xaiRenewals)

        replies = replies + mapOf(Xai.BILLING_CREDITS_URL to Reply(401), Xai.BILLING_URL to Reply(401))
        assertEquals(false, usageSync.syncAll(automatic = true).single().success)
        assertEquals(1, xaiRenewals)
        assertEquals(ConnectionStatus.RECONNECT_REQUIRED, usage.snapshot(id)?.connectionStatus)
    }

    @Test fun `Antigravity keeps the host union and pauses on the longest hint`() = runTest {
        val id = addUsage(provider = ProviderId.ANTIGRAVITY,
            attributes = mapOf(AntigravityProvider.ATTR_PROJECT_ID to "synthetic-project"))
        val (first, second, third) = Antigravity.QUOTA_URLS
        replies = mapOf(
            first to Reply(200, """{"groups":[{"displayName":"Gemini Models","buckets":[
                {"bucketId":"gemini-weekly","window":"weekly","remainingFraction":0.5}]}]}"""),
            second to Reply(429, retryAfter = "1200"),
            third to Reply(429, retryAfter = "3600"),
        )

        assertTrue(usageSync.syncAll(automatic = true).single().success)
        val reading = checkNotNull(usage.snapshot(id))
        assertEquals(SnapshotStatus.OK, reading.status)
        assertEquals(50.0, reading.windows.single().remainingPercent!!, 0.0)

        now = start + 1_800_000
        val attempts = requests
        val outcomes = usageSync.syncAll(automatic = true)
        assertEquals(attempts to emptyList<SyncOutcome>(), requests to outcomes)
        assertEquals(reading, usage.snapshot(id))
        assertEquals(start + 3_600_000, usage.backgroundNotBefore(id))
    }

    @Test fun `another account at the same usage provider is still read automatically`() = runTest {
        addUsage()
        rateLimit()
        usageSync.syncAll(automatic = true)
        val other = addUsage(externalId = "second-account")
        code = 200

        assertEquals(listOf(SyncOutcome(other, success = true)), usageSync.syncAll(automatic = true))
    }

    @Test fun `automatic credit pass keeps the hourly deadline across a database reopen`() = runTest {
        addCredit()
        rateLimit()
        creditSync.syncAll(automatic = true)
        val attempts = requests

        database.close()
        openDatabase()
        code = 200
        now = start + 3_599_999
        val outcomes = creditSync.syncAll(automatic = true)

        assertEquals(attempts to emptyList<SyncOutcome>(), requests to outcomes)
    }

    @Test fun `usage resumes exactly at the retry after deadline`() = runTest {
        addUsage()
        rateLimit()
        usageSync.syncAll()

        code = 200
        now = start + 3_600_000

        assertTrue(usageSync.syncAll(automatic = true).single().success)
    }

    @Test fun `automatic credit pass waits fifteen minutes when the header is absent`() = runTest {
        addCredit()
        rateLimit(seconds = null)
        creditSync.syncAll()

        code = 200
        now = start + 899_999

        assertTrue("missing Retry-After uses the 15-minute floor", creditSync.syncAll(automatic = true).isEmpty())
    }

    @Test fun `a manual usage sync bypasses the cooldown`() = runTest {
        val id = addUsage()
        rateLimit()
        usageSync.syncAll(automatic = true)

        code = 200

        assertTrue("manual usage sync is eligible during cooldown", usageSync.syncAccount(id).success)
    }

    @Test fun `successful manual credit sync clears the cooldown`() = runTest {
        addCredit()
        rateLimit()
        creditSync.syncAll(automatic = true)

        code = 200
        creditSync.syncAll()

        assertEquals("the account is eligible for its next automatic sync",
            listOf(SyncOutcome("credit", success = true)), creditSync.syncAll(automatic = true))
    }

    @Test fun `a different manual usage failure does not clear the cooldown`() = runTest {
        val id = addUsage()
        rateLimit()
        usageSync.syncAll(automatic = true)

        code = 403
        usageSync.syncAccount(id)
        code = 200

        assertTrue(usageSync.syncAll(automatic = true).isEmpty())
    }

    @Test fun `removing a credit account removes its deadline even when its id is reused`() = runTest {
        addCredit()
        rateLimit()
        creditSync.syncAll(automatic = true)

        credit.remove("credit")
        addCredit()
        code = 200

        assertEquals("the account is eligible for its next automatic sync",
            listOf(SyncOutcome("credit", success = true)), creditSync.syncAll(automatic = true))
    }

    @Test fun `a late credit 429 cannot put a re-added account into cooldown`() = runTest {
        addCredit()
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val held429 = HttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            requestStarted.complete(Unit)
            runBlocking { releaseResponse.await() }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("scripted")
                .header("Retry-After", "3600").body("{}".toResponseBody(HttpClient.JSON_MEDIA_TYPE)).build()
        }.build())
        val oldSync = CreditSyncEngine(credit, credentials, CreditRegistry(held429)) { now }
        val running = async { oldSync.syncAll(automatic = true) }
        try {
            requestStarted.await()
            credit.remove("credit")
            now++
            addCredit()
        } finally {
            releaseResponse.complete(Unit)
        }
        running.await()

        assertEquals("the re-added account is eligible", listOf(SyncOutcome("credit", success = true)),
            creditSync.syncAll(automatic = true))
    }

    @Test fun `a skipped usage account keeps its last reading and attempted time`() = runTest {
        val id = addUsage()
        usageSync.syncAll()
        now = start + 60_000
        rateLimit()
        usageSync.syncAll(automatic = true)
        val lastReading = checkNotNull(usage.snapshot(id))

        now = start + 360_000
        code = 200
        usageSync.syncAll(automatic = true)

        assertEquals(lastReading, usage.snapshot(id))
    }
}
