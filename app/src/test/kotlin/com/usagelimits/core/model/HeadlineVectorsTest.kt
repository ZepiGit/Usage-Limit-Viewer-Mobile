package com.usagelimits.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account headline, proven against the vector file the Swift suite reads too.
 *
 * Android and iOS share behaviour, not code, so the only thing that keeps the two headline
 * functions answering the same question the same way is a table both run. The file lives once,
 * outside either platform, and its expected ids were written by hand from the rule — a vector
 * generated from one implementation would only prove that implementation agrees with itself.
 *
 * The loader is strict on purpose. A renamed key that a lenient parser skipped, or a file that
 * emptied out, would leave this test green while proving nothing.
 */
class HeadlineVectorsTest {

    @Serializable
    private data class VectorFile(val description: String, val vectors: List<Vector>)

    @Serializable
    private data class Vector(
        val name: String,
        val fetchedAt: Long?,
        val now: Long,
        val windows: List<Window>,
        val expected: String?,
    )

    @Serializable
    private data class Window(
        val id: String,
        val scope: Scope,
        val remainingPercent: Double?,
        val exhausted: Boolean,
        val resetAt: Long?,
    ) {
        fun toUsageWindow() = UsageWindow(
            id = id,
            label = id,
            category = WindowCategory.OTHER,
            usedPercent = remainingPercent?.let { 100.0 - it },
            periodSeconds = null,
            resetAt = resetAt,
            exhausted = exhausted,
            scope = scope.model,
        )
    }

    @Serializable
    private enum class Scope(val model: UsageScope) {
        @SerialName("global") GLOBAL(UsageScope.GLOBAL),
        @SerialName("model") MODEL(UsageScope.MODEL),
        @SerialName("unknown") UNKNOWN(UsageScope.UNKNOWN),
    }

    private fun parse(text: String): List<Vector> {
        val vectors = SharedVectors.parse(text, VectorFile.serializer()).vectors
        require(vectors.isNotEmpty()) { "the headline vector file holds no vectors" }
        return vectors
    }

    private val file = SharedVectors.file("account-headline.json")

    @Test
    fun `every shared vector picks the expected headline`() {
        val vectors = parse(file.readText())
        for (vector in vectors) {
            val windows = vector.windows.map { it.toUsageWindow() }
            assertEquals(vector.name, vector.expected, windows.headlineAt(vector.fetchedAt, vector.now)?.id)
            // The snapshot always knows its fetch time; where the vector does too, the snapshot's
            // own function must give the same answer as the rule it delegates to.
            vector.fetchedAt?.let { fetchedAt ->
                val snapshot = UsageSnapshot("a", fetchedAt, SnapshotStatus.OK, windows)
                assertEquals(vector.name, vector.expected, snapshot.headlineAt(vector.now)?.id)
            }
        }
    }

    @Test
    fun `the vector file covers every case the rule names`() {
        // Not a count to keep in step with the file: a floor, so a file truncated by a bad merge
        // cannot pass as a smaller suite.
        assertTrue(parse(file.readText()).size >= 9)
    }

    @Test
    fun `a renamed key fails the suite instead of skipping the vector`() {
        val renamed = """
            {"description": "", "vectors": [{"name": "x", "fetchedAt": null, "now": 0,
              "windows": [{"id": "a", "scope": "global", "remaining": 5, "exhausted": false, "resetAt": null}],
              "expected": "a"}]}
        """.trimIndent()

        assertThrows(SerializationException::class.java) { parse(renamed) }
    }

    @Test
    fun `an empty vector list fails the suite`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"description": "", "vectors": []}""")
        }
    }
}
