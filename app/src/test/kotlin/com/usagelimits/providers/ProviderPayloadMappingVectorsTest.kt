package com.usagelimits.providers

import com.usagelimits.core.model.SharedVectors
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
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
                    val allowed = setOf("currency", "amount", "total")
                    assertTrue("$where has unexpected keys ${balance.keys - allowed}", allowed.containsAll(balance.keys))
                    assertTrue("$where is missing ${setOf("currency", "amount") - balance.keys}",
                        balance.keys.containsAll(setOf("currency", "amount")))
                    string("$where.currency", balance["currency"])
                    decimalText("$where.amount", balance["amount"])
                    balance["total"]?.takeUnless { it is JsonNull }?.let { decimalText("$where.total", it) }
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
