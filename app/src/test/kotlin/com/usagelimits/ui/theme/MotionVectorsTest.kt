package com.usagelimits.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import com.usagelimits.core.model.SharedVectors
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The motion vocabulary against `shared/test-vectors/motion.json`, which the iOS Kit's
 * `MotionTests` reads too: the same motion lasts the same time on both platforms.
 */
class MotionVectorsTest {
    @Serializable
    private data class VectorFile(val motions: List<Vector>)

    @Serializable
    private data class Vector(val name: String, val durationMs: Int, val delayMs: Int, val platforms: List<String>)

    private val vocabulary: Map<String, (UsageMotion) -> FiniteAnimationSpec<Float>> = mapOf(
        "stateChange" to { it.stateChange() },
        "contentSwap" to { it.contentSwap() },
        "move" to { it.move() },
        "exit" to { it.exit() },
        "enterAfterExit" to { it.enterAfterExit() },
        "moveAfterExit" to { it.moveAfterExit() },
        "enterAfterMove" to { it.enterAfterMove() },
        "launchReveal" to { it.launchReveal() },
        "launchDissolve" to { it.launchDissolve() },
    )

    private fun androidVectors() = SharedVectors.parse(
        SharedVectors.file("motion.json").readText(), VectorFile.serializer(),
    ).motions.filter { "android" in it.platforms }

    @Test
    fun `every motion lasts what the shared vectors say`() {
        val vectors = androidVectors()
        assertEquals(vocabulary.keys, vectors.map { it.name }.toSet())
        for (vector in vectors) {
            val spec = vocabulary.getValue(vector.name)(UsageMotion.Full) as TweenSpec<Float>
            assertEquals(vector.name, vector.durationMs, spec.durationMillis)
            assertEquals(vector.name, vector.delayMs, spec.delay)
        }
    }

    @Test
    fun `reduced motion turns every motion into a snap`() {
        for ((name, spec) in vocabulary) {
            assertTrue(name, spec(UsageMotion.Reduced) is SnapSpec<Float>)
        }
    }
}
