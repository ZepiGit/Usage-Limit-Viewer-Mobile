package com.usagelimits.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which accounts are pinned and in which order, proven against the vector file the Swift suite
 * reads too. Expected lists were written by hand from the rule in the file's description.
 */
class PinnedAccountsVectorsTest {

    @Serializable
    private data class VectorFile(val description: String, val schema: String, val vectors: List<Vector>)

    @Serializable
    private data class Vector(
        val name: String,
        val enabled: Boolean,
        val pinned: List<String>,
        val usageAccounts: List<UsageAccount>,
        // Credit accounts are their own table here, so they never reach the selection; the
        // vectors list them to prove their ids are not mistaken for usage accounts.
        val creditAccounts: List<String>,
        val expected: List<String>,
    )

    @Serializable
    private data class UsageAccount(val id: String, val hasReading: Boolean)

    private fun parse(text: String): List<Vector> {
        val vectors = SharedVectors.parse(text, VectorFile.serializer()).vectors
        require(vectors.isNotEmpty()) { "the pinned accounts vector file holds no vectors" }
        return vectors
    }

    private val file = SharedVectors.file("pinned-accounts.json")

    @Test
    fun `every shared vector selects the expected accounts in order`() {
        for (vector in parse(file.readText())) {
            assertEquals(
                vector.name,
                vector.expected,
                pinnedAccountIds(vector.enabled, vector.pinned, vector.usageAccounts.map { it.id }),
            )
        }
    }

    @Test
    fun `the vector file covers every case the rule names`() {
        val vectors = parse(file.readText())
        assertEquals("names must be unique", vectors.size, vectors.map { it.name }.toSet().size)
        val usageIds = { v: Vector -> v.usageAccounts.map { it.id }.toSet() }
        // Read from the inputs alone, so the check never consults the function under test.
        val scenarios: Map<String, (Vector) -> Boolean> = mapOf(
            "nothing stored" to { it.enabled && it.pinned.isEmpty() },
            "feature off with pins stored" to { !it.enabled && it.pinned.isNotEmpty() },
            "four or more stored ids" to { it.enabled && it.pinned.size >= 4 },
            "a stored id whose account is gone" to { v -> v.enabled && v.pinned.any { it !in usageIds(v) && it !in v.creditAccounts } },
            "a credit account id" to { v -> v.enabled && v.pinned.any { it in v.creditAccounts } },
            "a pinned usage account without a reading" to { v -> v.enabled && v.usageAccounts.any { !it.hasReading && it.id in v.pinned } },
            "pin order differing from account order" to { v ->
                val kept = v.pinned.filter { it in usageIds(v) }.distinct()
                v.enabled && kept != v.usageAccounts.map { it.id }.filter { it in kept }
            },
            "an id stored twice" to { it.enabled && it.pinned.size != it.pinned.toSet().size },
        )
        for ((name, holds) in scenarios) assertTrue("no vector for $name", vectors.any(holds))
        for (count in 1..3) assertTrue("no vector selecting $count", vectors.any { it.expected.size == count })
    }

    @Test
    fun `a renamed key fails the suite instead of skipping the vector`() {
        val renamed = """
            {"description": "", "schema": "", "vectors": [{"name": "x", "on": true, "pinned": [],
              "usageAccounts": [], "creditAccounts": [], "expected": []}]}
        """.trimIndent()

        assertThrows(SerializationException::class.java) { parse(renamed) }
    }

    @Test
    fun `an empty vector list fails the suite`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"description": "", "schema": "", "vectors": []}""")
        }
    }
}
