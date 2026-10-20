package com.usagelimits.core.credits

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.database.CreditRepository
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditStatus
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.sync.CreditSyncEngine
import com.usagelimits.providers.credits.CreditRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** The sync: per-account isolation, stale-over-empty, and keys that never reach a message. */
class CreditSyncEngineTest {

    private val now = 1_757_000_000_000L
    private val dao = FakeCreditDao()
    private val repository = CreditRepository(dao)
    private val store = MemoryCredentialStore()
    private var clock = now

    /** Answers by host so two providers can behave differently in one pass. */
    private fun registry(vararg byHost: Pair<String, Pair<Int, String>>) = CreditRegistry(
        HttpClient(
            OkHttpClient.Builder().addInterceptor { chain ->
                val host = chain.request().url.host
                val (code, body) = byHost.first { host.contains(it.first) }.second
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                    .message("scripted").body(body.toResponseBody(HttpClient.JSON_MEDIA_TYPE)).build()
            }.build(),
        ),
    )

    private suspend fun connect(id: String, provider: CreditProviderId, key: String? = "key-$id") {
        repository.upsert(CreditAccount(id, provider, id, "credit:$id", createdAt = now))
        if (key != null) store.save("credit:$id", OAuthCredentials(key, null, null, null))
    }

    private fun engine(registry: CreditRegistry) = CreditSyncEngine(repository, store, registry) { clock }

    @Test fun `one bad key costs one account and nothing else`() = runBlocking {
        connect("good", CreditProviderId.NOVITA)
        connect("bad", CreditProviderId.DEEPSEEK)
        connect("nokey", CreditProviderId.FAL, key = null)
        val outcomes = engine(
            registry(
                "novita" to (200 to """{"availableBalance":"250000"}"""),
                "deepseek" to (401 to "{}"),
                "fal" to (200 to """{"credits":{"current_balance":1}}"""),
            ),
        ).syncAll()

        assertEquals(mapOf("good" to true, "bad" to false, "nokey" to false), outcomes.associate { it.accountId to it.success })
        val good = repository.snapshot("good")!!
        assertEquals(CreditStatus.OK, good.status)
        assertEquals(0, BigDecimal(25).compareTo(good.balances[0].amount))
        assertEquals(CreditStatus.FAILED, repository.snapshot("bad")!!.status)
        assertTrue(repository.snapshot("bad")!!.error!!.contains("rejected"))
        assertEquals("No balance could be read (no key is stored for this account).", repository.snapshot("nokey")!!.error)
    }

    @Test fun `a failure after a success keeps the last balance`() = runBlocking {
        connect("n", CreditProviderId.NOVITA)
        engine(registry("novita" to (200 to """{"availableBalance":"250000"}"""))).syncAll()
        clock = now + 900_000
        val outcome = engine(registry("novita" to (401 to "{}"))).syncAll().single()

        assertFalse(outcome.success)
        val snapshot = repository.snapshot("n")!!
        assertEquals(CreditStatus.FAILED, snapshot.status)
        assertEquals(0, BigDecimal(25).compareTo(snapshot.balances[0].amount))
        assertEquals(now, snapshot.fetchedAt)
        assertEquals(now + 900_000, snapshot.lastAttemptAt)
    }

    @Test fun `no credit accounts is an empty pass`() = runBlocking {
        assertTrue(engine(registry("x" to (200 to "{}"))).syncAll().isEmpty())
        assertNull(repository.snapshot("x"))
    }

    @Test fun `the key never reaches the stored error`() = runBlocking {
        connect("n", CreditProviderId.NOVITA, key = "sk-very-secret")
        engine(registry("novita" to (400 to """{"echo":"sk-very-secret"}"""))).syncAll()
        assertFalse(repository.snapshot("n")!!.error!!.contains("sk-very-secret"))
    }
}
