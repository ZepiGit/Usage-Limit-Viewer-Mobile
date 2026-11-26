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

    @Test fun `vercel sends the bearer key and no team id unless one is stored`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val client = VercelCreditClient(scriptedHttp(200 to """{"balance":"95.50","total_used":"4.50"}""", seen = seen))
        client.fetchBalances("vck-abc", emptyMap())
        client.fetchBalances("vck-abc", mapOf("teamId" to "team/1"))
        assertEquals(
            listOf(
                "https://ai-gateway.vercel.sh/v1/credits",
                "https://ai-gateway.vercel.sh/v1/credits?teamId=team%2F1",
            ),
            seen.map { it.request.url.toString() },
        )
        assertEquals("Bearer vck-abc", seen[0].request.header("Authorization"))
    }

    @Test fun `vercel asks for a team id on 403 and never retries it`() {
        val forbidden = mutableListOf<RecordedRequest>()
        val denied = failure<CreditError.TeamDenied> {
            runBlocking { VercelCreditClient(scriptedHttp(403 to "{}", seen = forbidden)).fetchBalances("vck", emptyMap()) }
        }
        assertEquals("Vercel refused this key (HTTP 403). Enter the team id the key belongs to.", denied.message)
        assertEquals(1, forbidden.size)

        val unauthorized = mutableListOf<RecordedRequest>()
        val rejected = failure<CreditError.Rejected> {
            runBlocking { VercelCreditClient(scriptedHttp(401 to "{}", seen = unauthorized)).fetchBalances("vck", emptyMap()) }
        }
        assertEquals(401, rejected.status)
        assertEquals(1, unauthorized.size)
    }

    @Test fun `deepinfra sends the bearer key to the documented checklist url`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val client = DeepInfraCreditClient(
            scriptedHttp(
                200 to """{"checklist":{"stripe_balance":-1234,"recent":0,"limit":null,"suspend_reason":null}}""",
                seen = seen,
            ),
        )
        assertAmount("12.34", client.fetchBalances("di-test", emptyMap())[0].amount)
        assertEquals(listOf(Credits.DEEPINFRA_ME), seen.map { it.request.url.toString() })
        assertEquals("Bearer di-test", seen[0].request.header("Authorization"))
    }

    @Test fun `deepinfra names the missing scope on 403 and never retries it`() {
        val forbidden = mutableListOf<RecordedRequest>()
        val missing = failure<CreditError.MissingScope> {
            runBlocking { DeepInfraCreditClient(scriptedHttp(403 to "{}", seen = forbidden)).fetchBalances("di", emptyMap()) }
        }
        assertEquals("This key lacks the account:read scope.", missing.message)
        assertEquals(1, forbidden.size)

        val unauthorized = mutableListOf<RecordedRequest>()
        val rejected = failure<CreditError.Rejected> {
            runBlocking { DeepInfraCreditClient(scriptedHttp(401 to "{}", seen = unauthorized)).fetchBalances("di", emptyMap()) }
        }
        assertEquals(401, rejected.status)
        assertEquals(1, unauthorized.size)
    }

    @Test fun `tokenrouter sends the bearer management key to the wallet`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        val client = TokenRouterCreditClient(
            scriptedHttp(
                200 to """{"success":true,"data":{"topUpBalance":120.50,"voucherEfficientAmount":30.00,"toppedUpSpent":80.25,"voucherSpent":10.00}}""",
                seen = seen,
            ),
        )
        assertAmount("150.50", client.fetchBalances("tr-test", emptyMap())[0].amount)
        assertEquals(listOf(Credits.TOKENROUTER_WALLET), seen.map { it.request.url.toString() })
        assertEquals("Bearer tr-test", seen[0].request.header("Authorization"))
    }

    @Test fun `tokenrouter reports a rejected key on 401 and 403 and never retries`() {
        for (status in listOf(401, 403)) {
            val seen = mutableListOf<RecordedRequest>()
            val rejected = failure<CreditError.Rejected> {
                runBlocking {
                    TokenRouterCreditClient(
                        scriptedHttp(status to """{"message":"management key is invalid","success":false}""", seen = seen),
                    ).fetchBalances("tr", emptyMap())
                }
            }
            assertEquals(status, rejected.status)
            assertEquals("one attempt for HTTP $status", 1, seen.size)
        }
    }

    @Test fun `endpoints are the documented ones`() {
        assertEquals("https://api.novita.ai/openapi/v1/billing/balance/detail", Credits.NOVITA_BALANCE)
        assertEquals("https://api.deepseek.com/user/balance", Credits.DEEPSEEK_BALANCE)
        assertEquals("https://ai-gateway.vercel.sh/v1/credits", Credits.VERCEL_CREDITS)
        assertEquals("https://api.deepinfra.com/v1/me?checklist=true", Credits.DEEPINFRA_ME)
        assertEquals("https://api.tokenrouter.com/api/management/self/wallet", Credits.TOKENROUTER_WALLET)
    }
}
