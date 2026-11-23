package com.usagelimits.widget

import com.usagelimits.core.model.SharedVectors
import com.usagelimits.core.model.withPercentSign
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetPercentFormatTest {
    @Serializable
    private data class VectorFile(val description: String, val vectors: List<Vector>)

    @Serializable
    private data class Vector(val token: String, val expected: String)

    @Test
    fun `visible widget figures match the shared formatting contract`() {
        val file = SharedVectors.file("widget-percent-format.json")
        val vectors = SharedVectors.parse(file.readText(), VectorFile.serializer()).vectors
        assertEquals(setOf("0", "1", "70", "100", "<1", ">99", "—"), vectors.map { it.token }.toSet())
        for ((token, expected) in vectors) {
            assertEquals("widget token $token", expected, withPercentSign(token))
        }
    }
}
