package com.usagelimits.ui.motion

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.usagelimits.core.model.ProviderId
import com.usagelimits.feature.accounts.AddAccountScreen
import com.usagelimits.feature.accounts.AddAccountState
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.ui.theme.UsageLimitsTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h700dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignInMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val old = AddAccountState.Failed(ProviderId.KIMI, "Previous sign-in explanation")
    private val next = AddAccountState.Success(ProviderId.KIMI, "New account")
    private var state by mutableStateOf<AddAccountState>(old)
    private var retries = 0

    private fun show(motion: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageLimitsTheme {
                CompositionLocalProvider(LocalMotionEnabled provides motion) {
                    AddAccountScreen(
                        state = state, providers = listOf(ProviderId.KIMI),
                        onStart = { retries++ }, onCancel = {}, onDone = {}, onSubmitApiKey = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun changeTo(value: AddAccountState) = compose.change { state = value }

    private fun messageBox() = compose.onNodeWithText(old.message).fetchSemanticsNode().boundsInWindow.toRegion()

    // The crop starts in the card padding, just above and left of the message. The entire
    // card fades, so its painted fill (not the theme's opaque surface) is bare in each frame.
    private val Bitmap.cardFill: Int get() = at(0, 0)

    private fun probes(before: Bitmap, settled: Bitmap): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        var leaving: Pair<Int, Int>? = null
        var arriving: Pair<Int, Int>? = null
        var oldContrast = 0
        var newContrast = 0
        fun contrast(pixel: Int, fill: Int) = abs((pixel and 255) - (fill and 255)) +
            abs((pixel shr 8 and 255) - (fill shr 8 and 255)) +
            abs((pixel shr 16 and 255) - (fill shr 16 and 255))
        val oldFill = before.cardFill
        val newFill = settled.cardFill
        for (y in 0 until before.height) for (x in 0 until before.width) {
            val old = contrast(before.at(x, y), oldFill)
            val new = contrast(settled.at(x, y), newFill)
            if (old > oldContrast && new == 0) { leaving = x to y; oldContrast = old }
            if (old == 0 && new > newContrast) { arriving = x to y; newContrast = new }
        }
        return checkNotNull(leaving) { "no leaving text probe" } to checkNotNull(arriving) { "no arriving text probe" }
    }

    private fun Bitmap.inkOutsideCardText(reference: Bitmap): Int {
        val fill = cardFill
        val referenceFill = reference.cardFill
        var count = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (at(x, y) != fill && reference.at(x, y) == referenceFill) count++
        }
        return count
    }

    @Test fun `the next step waits for the leaving card and queued input is rejected`() {
        show(motion = true)
        val box = messageBox()
        val queuedClick = compose.onNodeWithText("Try again").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        FrameRecorder(compose).use { recorder ->
            val before = recorder.frame().cropAndRelease(box)
            val frames = ArrayList<Bitmap>(21)
            try {
                changeTo(next)
                repeat(20) { index ->
                    compose.mainClock.advanceTimeByFrame()
                    val frame = recorder.frame().also { it.saveFrame("sign-in-fade-through", index) }.cropAndRelease(box)
                    frames += frame
                    if (index == 2) {
                        // Dispatch at 48 ms while the faded Retry node is still attached but hidden from semantics.
                        assertTrue("the leaving card is still visibly fading",
                            frame.cardFill != before.cardFill && frame.ink(box.atOrigin(), frame.cardFill) > 0)
                        compose.onNodeWithText("Try again").assertDoesNotExist()
                        compose.runOnIdle { assertTrue("the queued action was dispatched", queuedClick()) }
                    }
                }
                val settled = recorder.frame().cropAndRelease(box).also { frames += it }
                val transition = frames.subList(0, frames.lastIndex)
                val (leaving, arriving) = probes(before, settled)
                fun Bitmap.pixel(probe: Pair<Int, Int>) = at(probe.first, probe.second)
                assertFalse("no frame draws both steps", transition.any {
                    it.pixel(leaving) != it.cardFill && it.pixel(arriving) != it.cardFill
                })
                assertTrue("the old card really fades", transition.any {
                    it.pixel(leaving) != it.cardFill && it.pixel(leaving) != before.pixel(leaving)
                })
                assertTrue("the new card really fades", transition.any {
                    it.pixel(arriving) != it.cardFill && it.pixel(arriving) != settled.pixel(arriving)
                })
                compose.runOnIdle { assertEquals(0, retries) }
                compose.onNodeWithText("Try again").assertDoesNotExist()
                compose.onNodeWithText("Done").assertExists()
            } finally {
                before.recycle(); frames.forEach(Bitmap::recycle)
            }
        }
    }

    @Test fun `reversing an exit resumes the same card from its current opacity`() {
        show(motion = true)
        val box = messageBox()
        FrameRecorder(compose).use { recorder ->
            val full = recorder.frame().cropAndRelease(box)
            changeTo(next)
            compose.mainClock.advanceTimeBy(48)
            val leaving = recorder.frame().cropAndRelease(box)
            compose.onNodeWithText("Try again").assertDoesNotExist()
            changeTo(old)
            compose.mainClock.advanceTimeByFrame()
            val reversed = recorder.frame().cropAndRelease(box)
            compose.mainClock.advanceTimeBy(1_000)
            val settled = recorder.frame().cropAndRelease(box)
            try {
                assertTrue("the interrupted exit still shows text", leaving.ink(box.atOrigin(), leaving.cardFill) > 0)
                assertFalse("the interrupted exit was visibly between its endpoints", leaving.sameAs(full))
                assertTrue("reversing still shows text", reversed.ink(box.atOrigin(), reversed.cardFill) > 0)
                assertFalse("reversing does not jump to full opacity", reversed.sameAs(full))
                assertEquals("reversing keeps the old card, not new text underneath it", 0,
                    reversed.inkOutsideCardText(full))
                assertTrue("the old card returns exactly", settled.sameAs(full))
                compose.onNodeWithText("Try again").assertExists()
                compose.onNodeWithText("Done").assertDoesNotExist()
            } finally {
                full.recycle(); leaving.recycle(); reversed.recycle(); settled.recycle()
            }
        }
    }

    @Test fun `interrupting an entrance fades its current card before returning`() {
        show(motion = true)
        val box = messageBox()
        FrameRecorder(compose).use { recorder ->
            changeTo(next)
            compose.mainClock.advanceTimeBy(160)
            val entering = recorder.frame().cropAndRelease(box)
            changeTo(old)
            compose.mainClock.advanceTimeByFrame()
            val interrupted = recorder.frame().cropAndRelease(box)
            compose.mainClock.advanceTimeBy(1_000)
            val returned = recorder.frame().cropAndRelease(box)
            changeTo(next)
            compose.mainClock.advanceTimeBy(1_000)
            val fullNext = recorder.frame().cropAndRelease(box)
            try {
                assertTrue("the entrance already shows text", entering.ink(box.atOrigin(), entering.cardFill) > 0)
                assertFalse("the entrance was not already settled", entering.sameAs(fullNext))
                assertTrue("an interrupted entrance still shows text", interrupted.ink(box.atOrigin(), interrupted.cardFill) > 0)
                assertFalse("an interrupted entrance never jumps to full", interrupted.sameAs(fullNext))
                assertEquals("the old step is not drawn over the exiting new step", 0,
                    interrupted.inkOutsideCardText(fullNext))
                assertFalse("the requested old step eventually returns", returned.sameAs(fullNext))
            } finally {
                entering.recycle(); interrupted.recycle(); returned.recycle(); fullNext.recycle()
            }
        }
    }

    @Test fun `reduced motion cuts the whole sign-in screen on its next frame`() {
        show(motion = false)
        FrameRecorder(compose).use { recorder ->
            changeTo(next)
            compose.mainClock.advanceTimeByFrame()
            val first = recorder.frame()
            compose.mainClock.advanceTimeBy(1_000)
            val settled = recorder.frame()
            try {
                assertTrue("no delayed card or control movement", first.sameAs(settled))
                compose.onNodeWithText("Try again").assertDoesNotExist()
                compose.onNodeWithText("Done").assertExists()
            } finally {
                first.recycle(); settled.recycle()
            }
        }
    }

    private fun Bitmap.cropAndRelease(box: Region): Bitmap = try { crop(box) } finally { recycle() }
}
