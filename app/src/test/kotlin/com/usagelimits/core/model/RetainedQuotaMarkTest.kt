package com.usagelimits.core.model

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedQuotaMarkTest {
    @Serializable private data class VectorFile(val description: String, val vectors: List<Vector>)
    @Serializable private data class Vector(
        val name: String, val status: String, val windows: List<Window>,
        val expectedMark: String, val expectedValidity: String, val expectedHeadline: String,
    )
    @Serializable private data class Window(
        val id: String, val scope: String, val remainingPercent: Double?, val exhausted: Boolean,
    )

    @Test
    fun `failed retained windows keep limited mark and failed validity`() {
        val vectors = SharedVectors.parse(SharedVectors.file("retained-quota-mark.json").readText(),
            VectorFile.serializer()).vectors
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            val windows = vector.windows.map {
                UsageWindow(id = it.id, label = it.id, category = WindowCategory.WEEKLY,
                    usedPercent = it.remainingPercent?.let { value -> 100 - value },
                    periodSeconds = null, resetAt = null, exhausted = it.exhausted,
                    scope = UsageScope.valueOf(it.scope.uppercase()))
            }
            val snapshot = UsageSnapshot("account", 1_800_000_000_000L,
                SnapshotStatus.valueOf(vector.status.uppercase()), windows)
            assertEquals(vector.name, vector.expectedMark, snapshot.quotaSeverity.name.lowercase())
            assertEquals(vector.name, vector.expectedValidity, snapshot.severity.name.lowercase())
            assertEquals(vector.name, vector.expectedHeadline, snapshot.headlineAt(1_800_000_000_000L)?.id)
        }
    }
}
