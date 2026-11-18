package com.usagelimits.core.sync

import com.usagelimits.core.model.SharedVectors
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitCooldownVectorsTest {
    @Serializable
    private data class VectorFile(val cases: List<Vector>)

    @Serializable
    private data class Vector(
        val name: String,
        val now: Long,
        val rateLimitedAt: Long,
        val retryAfter: Long?,
        val automatic: Boolean,
        val skip: Boolean,
    )

    @Test fun `automatic reads honor the cooldown shared by both platforms`() {
        val vectors = SharedVectors.parse(
            SharedVectors.file("rate-limit-cooldown.json").readText(),
            VectorFile.serializer(),
        ).cases
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            val deadline = RateLimitCooldown.deadline(vector.rateLimitedAt * 1_000, vector.retryAfter?.times(1_000))
            assertEquals(vector.name, vector.skip, RateLimitCooldown.shouldSkip(vector.automatic, { vector.now * 1_000 }) { deadline })
        }
    }
}
