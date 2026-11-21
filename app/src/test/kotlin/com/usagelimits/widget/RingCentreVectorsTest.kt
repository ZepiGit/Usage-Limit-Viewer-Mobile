package com.usagelimits.widget

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.usagelimits.core.model.FillState
import com.usagelimits.core.model.PercentDisplay
import com.usagelimits.core.model.ReadingValidity
import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.SharedVectors
import com.usagelimits.core.model.windowPresentation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a ring writes in its centre (#38), proven against the vector file the Swift suite reads too.
 *
 * The rule is the shared part; the arc each surface paints is not. The vectors therefore state
 * whether the arc is drawn, and the Android-only cases below pin what the Glance ring paints.
 * The loader is strict for the reason `HeadlineVectorsTest` gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingCentreVectorsTest {

    @Serializable
    private data class VectorFile(val description: String, val vectors: List<Vector>)

    @Serializable
    private data class Vector(
        val name: String,
        val token: String,
        val side: Side,
        val arcDrawn: Boolean,
        val expected: Expected,
    )

    @Serializable
    private enum class Side(val mode: PercentDisplay) {
        @SerialName("left") LEFT(PercentDisplay.LEFT),
        @SerialName("used") USED(PercentDisplay.USED),
    }

    @Serializable
    private enum class Expected(val centre: RingCentre) {
        @SerialName("signed") SIGNED(RingCentre.SIGNED),
        @SerialName("bare") BARE(RingCentre.BARE),
        @SerialName("nothing") NOTHING(RingCentre.NOTHING),
    }

    /** Strict: an unknown key fails rather than being skipped. Every field is required. */
    private val json = Json { ignoreUnknownKeys = false }

    private fun parse(text: String): List<Vector> {
        val vectors = json.decodeFromString(VectorFile.serializer(), text).vectors
        require(vectors.isNotEmpty()) { "the ring centre vector file holds no vectors" }
        return vectors
    }

    private val file = SharedVectors.file("ring-centre.json")

    @Test
    fun `every shared vector writes the expected centre`() {
        for (vector in parse(file.readText())) {
            assertEquals(vector.name, vector.expected.centre, ringCentre(vector.token, vector.side.mode, vector.arcDrawn))
        }
    }

    @Test
    fun `the vector file covers every case the rule names`() {
        // A floor, not a count to keep in step: a file truncated by a bad merge cannot pass.
        assertTrue(parse(file.readText()).size >= 13)
    }

    @Test
    fun `a renamed key fails the suite instead of skipping the vector`() {
        val renamed = """
            {"description": "", "vectors": [{"name": "x", "token": "100", "side": "left",
              "arc": true, "expected": "nothing"}]}
        """.trimIndent()
        assertThrows(SerializationException::class.java) { parse(renamed) }
    }

    @Test
    fun `a missing key fails the suite`() {
        val missing = """
            {"description": "", "vectors": [{"name": "x", "token": "100", "side": "left", "expected": "nothing"}]}
        """.trimIndent()
        assertThrows(SerializationException::class.java) { parse(missing) }
    }

    @Test
    fun `an empty vector list fails the suite`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"description": "", "vectors": []}""")
        }
    }

    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    /** A Left reading of [remaining] whose raw severity and validity are given. */
    private fun left(remaining: Double?, severity: Severity, validity: ReadingValidity) =
        windowPresentation(remaining, severity, validity, PercentDisplay.LEFT)

    @Test
    fun `a full allowance writes nothing in every validity because the Glance ring always paints its fill`() {
        // Android's ring paints the presentation's fill whatever its fill state says, so even the
        // cases the presentation marks "no fill" (a provider-exhausted window, a failed reading)
        // close the ring here, and the rule's arc-drawn branch applies to all five.
        val cases = mapOf(
            "current" to left(100.0, Severity.HEALTHY, ReadingValidity.CURRENT),
            "exhausted" to left(100.0, Severity.EXHAUSTED, ReadingValidity.CURRENT),
            "stale" to left(100.0, Severity.HEALTHY, ReadingValidity.STALE),
            "reset due" to left(100.0, Severity.HEALTHY, ReadingValidity.RESET_DUE),
            "error" to left(100.0, Severity.HEALTHY, ReadingValidity.ERROR),
        )
        for ((name, p) in cases) {
            assertEquals("$name: the presentation fills the whole ring", 1.0, p.fill)
            assertNull("$name: nothing in the centre", ringCentreFigure(resources, p))
        }
        assertEquals("exhausted reads no fill", FillState.NO_FILL, cases.getValue("exhausted").fillState)
        assertEquals("error reads no fill", FillState.NO_FILL, cases.getValue("error").fillState)
    }

    @Test
    fun `the Android ring centre writes signed, bare and no figure`() {
        assertEquals("42%", ringCentreFigure(resources, left(42.0, Severity.HEALTHY, ReadingValidity.CURRENT)))
        assertEquals("100", ringCentreFigure(resources,
            windowPresentation(null, Severity.EXHAUSTED, ReadingValidity.CURRENT, PercentDisplay.USED)))
        // The unknown dash keeps Android's ring treatment: nothing is written.
        assertNull(ringCentreFigure(resources, left(null, Severity.UNKNOWN, ReadingValidity.CURRENT)))
    }
}
