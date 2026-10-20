package com.usagelimits.providers.credits

import com.usagelimits.core.network.ProviderEndpoints.Credits
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.providers.RecordedRequest
import com.usagelimits.providers.scriptedHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/** The clients against a scripted transport: which URL, which header, which status means what. */
class CreditClientsTest {

    private fun assertAmount(expected: String, actual: BigDecimal) =
        assertTrue("expected $expected but was $actual", BigDecimal(expected).compareTo(actual) == 0)

    private inline fun <reified E : CreditError> failure(block: () -> Unit): E {
        try {
            block()
        } catch (e: CreditError) {
            return e as? E ?: throw AssertionError("wrong error $e", e)
        }
        fail("expected a failure")
        throw AssertionError()
    }

    @Test fun `openrouter falls back to key only on 403`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val http = scriptedHttp(
            403 to """{"error":"Only management keys"}""",
            200 to """{"data":{"limit":10,"limit_remaining":4}}""",
            seen = seen,
        )
        val balances = OpenRouterCreditClient(http).fetchBalances("sk-test", emptyMap())
        assertAmount("4", balances[0].amount)
        assertEquals(
            listOf("https://openrouter.ai/api/v1/credits", "https://openrouter.ai/api/v1/key"),
            seen.map { it.request.url.toString() },
        )
        assertEquals("Bearer sk-test", seen[0].request.header("Authorization"))

        // A bad key (401) is a bad key: no second request.
        val rejectedSeen = mutableListOf<RecordedRequest>()
        val rejected = failure<CreditError.Rejected> {
            runBlocking { OpenRouterCreditClient(scriptedHttp(401 to "{}", seen = rejectedSeen)).fetchBalances("x", emptyMap()) }
        }
        assertEquals(401, rejected.status)
        assertEquals(1, rejectedSeen.size)
    }

    @Test fun `moonshot host follows the region`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val client = MoonshotCreditClient(scriptedHttp(200 to """{"data":{"available_balance":1}}""", seen = seen))
        client.fetchBalances("k", mapOf("region" to "cn"))
        client.fetchBalances("k", emptyMap())
        assertEquals(
            listOf("https://api.moonshot.cn/v1/users/me/balance", "https://api.moonshot.ai/v1/users/me/balance"),
            seen.map { it.request.url.toString() },
        )
        assertEquals(Credits.moonshotBalance(ProviderRegion.INTL), seen[1].request.url.toString())
    }

    @Test fun `xai resolves the team then reads the ledger`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val http = scriptedHttp(
            200 to """{"scope":"SCOPE_TEAM","scopeId":"team-9"}""",
            200 to """{"total":{"val":"-1000"}}""",
            seen = seen,
        )
        assertAmount("10", XaiCreditClient(http).fetchBalances("mk", emptyMap())[0].amount)
        assertEquals(
            listOf(
                "https://management-api.x.ai/auth/management-keys/validation",
                "https://management-api.x.ai/v1/billing/teams/team-9/prepaid/balance",
            ),
            seen.map { it.request.url.toString() },
        )

        // A stored team id skips the validation call, and is escaped as a path segment.
        val storedSeen = mutableListOf<RecordedRequest>()
        XaiCreditClient(scriptedHttp(200 to """{"total":{"val":"-500"}}""", seen = storedSeen))
            .fetchBalances("mk", mapOf("teamId" to "t/../x"))
        assertEquals(
            listOf("https://management-api.x.ai/v1/billing/teams/t%2F..%2Fx/prepaid/balance"),
            storedSeen.map { it.request.url.toString() },
        )
    }

    @Test fun `xai org key without a team asks for one and 404 is not zero`() = runBlocking {
        failure<CreditError.TeamIdRequired> {
            runBlocking {
                XaiCreditClient(scriptedHttp(200 to """{"scope":"SCOPE_ORGANIZATION","scopeId":"o"}"""))
                    .fetchBalances("mk", emptyMap())
            }
        }
        failure<CreditError.NotFound> {
            runBlocking { XaiCreditClient(scriptedHttp(404 to "{}")).fetchBalances("mk", mapOf("teamId" to "t")) }
        }
        Unit
    }

    @Test fun `fal sends the Key prefix and expand`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        FalCreditClient(scriptedHttp(200 to """{"credits":{"current_balance":1,"currency":"USD"}}""", seen = seen))
            .fetchBalances("fk", emptyMap())
        assertEquals("Key fk", seen[0].request.header("Authorization"))
        assertEquals("https://api.fal.ai/v1/account/billing?expand=credits", seen[0].request.url.toString())
    }

    @Test fun `statuses map to errors that never carry the key`() {
        val key = "super-secret-key"
        val rejected = failure<CreditError.Rejected> {
            runBlocking { DeepSeekCreditClient(scriptedHttp(403 to "{}")).fetchBalances(key, emptyMap()) }
        }
        assertEquals(403, rejected.status)
        val other = failure<CreditError.Unavailable> {
            runBlocking { NovitaCreditClient(scriptedHttp(400 to "{}")).fetchBalances(key, emptyMap()) }
        }
        assertEquals("No balance could be read (novita balance answered HTTP 400).", other.message)
        val notJson = failure<CreditError.Malformed> {
            runBlocking { NovitaCreditClient(scriptedHttp(200 to "[1]")).fetchBalances(key, emptyMap()) }
        }
        for (e in listOf(rejected, other, notJson)) assertTrue(!e.message!!.contains(key))
    }

    @Test fun `endpoints are the documented ones`() {
        assertEquals("https://api.novita.ai/openapi/v1/billing/balance/detail", Credits.NOVITA_BALANCE)
        assertEquals("https://api.deepseek.com/user/balance", Credits.DEEPSEEK_BALANCE)
    }
}
