package com.usagelimits.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import kotlin.coroutines.coroutineContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.usagelimits.core.model.ClaudeCodeMood
import com.usagelimits.ui.theme.LocalMotionEnabled
import com.usagelimits.ui.theme.UsageColors

/** The person's "Animate Claude Code icon" choice; the system's reduced motion stills the figure as well. */
val LocalAnimateClaudeCodeIcon = staticCompositionLocalOf { true }

/**
 * The app slots that show the Claude Code figure: its size there and how far the whole figure
 * moves (CH-3). The slot keeps its layout; the figure is centred on it and may draw past it.
 */
enum class FigureSlot(val size: Dp, val amplitude: Float) {
    LIST_ROW(28.dp, 0.7f),
    LIST_PREVIEW(20.dp, 0.7f),
    DIAL(34.dp, 0.7f),
    DIAL_PREVIEW(16.dp, 0.6f),
    DETAIL(36.dp, 0.8f),
    RESETS(28.dp, 0.7f),
    SETTINGS_ROW(28.dp, 0.7f),
}

/** The figure's fill for this icon choice, or null when the choice keeps its static artwork. */
@Composable
internal fun ProviderIconChoice.claudeCodeFill(): Color? = when (id) {
    "claudecode-color" -> ClaudeCodeBrand
    "claudecode" -> UsageColors.Ink
    else -> null
}

/**
 * The Claude Code figure in [mood], drawn in [slot]'s size at the centre of the space [modifier]
 * gives it. It moves only while the person and the system allow motion, its screen is RESUMED and
 * it is on screen; otherwise it shows the mood's still pose. [contentDescription] is what the
 * static logo in the same place says, so swapping one for the other changes nothing spoken.
 *
 * The loop invalidates drawing only: its time is read in the draw phase, at most 30 times a second,
 * and every path and paint is built once per size and mood.
 */
@Composable
internal fun ClaudeCodeFigure(
    mood: ClaudeCodeMood,
    fill: Color,
    slot: FigureSlot,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val animate = LocalMotionEnabled.current && LocalAnimateClaudeCodeIcon.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var visible by remember { mutableStateOf(false) }
    val clock = remember { FigureClock() }
    val running = animate && visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(running, mood) {
        clock.time = STILL
        if (running) clock.run()
    }
    val sizePx = with(LocalDensity.current) { slot.size.toPx() }
    val art = remember(mood, sizePx) { ClaudeCodeArt(mood, sizePx) }
    val sleepInk = UsageColors.Muted
    Spacer(
        modifier
            .then(if (contentDescription == null) Modifier else Modifier.semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            })
            .onVisibilityChanged { visible = it }
            .drawBehind {
                val origin = Offset((size.width - sizePx) / 2f, (size.height - sizePx) / 2f)
                art.draw(this, fill, sleepInk, origin, clock.time, slot.amplitude)
            },
    )
}

/**
 * The time source of one figure: nanoseconds since its loop started, or [STILL]. Written at most
 * once per [FRAME_NANOS], so a 60 or 120 Hz display redraws the figure 30 times a second.
 */
@Stable
private class FigureClock {
    var time by mutableLongStateOf(STILL)

    private var start = 0L
    private var durationScale: MotionDurationScale? = null
    private val onFrame: (Long) -> Unit = { now ->
        // Android's animator duration scale stretches the loop like every other animation; at 0
        // the figure never gets here (LocalMotionEnabled).
        val scale = durationScale?.scaleFactor?.takeIf { it > 0f } ?: 1f
        // The cap counts display time, so a scale below 1 does not raise the redraw rate.
        val frame = (now - start) / FRAME_NANOS * FRAME_NANOS
        val loop = (frame / scale.toDouble()).toLong()
        if (loop != time) time = loop
    }

    /**
     * Runs until cancelled. An infinite animation by the platform's rules: a Compose test that lets
     * its clock run on its own does not run it, and the figure keeps its still pose there.
     */
    suspend fun run() {
        durationScale = coroutineContext[MotionDurationScale]
        start = withInfiniteAnimationFrameNanos { it }
        time = 0L
        while (true) withInfiniteAnimationFrameNanos(onFrame)
    }

    private companion object {
        /**
         * 30 frames a second: half the redraws of a 60 Hz display, a quarter of a 120 Hz one. The
         * shortest loop (the Working arms, 0.24 s) still gets about seven frames per cycle.
         */
        const val FRAME_NANOS = 1_000_000_000L / 30
    }
}
