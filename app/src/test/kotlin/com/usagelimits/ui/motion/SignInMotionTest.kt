package com.usagelimits.ui.motion

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.usagelimits.core.model.ProviderId
import com.usagelimits.core.settings.AppTheme
import com.usagelimits.feature.accounts.AddAccountScreen
import com.usagelimits.feature.accounts.AddAccountState
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.ui.theme.UsageLimitsTheme
import com.usagelimits.ui.theme.colors
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
    private val surface = AppTheme.PURE_BLACK.colors.Surface.argb

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

    private fun probes(before: Bitmap, settled: Bitmap, box: Region): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        var leaving: Pair<Int, Int>? = null
        var arriving: Pair<Int, Int>? = null
        var oldContrast = 0
        var newContrast = 0
        fun contrast(pixel: Int) = kotlin.math.abs((pixel and 255) - (surface and 255)) +
            kotlin.math.abs((pixel shr 8 and 255) - (surface shr 8 and 255)) +
            kotlin.math.abs((pixel shr 16 and 255) - (surface shr 16 and 255))
        for (y in box.top until box.bottom) for (x in box.left until box.right) {
            val old = contrast(before.at(x, y))
            val new = contrast(settled.at(x, y))
            if (old > oldContrast && new == 0) { leaving = x to y; oldContrast = old }
            if (old == 0 && new > newContrast) { arriving = x to y; newContrast = new }
        }
        return checkNotNull(leaving) { "no leaving text probe" } to checkNotNull(arriving) { "no arriving text probe" }
    }

    @Test fun `the next step waits for the leaving card and queued input is rejected`() {
        show(motion = true)
        val box = messageBox()
        val queuedClick = compose.onNodeWithText("Try again").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        FrameRecorder(compose).use { recorder ->
            val before = recorder.frame().cropAndRelease(box)
            changeTo(next)
            val frames = List(20) { index ->
                compose.mainClock.advanceTimeByFrame()
                recorder.frame().also { it.saveFrame("sign-in-fade-through", index) }.cropAndRelease(box)
            }
            val settled = recorder.frame().cropAndRelease(box)
            try {
                val (leaving, arriving) = probes(before, settled, box.atOrigin())
                fun Bitmap.pixel(probe: Pair<Int, Int>) = at(probe.first, probe.second)
                assertFalse("no frame draws both steps", frames.any {
                    it.pixel(leaving) != surface && it.pixel(arriving) != surface
                })
                assertTrue("the old card really fades", frames.any {
                    it.pixel(leaving) != surface && it.pixel(leaving) != before.pixel(leaving)
                })
                assertTrue("the new card really fades", frames.any {
                    it.pixel(arriving) != surface && it.pixel(arriving) != settled.pixel(arriving)
                })
                compose.runOnIdle { queuedClick(); assertEquals(0, retries) }
                compose.onNodeWithText("Try again").assertDoesNotExist()
                compose.onNodeWithText("Done").assertExists()
            } finally {
                before.recycle(); settled.recycle(); frames.forEach(Bitmap::recycle)
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
                assertFalse("the interrupted exit was visibly between its endpoints", leaving.sameAs(full))
                assertFalse("reversing does not jump to full opacity", reversed.sameAs(full))
                assertTrue("reversing keeps the old card, not new text underneath it", reversed.inkOutside(full, box.atOrigin(), surface) == 0)
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
                assertFalse("the entrance was not already settled", entering.sameAs(fullNext))
                assertFalse("an interrupted entrance never jumps to full", interrupted.sameAs(fullNext))
                assertEquals("the old step is not drawn over the exiting new step", 0,
                    interrupted.inkOutside(fullNext, box.atOrigin(), surface))
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
