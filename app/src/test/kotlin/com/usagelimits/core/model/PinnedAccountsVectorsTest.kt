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
        assertTrue(vectors.size >= 12)
        assertEquals("names must be unique", vectors.size, vectors.map { it.name }.toSet().size)
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
