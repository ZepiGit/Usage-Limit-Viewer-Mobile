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
import java.time.Instant

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

    /** A fixed clock for the payloads that place a period boundary; Wednesday 13:45 UTC. */
    private val nowMs = Instant.parse("2026-11-25T13:45:00Z").toEpochMilli()

    private fun parseKey(body: String): CreditBalance = OpenRouterCreditParser.parseKey(json(body), nowMs)[0]

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
        // iOS reads every number as text, so a string exponent must parse alike on both.
        assertAmount("100000", CreditJson.decimal(json("""{"a":"1e5"}"""), "a"))
        assertNull(CreditJson.decimal(json("""{"a":"1e"}"""), "a"))
        assertNull(CreditJson.decimal(json("""{"a":"1e9999999999"}"""), "a"))
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
        val balance = parseKey(body)
        assertAmount("15", balance.amount)
        assertAmount("20", balance.total)
        assertEquals("Key limit", balance.label)
        assertFalse(balance.unlimited)
        // A budget row names no key period, so the row keeps the plain label and no reset.
        assertNull(balance.resetsAt)
    }

    @Test fun `openrouter key falls back to limit remaining`() {
        val balance = parseKey("""{"data":{"limit":50,"limit_remaining":40.25,"effective_budget":null}}""")
        assertAmount("40.25", balance.amount)
        assertAmount("50", balance.total)
        assertEquals("Key limit", balance.label)
        assertNull(balance.resetsAt)
    }

    /** The period label and the next UTC boundary, by hand: Wednesday 13:45 UTC. */
    @Test fun `openrouter key limit names its period and next reset`() {
        val daily = parseKey("""{"data":{"limit":10,"limit_remaining":2.5,"limit_reset":"daily","effective_budget":null}}""")
        assertEquals("Daily key limit", daily.label)
        assertEquals(Instant.parse("2026-11-26T00:00:00Z").toEpochMilli(), daily.resetsAt)
        assertEquals(75.0, 100.0 - daily.remainingPercent!!, 1e-9)

        val weekly = parseKey("""{"data":{"limit":40,"limit_remaining":10,"limit_reset":"weekly","effective_budget":null}}""")
        assertEquals("Weekly key limit", weekly.label)
        assertEquals(Instant.parse("2026-11-30T00:00:00Z").toEpochMilli(), weekly.resetsAt)

        val monthly = parseKey("""{"data":{"limit":200,"limit_remaining":50,"limit_reset":"monthly","effective_budget":null}}""")
        assertEquals("Monthly key limit", monthly.label)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(), monthly.resetsAt)
    }

    @Test fun `openrouter key limit without a known reset adds none`() {
        for (reset in listOf("""null""", """absent""", """"yearly"""")) {
            val field = if (reset == "absent") "" else """"limit_reset":$reset,"""
            val balance = parseKey("""{"data":{"limit":10,"limit_remaining":5,$field"effective_budget":null}}""")
            assertEquals("Key limit", balance.label)
            assertNull(balance.resetsAt)
        }
    }

    @Test fun `openrouter key used share clamps and a zero limit draws no bar`() {
        val over = parseKey("""{"data":{"limit":10,"limit_remaining":12.5,"limit_reset":"daily","effective_budget":null}}""")
        assertEquals(0.0, 100.0 - over.remainingPercent!!, 1e-9)
        val under = parseKey("""{"data":{"limit":10,"limit_remaining":-2.5,"limit_reset":"daily","effective_budget":null}}""")
        assertEquals(100.0, 100.0 - under.remainingPercent!!, 1e-9)
        val zero = parseKey("""{"data":{"limit":0,"limit_remaining":5,"limit_reset":"daily","effective_budget":null}}""")
        assertEquals("Key limit", zero.label)
        assertNull(zero.remainingPercent)
        assertNull(zero.resetsAt)
    }

    @Test fun `openrouter key with no limit is unlimited not zero`() {
        val balance = parseKey("""{"data":{"limit":null,"limit_remaining":null,"usage_monthly":12.5,"effective_budget":null}}""")
        assertTrue(balance.unlimited)
        assertEquals("No limit", balance.label)
        assertAmount("12.5", balance.amount)
        assertEquals(Severity.HEALTHY, CreditSeverity.of(balance, BigDecimal(5)))
    }

    @Test fun `openrouter key with a limit but no remainder is malformed not guessed`() {
        assertThrows(CreditError.Malformed::class.java) {
            OpenRouterCreditParser.parseKey(json("""{"data":{"limit":10}}"""), nowMs)
        }
    }

    @Test fun `openrouter key missing fields are malformed not unlimited`() {
        for (body in listOf(
            """{}""", """{"data":{}}""", """{"data":null}""",
            """{"data":{"usage_monthly":3}}""", // no `limit` at all
            """{"data":{"limit":null}}""", // unlimited, but no spend to show
            """{"data":{"limit":null,"usage_monthly":"abc"}}""",
        )) {
            assertThrows(body, CreditError.Malformed::class.java) {
                OpenRouterCreditParser.parseKey(json(body), nowMs)
            }
        }
        // An explicit null limit with a spend is the genuine unlimited key, even at $0.
        val balance = parseKey("""{"data":{"limit":null,"usage_monthly":0}}""")
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

    @Test fun `vercel reads the balance and an optional token pass`() {
        val rows = VercelCreditParser.parse(
            json(
                """{"balance":"95.50","total_used":"4.50",
                   "token_pass":{"remaining":"12.34","resets_at":"2026-12-01T00:00:00.000Z"}}""",
            ),
        )
        assertEquals(2, rows.size)
        assertEquals("USD", rows[0].currency)
        assertAmount("95.50", rows[0].amount)
        assertNull("the balance draws no bar, the gateway states no cap", rows[0].total)
        assertNull(rows[0].resetsAt)
        assertEquals("Used: 4.50", rows[0].label)
        assertAmount("12.34", rows[1].amount)
        assertNull(rows[1].total)
        assertEquals("Token Pass", rows[1].label)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(), rows[1].resetsAt)
    }

    @Test fun `vercel without a token pass keeps one row and never invents a second zero`() {
        val nullPass = VercelCreditParser.parse(json("""{"balance":"95.50","total_used":"4.50","token_pass":null}"""))
        assertEquals(1, nullPass.size)
        assertAmount("95.50", nullPass[0].amount)
        // The docs let `remaining` be null while the pass cannot be read: no row, not a zero.
        val unreadable = VercelCreditParser.parse(
            json("""{"balance":"95.50","total_used":"4.50","token_pass":{"remaining":null,"resets_at":"2026-12-01T00:00:00Z"}}"""),
        )
        assertEquals(1, unreadable.size)
        // An exhausted balance is a real zero, and a missing total_used only drops the used figure.
        val exhausted = VercelCreditParser.parse(json("""{"balance":"0.00"}"""))
        assertAmount("0.00", exhausted[0].amount)
        assertNull(exhausted[0].label)
    }

    @Test fun `vercel refuses a figure that is not a decimal string`() {
        // A JSON number would have to go through a float, which loses the digits it was sent with.
        assertThrows(CreditError.Malformed::class.java) {
            VercelCreditParser.parse(json("""{"balance":95.5,"total_used":"4.50"}"""))
        }
        assertThrows(CreditError.Malformed::class.java) {
            VercelCreditParser.parse(
                json("""{"balance":"1.00","total_used":4.5,"token_pass":null}"""),
            )
        }
        assertThrows(CreditError.Malformed::class.java) {
            VercelCreditParser.parse(
                json("""{"balance":"1.00","token_pass":{"remaining":12.34,"resets_at":"2026-12-01T00:00:00Z"}}"""),
            )
        }
        assertThrows(CreditError.Malformed::class.java) { VercelCreditParser.parse(json("""{}""")) }
        assertThrows(CreditError.Malformed::class.java) { VercelCreditParser.parse(json("""{"balance":"12abc"}""")) }
    }

    @Test fun `vercel keeps a row when the pass names no readable reset`() {
        val rows = VercelCreditParser.parse(
            json("""{"balance":"1.00","token_pass":{"remaining":"2.00","resets_at":"soon"}}"""),
        )
        assertEquals(2, rows.size)
        assertAmount("2.00", rows[1].amount)
        assertNull(rows[1].resetsAt)
    }

    @Test fun `deepinfra negates a signed stripe balance and keeps each live scoped credit`() {
        val rows = DeepInfraCreditParser.parse(
            json(
                """{"uid":"u-1","checklist":{"stripe_balance":-1234,"recent":0,"limit":25,"suspend_reason":null,
                   "scoped_credits":[
                     {"name":"H100 grant","granted_cents":1000,"remaining_cents":500,"granted_ts":1,"expires_ts":2,"expired":false},
                     {"name":"Expired grant","granted_cents":300,"remaining_cents":300,"granted_ts":1,"expires_ts":2,"expired":true}]}}""",
            ),
        )
        assertEquals(2, rows.size)
        assertEquals("USD", rows[0].currency)
        // Negative cents are money left to spend, and the spending limit is never a balance.
        assertAmount("12.34", rows[0].amount)
        assertNull("a spending limit is never a balance", rows[0].total)
        assertEquals("Prepaid balance", rows[0].label)
        assertAmount("5", rows[1].amount)
        assertEquals("H100 grant", rows[1].label)
        assertNull(rows[1].total)
    }

    @Test fun `deepinfra keeps an owed balance negative and refuses to read nothing as zero`() {
        val owed = DeepInfraCreditParser.parse(
            json("""{"checklist":{"stripe_balance":500,"recent":4.5,"limit":null,"suspend_reason":null}}"""),
        )
        assertEquals(1, owed.size)
        // Positive cents are money owed, and the row says so instead of reading as healthy.
        assertAmount("-5", owed[0].amount)
        assertAmount("0", DeepInfraCreditParser.parse(
            json("""{"checklist":{"stripe_balance":0,"recent":0,"limit":null,"suspend_reason":null}}"""),
        )[0].amount)
        // A credit whose `expired` is absent is live, as the schema's default says.
        assertEquals(
            2,
            DeepInfraCreditParser.parse(
                json("""{"checklist":{"stripe_balance":0,"scoped_credits":[{"name":"Live","remaining_cents":250}]}}"""),
            ).size,
        )
        for (body in listOf(
            """{"uid":"u-1"}""",
            """{"checklist":null}""",
            """{"checklist":{"recent":0,"limit":null,"suspend_reason":null}}""",
            """{"checklist":{"stripe_balance":"lots"}}""",
        )) {
            assertThrows(CreditError::class.java) { DeepInfraCreditParser.parse(json(body)) }
        }
        assertThrows(CreditError.Malformed::class.java) {
            DeepInfraCreditParser.parse(
                json("""{"checklist":{"stripe_balance":0,"scoped_credits":[{"name":"No figure"}]}}"""),
            )
        }
    }

    @Test fun `tokenrouter adds valid vouchers to the topped-up balance and labels the spend`() {
        val rows = TokenRouterCreditParser.parse(
            json(
                """{"success":true,"message":"","data":{"topUpBalance":120.50,"voucherEfficientAmount":30.00,
                   "toppedUpSpent":80.25,"voucherSpent":10.00}}""",
            ),
        )
        assertEquals(1, rows.size)
        assertEquals("USD", rows[0].currency)
        assertAmount("150.50", rows[0].amount)
        assertNull("the wallet states no cap, so there is no bar", rows[0].total)
        assertEquals("Spent: 80.25 top-up, 10.00 vouchers", rows[0].label)
        // A figure sent as a string is the same figure; the digits are read, never a float.
        assertAmount(
            "42.5",
            TokenRouterCreditParser.parse(
                json("""{"data":{"topUpBalance":"42.50","voucherEfficientAmount":0}}"""),
            )[0].amount,
        )
    }

    @Test fun `tokenrouter keeps a negative topped-up balance and refuses to read nothing as zero`() {
        val negative = TokenRouterCreditParser.parse(
            json(
                """{"data":{"topUpBalance":-5.25,"voucherEfficientAmount":10.00,"toppedUpSpent":0,"voucherSpent":0}}""",
            ),
        )
        assertAmount("4.75", negative[0].amount)
        // Without both spend figures there is no sentence to show, and the balance still reads.
        val noSpend = TokenRouterCreditParser.parse(
            json("""{"data":{"topUpBalance":42.50,"voucherEfficientAmount":0}}"""),
        )
        assertEquals(1, noSpend.size)
        assertNull("a wallet without spend figures shows no spend label", noSpend[0].label)
        assertNull(
            TokenRouterCreditParser.parse(
                json("""{"data":{"topUpBalance":1,"voucherEfficientAmount":0,"toppedUpSpent":5}}"""),
            )[0].label,
        )
        for (body in listOf(
            """{"success":true}""",
            """{"data":null}""",
            """{"data":{"voucherEfficientAmount":30.00}}""",
            """{"data":{"topUpBalance":120.50}}""",
            """{"data":{"topUpBalance":"lots","voucherEfficientAmount":0}}""",
        )) {
            assertThrows(CreditError::class.java) { TokenRouterCreditParser.parse(json(body)) }
        }
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
