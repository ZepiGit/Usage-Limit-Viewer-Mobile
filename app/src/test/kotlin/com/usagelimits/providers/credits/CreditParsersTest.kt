package com.usagelimits.providers.credits

import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditSeverity
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.core.model.Severity
import com.usagelimits.core.network.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The balance payloads, with the quirks recorded in docs/credits.md. The same cases as the iOS
 * CreditsTests, with the same expected values. Amounts are compared with compareTo: BigDecimal
 * equality is scale-sensitive and 0.30 is not "equal" to 0.3.
 */
class CreditParsersTest {

    private fun json(text: String) = JsonSupport.parseObject(text)
    private fun dec(text: String) = BigDecimal(text)

    private fun assertAmount(expected: String, actual: BigDecimal?) =
        assertTrue("expected $expected but was $actual", actual != null && dec(expected).compareTo(actual) == 0)

    // MARK: Decimal handling

    @Test fun `money is exact and strings are strict`() {
        val tenth = CreditJson.decimal(json("""{"a":0.1}"""), "a")!!
        val fifth = CreditJson.decimal(json("""{"a":0.2}"""), "a")!!
        assertAmount("0.3", tenth + fifth)
        assertAmount("49.58894", CreditJson.decimal(json("""{"a":49.58894}"""), "a"))
        assertAmount("0.00001", CreditJson.decimal(json("""{"a":1e-05}"""), "a"))
        assertNull(CreditJson.decimal(json("""{"a":"12abc"}"""), "a"))
        assertNull(CreditJson.decimal(json("""{"a":""}"""), "a"))
        assertNull(CreditJson.decimal(json("""{"a":true}"""), "a"))
        assertNull(CreditJson.decimal(json("""{"a":null}"""), "a"))
        assertAmount("-3.5", CreditJson.decimal(json("""{"a":" -3.50 "}"""), "a"))
    }

    // MARK: OpenRouter

    @Test fun `openrouter credits is purchased minus used`() {
        val balances = OpenRouterCreditParser.parseCredits(json("""{"data":{"total_credits":100.5,"total_usage":25.75}}"""))
        assertEquals(1, balances.size)
        assertEquals("USD", balances[0].currency)
        assertAmount("74.75", balances[0].amount)
        assertAmount("100.5", balances[0].total)
        assertThrows(CreditError.Malformed::class.java) {
            OpenRouterCreditParser.parseCredits(json("""{"data":{"total_credits":1}}"""))
        }
    }

    @Test fun `openrouter key prefers the effective budget`() {
        val body = """
            {"data":{"label":"k","limit":50,"limit_remaining":40,"usage_monthly":3,
             "effective_budget":{"limit_usd":20,"spend_usd":5,"remaining_usd":15,"reset_interval":"monthly"}}}
        """
        val balance = OpenRouterCreditParser.parseKey(json(body))[0]
        assertAmount("15", balance.amount)
        assertAmount("20", balance.total)
        assertEquals("Key limit", balance.label)
        assertFalse(balance.unlimited)
    }

    @Test fun `openrouter key falls back to limit remaining`() {
        val balance = OpenRouterCreditParser.parseKey(
            json("""{"data":{"limit":50,"limit_remaining":40.25,"effective_budget":null}}"""),
        )[0]
        assertAmount("40.25", balance.amount)
        assertAmount("50", balance.total)
    }

    @Test fun `openrouter key with no limit is unlimited not zero`() {
        val balance = OpenRouterCreditParser.parseKey(
            json("""{"data":{"limit":null,"limit_remaining":null,"usage_monthly":12.5,"effective_budget":null}}"""),
        )[0]
        assertTrue(balance.unlimited)
        assertEquals("No limit", balance.label)
        assertAmount("12.5", balance.amount)
        assertEquals(Severity.HEALTHY, CreditSeverity.of(balance, BigDecimal(5)))
    }

    @Test fun `openrouter key with a limit but no remainder is malformed not guessed`() {
        assertThrows(CreditError.Malformed::class.java) {
            OpenRouterCreditParser.parseKey(json("""{"data":{"limit":10}}"""))
        }
    }

    @Test fun `openrouter key missing fields are malformed not unlimited`() {
        for (body in listOf(
            """{}""", """{"data":{}}""", """{"data":null}""",
            """{"data":{"usage_monthly":3}}""", // no `limit` at all
            """{"data":{"limit":null}}""", // unlimited, but no spend to show
            """{"data":{"limit":null,"usage_monthly":"abc"}}""",
        )) {
            assertThrows(body, CreditError.Malformed::class.java) { OpenRouterCreditParser.parseKey(json(body)) }
        }
        // An explicit null limit with a spend is the genuine unlimited key, even at $0.
        val balance = OpenRouterCreditParser.parseKey(json("""{"data":{"limit":null,"usage_monthly":0}}"""))[0]
        assertTrue(balance.unlimited)
        assertAmount("0", balance.amount)
    }

    @Test fun `openrouter credits missing totals are malformed`() {
        for (body in listOf("""{}""", """{"data":{}}""", """{"data":{"total_usage":1}}""", """{"data":{"total_credits":1}}""")) {
            assertThrows(body, CreditError.Malformed::class.java) { OpenRouterCreditParser.parseCredits(json(body)) }
        }
    }

    // MARK: DeepSeek

    @Test fun `deepseek gives one balance per currency`() {
        val body = """
            {"is_available":true,"balance_infos":[
              {"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"},
              {"currency":"USD","total_balance":"2.50","granted_balance":"0.00","topped_up_balance":"2.50"}]}
        """
        val balances = DeepSeekCreditParser.parse(json(body))
        assertEquals(listOf("CNY", "USD"), balances.map { it.currency })
        assertAmount("110", balances[0].amount)
        assertAmount("2.5", balances[1].amount)
    }

    @Test fun `deepseek unavailable reads as empty`() {
        val balances = DeepSeekCreditParser.parse(
            json("""{"is_available":false,"balance_infos":[{"currency":"CNY","total_balance":"3.00"}]}"""),
        )
        assertAmount("0", balances[0].amount)
        assertEquals("Unavailable", balances[0].label)
        assertEquals(Severity.EXHAUSTED, CreditSeverity.of(balances[0], BigDecimal(35)))
        val empty = DeepSeekCreditParser.parse(json("""{"is_available":false,"balance_infos":[]}"""))
        assertEquals("CNY", empty[0].currency)
        assertAmount("0", empty[0].amount)
        assertThrows(CreditError.Malformed::class.java) {
            DeepSeekCreditParser.parse(json("""{"is_available":true,"balance_infos":[]}"""))
        }
    }

    // MARK: Moonshot

    @Test fun `moonshot shows available even when cash is negative`() {
        val body = """{"code":0,"data":{"available_balance":49.58894,"voucher_balance":50.58894,"cash_balance":-1.0},"scode":"0x0","status":true}"""
        val balance = MoonshotCreditParser.parse(json(body), ProviderRegion.INTL)[0]
        assertEquals("USD", balance.currency)
        assertAmount("49.58894", balance.amount)
        assertEquals("CNY", MoonshotCreditParser.parse(json(body), ProviderRegion.CN)[0].currency)
        assertThrows(CreditError.Malformed::class.java) {
            MoonshotCreditParser.parse(json("""{"code":0,"data":{}}"""), ProviderRegion.INTL)
        }
    }

    // MARK: xAI

    @Test fun `xai balance sign is inverted and in cents`() {
        assertAmount("10", XaiCreditParser.parseBalance(json("""{"changes":[],"total":{"val":"-1000"}}"""))[0].amount)
        assertAmount("23.17", XaiCreditParser.parseBalance(json("""{"total":{"val":"-2317"}}"""))[0].amount)
        // Spent past the purchase: arrears, shown negative rather than hidden.
        assertAmount("-2.5", XaiCreditParser.parseBalance(json("""{"total":{"val":"250"}}"""))[0].amount)
        assertAmount("0", XaiCreditParser.parseBalance(json("""{"total":{"val":"0"}}"""))[0].amount)
    }

    @Test fun `xai missing total is an error never zero`() {
        for (body in listOf("{}", """{"total":{}}""", """{"total":{"val":"abc"}}""", """{"total":null}""")) {
            assertThrows(body, CreditError.Malformed::class.java) { XaiCreditParser.parseBalance(json(body)) }
        }
    }

    @Test fun `xai team comes from a team scoped key only`() {
        assertEquals("team-9", XaiCreditParser.parseTeamId(json("""{"scope":"SCOPE_TEAM","scopeId":"team-9"}""")))
        assertNull(XaiCreditParser.parseTeamId(json("""{"scope":"SCOPE_ORGANIZATION","scopeId":"org-1"}""")))
        assertNull(XaiCreditParser.parseTeamId(json("""{"scope":"SCOPE_TEAM"}""")))
    }

    // MARK: fal and Novita

    @Test fun `fal reads credits and treats a missing block as an error`() {
        val balance = FalCreditParser.parse(json("""{"username":"t","credits":{"current_balance":24.5,"currency":"USD"}}"""))[0]
        assertAmount("24.5", balance.amount)
        assertEquals("EUR", FalCreditParser.parse(json("""{"credits":{"current_balance":"7.25","currency":"eur"}}"""))[0].currency)
        assertThrows(CreditError.Unavailable::class.java) { FalCreditParser.parse(json("""{"username":"t"}""")) }
        assertThrows(CreditError.Unavailable::class.java) { FalCreditParser.parse(json("""{"credits":{"currency":"USD"}}""")) }
    }

    @Test fun `novita strings are ten thousandths of a dollar`() {
        assertAmount("100", NovitaCreditParser.parse(json("""{"availableBalance":"1000000","cashBalance":"800000"}"""))[0].amount)
        assertAmount("0.0005", NovitaCreditParser.parse(json("""{"availableBalance":"5"}"""))[0].amount)
        assertAmount("-2.5", NovitaCreditParser.parse(json("""{"availableBalance":"-25000"}"""))[0].amount)
        assertThrows(CreditError.Malformed::class.java) { NovitaCreditParser.parse(json("""{"availableBalance":"lots"}""")) }
        assertThrows(CreditError.Malformed::class.java) { NovitaCreditParser.parse(json("{}")) }
    }

    @Test fun `errors never carry a payload`() {
        // Built from endpoint names and statuses only.
        assertTrue(CreditError.Rejected(401).message!!.contains("HTTP 401"))
        assertEquals(
            "This xAI key is not tied to one team. Enter the team id from console.x.ai.",
            CreditError.TeamIdRequired().message,
        )
        assertEquals(CreditBalance("USD", BigDecimal.ONE).unlimited, false)
    }
}
