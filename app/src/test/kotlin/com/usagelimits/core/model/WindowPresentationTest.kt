package com.usagelimits.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the same hand-written presentation and fill-state vectors as the Swift suite. */
class WindowPresentationTest {

    @Serializable
    private data class VectorFile(
        val description: String,
        val presentations: List<Row>,
        val fillStates: List<State>,
    )

    private fun parse(text: String): VectorFile {
        val vectors = SharedVectors.parse(text, VectorFile.serializer())
        require(vectors.presentations.isNotEmpty()) { "the presentation vector file holds no presentations" }
        require(vectors.fillStates.isNotEmpty()) { "the presentation vector file holds no fill states" }
        return vectors
    }

    private fun vectors() = parse(SharedVectors.file("window-presentation.json").readText())

    @Serializable
    private data class Row(
        val name: String,
        val remaining: Double?,
        val severity: VectorSeverity,
        val validity: VectorValidity,
        val mode: VectorMode,
        val token: String,
        val fill: Double?,
        val fillState: VectorFillState,
        val exhausted: Boolean,
    )

    @Serializable
    private data class State(
        val name: String,
        val validity: VectorValidity,
        val severity: VectorSeverity,
        val fill: Double?,
        val mode: VectorMode,
        val expected: VectorFillState,
    )

    @Serializable
    private enum class VectorSeverity(val model: Severity) {
        @SerialName("healthy") HEALTHY(Severity.HEALTHY),
        @SerialName("medium") MEDIUM(Severity.MEDIUM),
        @SerialName("low") LOW(Severity.LOW),
        @SerialName("limited") LIMITED(Severity.LIMITED),
        @SerialName("exhausted") EXHAUSTED(Severity.EXHAUSTED),
        @SerialName("unknown") UNKNOWN(Severity.UNKNOWN),
        @SerialName("stale") STALE(Severity.STALE),
        @SerialName("error") ERROR(Severity.ERROR);
    }

    @Serializable
    private enum class VectorValidity(val model: ReadingValidity) {
        @SerialName("current") CURRENT(ReadingValidity.CURRENT),
        @SerialName("stale") STALE(ReadingValidity.STALE),
        @SerialName("resetDue") RESET_DUE(ReadingValidity.RESET_DUE),
        @SerialName("error") ERROR(ReadingValidity.ERROR);
    }

    @Serializable
    private enum class VectorMode(val model: PercentDisplay) {
        @SerialName("left") LEFT(PercentDisplay.LEFT),
        @SerialName("used") USED(PercentDisplay.USED);
    }

    @Serializable
    private enum class VectorFillState(val model: FillState) {
        @SerialName("data") DATA(FillState.DATA),
        @SerialName("stale") STALE(FillState.STALE),
        @SerialName("noFill") NO_FILL(FillState.NO_FILL);
    }

    private fun changedDocument(table: String? = null, change: MutableMap<String, JsonElement>.() -> Unit): String {
        val document = Json.parseToJsonElement(SharedVectors.file("window-presentation.json").readText())
            .jsonObject.toMutableMap()
        if (table == null) {
            document.change()
        } else {
            val rows = document.getValue(table).jsonArray.toMutableList()
            rows[0] = JsonObject(rows[0].jsonObject.toMutableMap().apply(change))
            document[table] = JsonArray(rows)
        }
        return JsonObject(document).toString()
    }

    @Test
    fun `unknown fields fail at every schema level`() {
        listOf(null, "presentations", "fillStates").forEach { table ->
            val unknown = changedDocument(table) { put("unexpected", JsonPrimitive(true)) }
            assertThrows("unknown field in $table", SerializationException::class.java) { parse(unknown) }
        }
    }

    @Test
    fun `missing fields fail including nullable values`() {
        listOf(null to "description", "presentations" to "remaining", "fillStates" to "fill").forEach { (table, key) ->
            val missing = changedDocument(table) { remove(key) }
            assertThrows("missing $key in $table", SerializationException::class.java) { parse(missing) }
        }
    }

    @Test
    fun `empty data fails the suite`() {
        assertThrows(SerializationException::class.java) { parse("") }
    }

    @Test
    fun `an empty fill state table fails the suite`() {
        val empty = changedDocument { put("fillStates", JsonArray(emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { parse(empty) }
    }

    @Test
    fun `an empty presentation table fails the suite`() {
        val empty = changedDocument { put("presentations", JsonArray(emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { parse(empty) }
    }

    @Test
    fun `the presentation table holds row for row`() {
        vectors().presentations.forEach { r ->
            val p = windowPresentation(r.remaining, r.severity.model, r.validity.model, r.mode.model)
            val at = "${r.name} (${r.remaining}, ${r.severity}, ${r.validity}, ${r.mode})"
            assertEquals("$at token", r.token, p.token)
            assertEquals("$at side", r.mode.model, p.side)
            assertEquals("$at fillState", r.fillState.model, p.fillState)
            assertEquals("$at exhausted", r.exhausted, p.exhausted)
            if (r.fill == null) assertNull("$at fill", p.fill)
            else assertEquals("$at fill", r.fill, p.fill ?: Double.NaN, 1e-9)
        }
    }

    @Test
    fun `the figure exists exactly when the token is not the dash`() {
        vectors().presentations.forEach { r ->
            val p = windowPresentation(r.remaining, r.severity.model, r.validity.model, r.mode.model)
            assertEquals("${r.name}", r.token != "—", p.hasFigure)
        }
    }

    @Test
    fun `no window presents the dash with no fill in both modes`() {
        listOf(PercentDisplay.LEFT, PercentDisplay.USED).forEach { mode ->
            val p = WindowPresentation.absent(mode)
            assertEquals("—", p.token)
            assertEquals(mode, p.side)
            assertNull(p.fill)
            assertEquals(FillState.NO_FILL, p.fillState)
            assertFalse(p.exhausted)
            assertFalse(p.hasFigure)
        }
    }

    @Test
    fun `absent is exactly what the primitives give for a missing window`() {
        listOf(PercentDisplay.LEFT, PercentDisplay.USED).forEach { mode ->
            val p = WindowPresentation.absent(mode)
            assertEquals(percentToken(null, false, mode), p.token)
            assertEquals(fillFraction(null, false, mode), p.fill)
        }
    }

    @Test
    fun `a null window presents as absent whatever the validity`() {
        val none: UsageWindow? = null
        ReadingValidity.entries.forEach { validity ->
            listOf(PercentDisplay.LEFT, PercentDisplay.USED).forEach { mode ->
                assertEquals("$validity $mode", WindowPresentation.absent(mode), none.presentation(validity, mode))
            }
        }
    }

    @Test
    fun `a window presents from its remainder and its raw severity`() {
        val healthy = window(usedPercent = 27.0)
        val exhaustedWithRemainder = window(usedPercent = 85.0, exhausted = true)
        val noFigure = window(usedPercent = null, exhausted = true)

        assertEquals(
            windowPresentation(73.0, Severity.HEALTHY, ReadingValidity.CURRENT, PercentDisplay.USED),
            healthy.presentation(ReadingValidity.CURRENT, PercentDisplay.USED),
        )
        assertEquals(
            windowPresentation(15.0, Severity.EXHAUSTED, ReadingValidity.STALE, PercentDisplay.USED),
            exhaustedWithRemainder.presentation(ReadingValidity.STALE, PercentDisplay.USED),
        )
        assertEquals(
            windowPresentation(null, Severity.EXHAUSTED, ReadingValidity.CURRENT, PercentDisplay.USED),
            noFigure.presentation(ReadingValidity.CURRENT, PercentDisplay.USED),
        )
        val p = exhaustedWithRemainder.presentation(ReadingValidity.STALE, PercentDisplay.LEFT)
        assertEquals("15", p.token)
        assertTrue(p.exhausted)
        assertEquals(FillState.STALE, p.fillState)
    }

    // An independent copy of the app row's stale test, so a mistake in `readingValidity` cannot also
    // hide in its oracle.
    private fun referenceStale(window: UsageWindow, stale: Boolean, fetchedAt: Long?, nowMs: Long): Boolean =
        stale || (fetchedAt != null && window.resetElapsedAt(fetchedAt, nowMs))

    @Test
    fun `reading validity agrees with the plain stale-or-reset-elapsed test over every input`() {
        val resetAt = 10_000L
        val windows = listOf(window(usedPercent = 27.0, resetAt = resetAt), window(usedPercent = 27.0, resetAt = null))
        val fetchedAts = listOf(null, 5_000L, 12_000L)
        val nows = listOf(8_000L, 15_000L)
        var resetDueSeen = false
        windows.forEach { w ->
            listOf(false, true).forEach { stale ->
                fetchedAts.forEach { fetchedAt ->
                    nows.forEach { now ->
                        val actual = w.readingValidity(stale, fetchedAt, now)
                        val at = "resetAt=${w.resetAt} stale=$stale fetchedAt=$fetchedAt now=$now"
                        if (referenceStale(w, stale, fetchedAt, now)) {
                            assertEquals(at, if (stale) ReadingValidity.STALE else ReadingValidity.RESET_DUE, actual)
                            if (!stale) resetDueSeen = true
                        } else {
                            assertEquals(at, ReadingValidity.CURRENT, actual)
                        }
                    }
                }
            }
        }
        assertTrue("the sweep never reached RESET_DUE", resetDueSeen)
    }

    @Test
    fun `reading validity names the three outcomes`() {
        val w = window(usedPercent = 27.0, resetAt = 10_000L)
        assertEquals(ReadingValidity.STALE, w.readingValidity(stale = true, fetchedAt = null, nowMs = 8_000L))
        // Stale outranks a passed reset: the failed account's grey is not a reset prompt.
        assertEquals(ReadingValidity.STALE, w.readingValidity(stale = true, fetchedAt = 5_000L, nowMs = 15_000L))
        assertEquals(ReadingValidity.RESET_DUE, w.readingValidity(stale = false, fetchedAt = 5_000L, nowMs = 15_000L))
        // Fetched after the reset, the cache already describes the new window.
        assertEquals(ReadingValidity.CURRENT, w.readingValidity(stale = false, fetchedAt = 12_000L, nowMs = 15_000L))
        assertEquals(ReadingValidity.CURRENT, w.readingValidity(stale = false, fetchedAt = 5_000L, nowMs = 8_000L))
        // No fetch time, as on the lead strip, never reads as reset due.
        assertEquals(ReadingValidity.CURRENT, w.readingValidity(stale = false, fetchedAt = null, nowMs = 15_000L))
    }

    private fun window(usedPercent: Double?, exhausted: Boolean = false, resetAt: Long? = null) = UsageWindow(
        id = "w",
        label = "5h limit",
        category = WindowCategory.FIVE_HOUR,
        usedPercent = usedPercent,
        periodSeconds = null,
        resetAt = resetAt,
        exhausted = exhausted,
    )

    @Test
    fun `the fill state table holds row for row`() {
        vectors().fillStates.forEach { s ->
            assertEquals(
                "${s.name} (${s.validity}, ${s.severity}, ${s.fill}, ${s.mode})",
                s.expected.model,
                fillState(s.validity.model, s.severity.model, s.fill, s.mode.model),
            )
        }
    }
}
