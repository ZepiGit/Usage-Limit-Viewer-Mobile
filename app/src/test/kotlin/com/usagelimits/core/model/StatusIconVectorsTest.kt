package com.usagelimits.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a status icon shows, proven against the vector file the Swift suite reads too.
 *
 * The expected contents were written by hand from the rule in the file's description. The loader
 * is strict for the reason `HeadlineVectorsTest` gives.
 */
class StatusIconVectorsTest {

    @Serializable
    private data class VectorFile(val description: String, val schema: String, val vectors: List<Vector>)

    @Serializable
    private data class Vector(
        val name: String,
        val headline: Headline?,
        val validity: Validity,
        val needsReauthentication: Boolean,
        val expected: Expected,
    )

    @Serializable
    private data class Headline(val token: String, val side: Side, val exhausted: Boolean) {
        // Fill and fill state are not inputs of the rule; any value proves the same.
        fun toPresentation() = WindowPresentation(token, side.mode, fill = null, fillState = FillState.NO_FILL, exhausted = exhausted)
    }

    @Serializable
    private enum class Side(val mode: PercentDisplay) {
        @SerialName("left") LEFT(PercentDisplay.LEFT),
        @SerialName("used") USED(PercentDisplay.USED),
    }

    @Serializable
    private enum class Validity(val model: ReadingValidity) {
        @SerialName("current") CURRENT(ReadingValidity.CURRENT),
        @SerialName("stale") STALE(ReadingValidity.STALE),
        @SerialName("resetDue") RESET_DUE(ReadingValidity.RESET_DUE),
        @SerialName("error") ERROR(ReadingValidity.ERROR),
    }

    @Serializable
    private enum class Kind {
        @SerialName("error") ERROR,
        @SerialName("exhausted") EXHAUSTED,
        @SerialName("unknown") UNKNOWN,
        @SerialName("none") NONE,
        @SerialName("figure") FIGURE,
    }

    @Serializable
    private data class Expected(val content: Kind, val figure: Int?, val outdated: Boolean) {
        fun toStatusIcon(): StatusIcon = StatusIcon(
            content = when (content) {
                Kind.ERROR -> StatusIconContent.Error
                Kind.EXHAUSTED -> StatusIconContent.Exhausted
                Kind.UNKNOWN -> StatusIconContent.Unknown
                Kind.NONE -> StatusIconContent.None
                Kind.FIGURE -> StatusIconContent.Figure(requireNotNull(figure) { "a figure vector states its number" })
            },
            outdated = outdated,
        )
    }

    private fun parse(text: String): List<Vector> {
        val vectors = SharedVectors.parse(text, VectorFile.serializer()).vectors
        require(vectors.isNotEmpty()) { "the status icon vector file holds no vectors" }
        return vectors
    }

    private val file = SharedVectors.file("status-icon.json")

    @Test
    fun `every shared vector shows the expected status icon`() {
        for (vector in parse(file.readText())) {
            assertEquals(
                vector.name,
                vector.expected.toStatusIcon(),
                statusIcon(vector.headline?.toPresentation(), vector.validity.model, vector.needsReauthentication),
            )
        }
    }

    /**
     * Which precedence rows of the rule an input satisfies, read from the input alone, so the
     * check below never consults the function under test.
     */
    private fun Vector.rows(): Set<Kind> = buildSet {
        if (needsReauthentication || validity == Validity.ERROR) add(Kind.ERROR)
        if (headline?.exhausted == true) add(Kind.EXHAUSTED)
        if (headline == null || headline.token == PERCENT_TOKEN_UNKNOWN) add(Kind.UNKNOWN)
        if (headline?.token == "100") add(Kind.NONE)
        if (headline != null && headline.token != PERCENT_TOKEN_UNKNOWN && headline.token != "100") add(Kind.FIGURE)
    }

    @Test
    fun `every pair of rows that can meet in one input has a vector where the earlier one wins`() {
        val vectors = parse(file.readText())
        assertEquals("names must be unique", vectors.size, vectors.map { it.name }.toSet().size)
        // Rows in precedence order. A full allowance and a figure exclude each other and the dash
        // by their tokens; every other pair can meet, and the earlier row must be seen to win.
        val order = listOf(Kind.ERROR, Kind.EXHAUSTED, Kind.UNKNOWN, Kind.NONE, Kind.FIGURE)
        val exclusive = setOf(setOf(Kind.UNKNOWN, Kind.NONE), setOf(Kind.UNKNOWN, Kind.FIGURE), setOf(Kind.NONE, Kind.FIGURE))
        for ((i, winner) in order.withIndex()) for (loser in order.drop(i + 1)) {
            if (setOf(winner, loser) in exclusive) continue
            assertTrue(
                "no vector shows $winner winning over $loser",
                vectors.any { it.rows().containsAll(listOf(winner, loser)) && it.expected.content == winner },
            )
        }
        // Every input also lands in one token row (dash, glyph alone or figure), so only those
        // can stand alone; error and exhausted are covered by the pairs above.
        for (row in listOf(Kind.UNKNOWN, Kind.NONE, Kind.FIGURE)) {
            assertTrue("no vector for $row alone", vectors.any { it.rows() == setOf(row) && it.expected.content == row })
        }
        // The underline both on and off where it may appear.
        for (row in listOf(Kind.FIGURE, Kind.NONE)) for (validity in listOf(Validity.STALE, Validity.RESET_DUE)) {
            assertTrue("no $validity $row", vectors.any { it.expected.content == row && it.validity == validity && it.expected.outdated })
        }
        for (row in listOf(Kind.ERROR, Kind.EXHAUSTED, Kind.UNKNOWN)) {
            assertTrue("no aged $row without an underline", vectors.any {
                it.expected.content == row && it.validity in setOf(Validity.STALE, Validity.RESET_DUE) && !it.expected.outdated
            })
        }
    }

    @Test
    fun `a renamed key fails the suite instead of skipping the vector`() {
        val renamed = """
            {"description": "", "schema": "", "vectors": [{"name": "x", "headline": null, "validity": "current",
              "reauth": false, "expected": {"content": "unknown", "figure": null, "outdated": false}}]}
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
