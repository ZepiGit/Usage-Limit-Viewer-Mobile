package com.usagelimits.providers

import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.model.SharedVectors
import com.usagelimits.core.network.ProviderException
import com.usagelimits.providers.credits.CreditError
import com.usagelimits.providers.credits.DeepInfraCreditClient
import com.usagelimits.providers.credits.OllamaCreditClient
import com.usagelimits.providers.credits.OpenRouterCreditParser
import com.usagelimits.providers.credits.TokenRouterCreditClient
import com.usagelimits.providers.credits.VercelCreditClient
import com.usagelimits.providers.minimax.MiniMaxProvider
import com.usagelimits.providers.ollama.OllamaProvider
import com.usagelimits.providers.scriptedHttp
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The shape of the shared provider payload vectors.
 *
 * The file pins how a documented response becomes credit rows or usage windows, and which
 * failures stay unknown rather than zero. Both parsers read it — Android here, iOS in
 * `ProviderPayloadMappingVectorsTests` — so a section this file loses or a field one side reads
 * differently has to fail a suite rather than pass as an empty test.
 *
 * Only the shape is checked; each slice adds one test method per section that maps the same cases
 * through its parser.
 */
class ProviderPayloadMappingVectorsTest {

    private val sections = listOf(
        "openrouterKeyWindow", "vercelAiGateway", "deepinfra", "tokenrouter", "ollama", "minimax",
    )

    private val file = SharedVectors.file("provider-payload-mapping.json")

    private fun root(): JsonObject {
        val element = SharedVectors.parse(file.readText(), JsonElement.serializer())
        assertTrue("the provider payload vector file must be one JSON object", element is JsonObject)
        return element as JsonObject
    }

    private fun cases(section: String): List<JsonObject> {
        val value = root()[section]
        assertTrue("$section must be a JSON array", value is JsonArray)
        return (value as JsonArray).map {
            assertTrue("$section holds only JSON objects", it is JsonObject)
            it as JsonObject
        }
    }

    private fun string(where: String, element: JsonElement?): String {
        assertTrue("$where must be a JSON string", element is JsonPrimitive && element.isString)
        return (element as JsonPrimitive).content
    }

    private fun decimalText(where: String, element: JsonElement?) {
        val text = string(where, element)
        assertTrue("$where must be a decimal string, was \"$text\"", DECIMAL.matches(text))
        BigDecimal(text)
    }

    private fun instant(where: String, element: JsonElement?) {
        val text = string(where, element)
        try {
            Instant.parse(text)
        } catch (error: RuntimeException) {
            fail("$where must be an ISO-8601 UTC instant, was \"$text\": ${error.message}")
        }
    }

    @Test
    fun `the file holds the description and exactly the six sections`() {
        val root = root()
        assertEquals(setOf("description") + sections, root.keys)
        assertTrue(string("description", root["description"]).isNotBlank())
    }

    @Test
    fun `every case carries a name, a status, a body and one expect form`() {
        for (section in sections) {
            for ((index, case) in cases(section).withIndex()) {
                val where = "$section[$index]"
                val allowed = setOf("name", "status", "body", "expect", "now")
                val required = setOf("name", "status", "body", "expect")
                assertTrue("$where has unexpected keys ${case.keys - allowed}", allowed.containsAll(case.keys))
                assertTrue("$where is missing ${required - case.keys}", case.keys.containsAll(required))
                assertTrue("$where name must not be blank", string("$where.name", case["name"]).isNotBlank())
                val status = case["status"]
                assertTrue(
                    "$where.status must be a JSON integer",
                    status is JsonPrimitive && !status.isString && status.content.toIntOrNull() != null,
                )
                case["now"]?.let { instant("$where.now", it) }
                val expect = case["expect"]
                assertTrue("$where.expect must be a JSON object", expect is JsonObject)
                checkExpect("$where.expect", expect as JsonObject)
            }
        }
    }

    @Test
    fun `balances carry string amounts and windows carry a used percent that may be null`() {
        for (section in sections) {
            for ((index, case) in cases(section).withIndex()) {
                val expect = case["expect"] as JsonObject
                (expect["balances"] as? JsonArray)?.forEachIndexed { row, element ->
                    val where = "$section[$index].expect.balances[$row]"
                    assertTrue("$where must be a JSON object", element is JsonObject)
                    val balance = element as JsonObject
                    val allowed = setOf("currency", "amount", "total", "resetAt", "usedPercent")
                    assertTrue("$where has unexpected keys ${balance.keys - allowed}", allowed.containsAll(balance.keys))
                    assertTrue("$where is missing ${setOf("currency", "amount") - balance.keys}",
                        balance.keys.containsAll(setOf("currency", "amount")))
                    string("$where.currency", balance["currency"])
                    decimalText("$where.amount", balance["amount"])
                    balance["total"]?.takeUnless { it is JsonNull }?.let { decimalText("$where.total", it) }
                    balance["resetAt"]?.takeUnless { it is JsonNull }?.let { instant("$where.resetAt", it) }
                    balance["usedPercent"]?.takeUnless { it is JsonNull }?.let {
                        assertTrue(
                            "$where.usedPercent must be a JSON number or null",
                            it is JsonPrimitive && !it.isString && it.content.toDoubleOrNull() != null,
                        )
                    }
                }
                (expect["windows"] as? JsonArray)?.forEachIndexed { row, element ->
                    val where = "$section[$index].expect.windows[$row]"
                    assertTrue("$where must be a JSON object", element is JsonObject)
                    val window = element as JsonObject
                    assertEquals(setOf("id", "usedPercent", "resetAt"), window.keys)
                    string("$where.id", window["id"])
                    window["usedPercent"]?.takeUnless { it is JsonNull }?.let {
                        assertTrue(
                            "$where.usedPercent must be a JSON number or null",
                            it is JsonPrimitive && !it.isString && it.content.toDoubleOrNull() != null,
                        )
                    }
                    window["resetAt"]?.takeUnless { it is JsonNull }?.let { instant("$where.resetAt", it) }
                }
            }
        }
    }

    /**
     * The OpenRouter key window: one row per key, its reset at the next UTC boundary of
     * `limit_reset`, and its used share clamped to 0..100. A limit with no remainder must stay
     * unknown rather than read as an empty balance.
     */
    @Test
    fun `an openrouter key limit carries its period reset and clamped used share`() {
        for ((index, case) in cases("openrouterKeyWindow").withIndex()) {
            val where = "openrouterKeyWindow[$index]"
            val now = case["now"] ?: throw AssertionError("$where needs a now instant")
            val nowMs = Instant.parse(string("$where.now", now)).toEpochMilli()
            val body = case["body"] as JsonObject
            val expect = case["expect"] as JsonObject
            val balances = try {
                OpenRouterCreditParser.parseKey(body, nowMs)
            } catch (error: CreditError) {
                assertEquals(
                    "$where: ${error.message}",
                    string("$where.expect.error", expect["error"]),
                    errorKind(error),
                )
                continue
            }
            assertNull("$where: the parse succeeded but the case expects an error", expect["error"])
            val rows = expect["balances"] as JsonArray
            assertEquals("$where: row count", rows.size, balances.size)
            for ((row, element) in rows.withIndex()) {
                val at = "$where.expect.balances[$row]"
                val expected = element as JsonObject
                val balance = balances[row]
                assertEquals("$at: currency", string("$at.currency", expected["currency"]), balance.currency)
                assertDecimal("$at: amount", decimalTextOrNull("$at.amount", expected["amount"]), balance.amount)
                assertDecimal("$at: total", decimalTextOrNull("$at.total", expected["total"]), balance.total)
                val reset = expected["resetAt"]?.takeUnless { it is JsonNull }
                    ?.let { Instant.parse(string("$at.resetAt", it)).toEpochMilli() }
                assertEquals("$at: resetAt", reset, balance.resetsAt)
                val used = expected["usedPercent"]?.takeUnless { it is JsonNull }
                    ?.let { (it as JsonPrimitive).content.toDouble() }
                if (used == null) {
                    assertNull("$at: a row without a used share has no bar", balance.remainingPercent)
                } else {
                    assertEquals("$at: usedPercent", used, 100.0 - balance.remainingPercent!!, 1e-9)
                }
            }
        }
    }

    /**
     * The Vercel AI Gateway credits call: decimal strings only, an optional Token Pass row with
     * its reset, and 401/403 as the documented failures. A JSON number must stay unknown rather
     * than be rounded into a balance, and a null Token Pass must not become a second zero.
     */
    @Test
    fun `vercel ai gateway reads decimal strings and refuses the rest`() {
        for ((index, case) in cases("vercelAiGateway").withIndex()) {
            val where = "vercelAiGateway[$index]"
            val status = (case["status"] as JsonPrimitive).content.toInt()
            val client = VercelCreditClient(scriptedHttp(status to (case["body"] as JsonObject).toString()))
            val expect = case["expect"] as JsonObject
            val balances = try {
                runBlocking { client.fetchBalances("vck-test", emptyMap()) }
            } catch (error: CreditError) {
                assertEquals(
                    "$where: ${error.message}",
                    string("$where.expect.error", expect["error"]),
                    errorKind(error),
                )
                continue
            }
            assertNull("$where: the read succeeded but the case expects an error", expect["error"])
            val rows = expect["balances"] as JsonArray
            assertEquals("$where: row count", rows.size, balances.size)
            for ((row, element) in rows.withIndex()) {
                val at = "$where.expect.balances[$row]"
                val expected = element as JsonObject
                val balance = balances[row]
                assertEquals("$at: currency", string("$at.currency", expected["currency"]), balance.currency)
                assertDecimal("$at: amount", decimalTextOrNull("$at.amount", expected["amount"]), balance.amount)
                assertDecimal("$at: total", decimalTextOrNull("$at.total", expected["total"]), balance.total)
                val reset = expected["resetAt"]?.takeUnless { it is JsonNull }
                    ?.let { Instant.parse(string("$at.resetAt", it)).toEpochMilli() }
                assertEquals("$at: resetAt", reset, balance.resetsAt)
                assertNull("$at: no row of this provider draws a bar", balance.remainingPercent)
            }
        }
    }

    /**
     * DeepInfra's billing checklist: `stripe_balance` is signed cents where NEGATIVE is money left
     * to spend, so a positive (owed) figure reads as a negative row. Each live scoped credit is
     * one more row, an expired one adds none, and `limit` is a spending cap that never becomes a
     * balance. A missing checklist and an unreadable figure must stay unknown, never zero.
     */
    @Test
    fun `deepinfra reads the signed prepaid balance and the live scoped credits`() {
        for ((index, case) in cases("deepinfra").withIndex()) {
            val where = "deepinfra[$index]"
            val status = (case["status"] as JsonPrimitive).content.toInt()
            val client = DeepInfraCreditClient(scriptedHttp(status to (case["body"] as JsonObject).toString()))
            val expect = case["expect"] as JsonObject
            val balances = try {
                runBlocking { client.fetchBalances("di-test", emptyMap()) }
            } catch (error: CreditError) {
                assertEquals(
                    "$where: ${error.message}",
                    string("$where.expect.error", expect["error"]),
                    errorKind(error),
                )
                continue
            }
            assertNull("$where: the read succeeded but the case expects an error", expect["error"])
            val rows = expect["balances"] as JsonArray
            assertEquals("$where: row count", rows.size, balances.size)
            for ((row, element) in rows.withIndex()) {
                val at = "$where.expect.balances[$row]"
                val expected = element as JsonObject
                val balance = balances[row]
                assertEquals("$at: currency", string("$at.currency", expected["currency"]), balance.currency)
                assertDecimal("$at: amount", decimalTextOrNull("$at.amount", expected["amount"]), balance.amount)
                assertDecimal("$at: total", decimalTextOrNull("$at.total", expected["total"]), balance.total)
                assertNull("$at: no row of this provider draws a bar", balance.remainingPercent)
            }
        }
    }

    /**
     * TokenRouter's management wallet: the topped-up balance plus the currently valid voucher
     * balance is the amount, and no cap is documented, so there is no bar. A wallet without a
     * topped-up balance must stay unknown rather than read as the vouchers alone.
     */
    @Test
    fun `tokenrouter adds the valid vouchers to the topped-up balance`() {
        for ((index, case) in cases("tokenrouter").withIndex()) {
            val where = "tokenrouter[$index]"
            val status = (case["status"] as JsonPrimitive).content.toInt()
            val client = TokenRouterCreditClient(scriptedHttp(status to (case["body"] as JsonObject).toString()))
            val expect = case["expect"] as JsonObject
            val balances = try {
                runBlocking { client.fetchBalances("tr-test", emptyMap()) }
            } catch (error: CreditError) {
                assertEquals(
                    "$where: ${error.message}",
                    string("$where.expect.error", expect["error"]),
                    errorKind(error),
                )
                continue
            }
            assertNull("$where: the read succeeded but the case expects an error", expect["error"])
            val rows = expect["balances"] as JsonArray
            assertEquals("$where: row count", rows.size, balances.size)
            for ((row, element) in rows.withIndex()) {
                val at = "$where.expect.balances[$row]"
                val expected = element as JsonObject
                val balance = balances[row]
                assertEquals("$at: currency", string("$at.currency", expected["currency"]), balance.currency)
                assertDecimal("$at: amount", decimalTextOrNull("$at.amount", expected["amount"]), balance.amount)
                assertDecimal("$at: total", decimalTextOrNull("$at.total", expected["total"]), balance.total)
                assertNull("$at: the wallet states no cap, so the row draws no bar", balance.remainingPercent)
            }
        }
    }

    /**
     * Ollama Cloud serves two accounts from one call: the `included` credits as usage windows and
     * the `purchased` balance as money. A current plan's allowance is one window whose used share
     * is what it has spent; a legacy plan states session and weekly limits as remaining
     * percentages. A missing block is omitted, never zero, and a rejected key is invalid on both
     * accounts.
     */
    @Test
    fun `ollama reads the included allowance beside the legacy limits and the purchased balance`() {
        for ((index, case) in cases("ollama").withIndex()) {
            val where = "ollama[$index]"
            val status = (case["status"] as JsonPrimitive).content.toInt()
            val http = scriptedHttp(status to (case["body"] as JsonObject).toString())
            val expect = case["expect"] as JsonObject
            val windows = runCatching {
                runBlocking { OllamaProvider(http).fetchUsage(ollamaAccount(), ollamaCredentials).windows }
            }
            val balances = runCatching {
                runBlocking { OllamaCreditClient(http).fetchBalances("ollama-test", emptyMap()) }
            }

            if (expect.containsKey("error")) {
                val kind = string("$where.expect.error", expect["error"])
                assertEquals("$where: usage windows", kind, usageErrorKind(windows.exceptionOrNull()))
                assertEquals("$where: purchased balance", kind, creditErrorKind(balances.exceptionOrNull()))
                continue
            }
            val read = windows.getOrElse { throw AssertionError("$where: the windows did not read: $it") }
            val rows = balances.getOrElse { throw AssertionError("$where: the balance did not read: $it") }

            val expectedWindows = expect["windows"] as JsonArray
            assertEquals("$where: window count", expectedWindows.size, read.size)
            for ((row, element) in expectedWindows.withIndex()) {
                val at = "$where.expect.windows[$row]"
                val expected = element as JsonObject
                val window = read[row]
                assertEquals("$at: id", string("$at.id", expected["id"]), window.id)
                val used = expected["usedPercent"]?.takeUnless { it is JsonNull }
                    ?.let { (it as JsonPrimitive).content.toDouble() }
                val actual = window.usedPercent
                if (used == null) {
                    assertNull("$at: an unreadable share stays unknown", actual)
                } else {
                    assertTrue("$at: expected $used but was null", actual != null)
                    assertEquals("$at: usedPercent", used, actual!!, 1e-9)
                }
                val reset = expected["resetAt"]?.takeUnless { it is JsonNull }
                    ?.let { Instant.parse(string("$at.resetAt", it)).toEpochMilli() }
                assertEquals("$at: resetAt", reset, window.resetAt)
            }

            val expectedBalances = expect["balances"] as? JsonArray ?: JsonArray(emptyList())
            assertEquals("$where: balance row count", expectedBalances.size, rows.size)
            for ((row, element) in expectedBalances.withIndex()) {
                val at = "$where.expect.balances[$row]"
                val expected = element as JsonObject
                val balance = rows[row]
                assertEquals("$at: currency", string("$at.currency", expected["currency"]), balance.currency)
                assertDecimal("$at: amount", decimalTextOrNull("$at.amount", expected["amount"]), balance.amount)
                assertDecimal("$at: total", decimalTextOrNull("$at.total", expected["total"]), balance.total)
                assertNull("$at: the purchased balance states no cap, so the row draws no bar", balance.remainingPercent)
            }
        }
    }

    /**
     * The MiniMax Token Plan: one five-hour and one weekly window per `model_name`, with the
     * ambiguous `*_usage_count` resolved the way the vendor's own CLI resolves it — read as a
     * remaining count, flipped to consumed when a stated percentage agrees better with that
     * reading. A rejected key arrives as HTTP 200 with `base_resp.status_code` 1004, any other
     * envelope code stays unknown, and a plan without a weekly allowance is one window rather
     * than a zero-percent week.
     */
    @Test
    fun `minimax reads one five-hour and one weekly window per model and honours the base_resp envelope`() {
        for ((index, case) in cases("minimax").withIndex()) {
            val where = "minimax[$index]"
            val status = (case["status"] as JsonPrimitive).content.toInt()
            val http = scriptedHttp(status to (case["body"] as JsonObject).toString())
            val expect = case["expect"] as JsonObject
            val read = runCatching {
                runBlocking { MiniMaxProvider(http).fetchUsage(minimaxAccount(), minimaxCredentials).windows }
            }

            if (expect.containsKey("error")) {
                assertEquals(
                    "$where: ${read.exceptionOrNull()}",
                    string("$where.expect.error", expect["error"]),
                    usageErrorKind(read.exceptionOrNull()),
                )
                continue
            }
            val windows = read.getOrElse { throw AssertionError("$where: the windows did not read: $it") }
            val expectedWindows = expect["windows"] as JsonArray
            assertEquals("$where: window count", expectedWindows.size, windows.size)
            for ((row, element) in expectedWindows.withIndex()) {
                val at = "$where.expect.windows[$row]"
                val expected = element as JsonObject
                val window = windows[row]
                assertEquals("$at: id", string("$at.id", expected["id"]), window.id)
                val used = expected["usedPercent"]?.takeUnless { it is JsonNull }
                    ?.let { (it as JsonPrimitive).content.toDouble() }
                val actual = window.usedPercent
                if (used == null) {
                    assertNull("$at: an unreadable share stays unknown", actual)
                } else {
                    assertTrue("$at: expected $used but was null", actual != null)
                    assertEquals("$at: usedPercent", used, actual!!, 1e-9)
                }
                val reset = expected["resetAt"]?.takeUnless { it is JsonNull }
                    ?.let { Instant.parse(string("$at.resetAt", it)).toEpochMilli() }
                assertEquals("$at: resetAt", reset, window.resetAt)
            }
        }
    }

    /** The usage account's key; the fixtures are synthetic and nothing leaves the process. */
    private val minimaxCredentials = OAuthCredentials(
        "mm-test",
        null,
        null,
        null,
        providerData = mapOf("region" to "intl"),
    )

    private fun minimaxAccount() = ProviderAccount(
        localId = "minimax-test",
        provider = ProviderId.MINIMAX,
        externalAccountId = "key-minimax-test",
        email = null,
        displayName = null,
        plan = null,
        credentialReference = "minimax-test",
        createdAt = 0L,
        lastSuccessfulSync = null,
    )

    /** The usage account's key; the fixtures are synthetic and nothing leaves the process. */
    private val ollamaCredentials = OAuthCredentials("ollama-test", null, null, null)

    private fun ollamaAccount() = ProviderAccount(
        localId = "ollama-test",
        provider = ProviderId.OLLAMA,
        externalAccountId = "key-ollama-test",
        email = null,
        displayName = null,
        plan = null,
        credentialReference = "ollama-test",
        createdAt = 0L,
        lastSuccessfulSync = null,
    )

    /** A usage read that fails is unknown unless the endpoint named the key as rejected. */
    private fun usageErrorKind(error: Throwable?): String = when (error) {
        is ProviderException.Unauthorized -> "invalidKey"
        is ProviderException.Forbidden -> "forbidden"
        else -> "unknown"
    }

    private fun creditErrorKind(error: Throwable?): String = (error as? CreditError)?.let(::errorKind) ?: "unknown"

    private fun decimalTextOrNull(where: String, element: JsonElement?): BigDecimal? =
        element?.takeUnless { it is JsonNull }?.let { BigDecimal(string(where, it)) }

    private fun assertDecimal(where: String, expected: BigDecimal?, actual: BigDecimal?) {
        if (expected == null) {
            assertNull("$where must be absent", actual)
        } else {
            assertTrue("$where: expected $expected but was $actual", actual != null && expected.compareTo(actual) == 0)
        }
    }

    /** The failure kinds the vectors name; a malformed payload is unknown, never zero. */
    private fun errorKind(error: CreditError): String = when (error) {
        is CreditError.Rejected -> if (error.status == 403) "forbidden" else "invalidKey"
        // Vercel's 403 asks for a team id instead of naming a rejected key; it is still a 403.
        is CreditError.TeamDenied -> "forbidden"
        // DeepInfra's 403 names the scope the key lacks; it is still a 403.
        is CreditError.MissingScope -> "forbidden"
        else -> "unknown"
    }

    private fun checkExpect(where: String, expect: JsonObject) {
        val forms = setOf("balances", "windows", "error")
        assertTrue("$where has unexpected keys ${expect.keys - forms}", forms.containsAll(expect.keys))
        assertTrue("$where states none of balances, windows or error", expect.isNotEmpty())
        if (expect.containsKey("error")) {
            assertEquals("$where: error cannot accompany balances or windows", setOf("error"), expect.keys)
            assertTrue(
                "$where.error must be invalidKey, forbidden or unknown",
                string("$where.error", expect["error"]) in setOf("invalidKey", "forbidden", "unknown"),
            )
        }
        expect["balances"]?.let { assertTrue("$where.balances must be an array", it is JsonArray) }
        expect["windows"]?.let { assertTrue("$where.windows must be an array", it is JsonArray) }
    }

    private companion object {
        val DECIMAL = Regex("-?\\d+(?:\\.\\d+)?")
    }
}
