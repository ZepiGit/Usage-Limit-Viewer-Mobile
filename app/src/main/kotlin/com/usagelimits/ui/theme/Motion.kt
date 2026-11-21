package com.usagelimits.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

val LocalMotionEnabled = staticCompositionLocalOf { true }

/** One observer per theme, removed with its composition; changes apply while the app is open. */
@Composable
internal fun rememberMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun enabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var motionEnabled by remember(resolver) { mutableStateOf(enabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motionEnabled = enabled() }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        motionEnabled = enabled()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return motionEnabled
}

// The only durations in the app, in milliseconds. `shared/test-vectors/motion.json` holds the same
// figures for iOS, and MotionVectorsTest reads it, so the two platforms cannot drift apart.
private const val BRIEF = 90
private const val QUICK = 120
private const val SHORT = 180
private const val MEDIUM = 240
private const val LONG = 320

/** Things that travel or grow: fast out of rest, slow into place. */
private val Standard: Easing = FastOutSlowInEasing
/** Opacity alone: an eased fade reads as a pause at each end. */
private val Fade: Easing = LinearEasing
/** The launch icon settling with a slight overshoot. */
private val Reveal: Easing = EaseOutBack

/** How far a pushed screen shifts along the reading axis while it fades. */
val AxisShift = 24.dp

/**
 * The app's motion vocabulary: every animation takes its timing from here, named by what the
 * motion says rather than by how long it lasts.
 *
 * Reduced motion is decided here and nowhere else: [Reduced] answers every request with a
 * snap, so a call site that uses this vocabulary cannot forget the system setting.
 */
@Immutable
class UsageMotion private constructor(val enabled: Boolean) {

    private fun <T> spec(durationMs: Int, easing: Easing, delayMs: Int = 0): FiniteAnimationSpec<T> =
        if (enabled) tween(durationMs, delayMs, easing) else snap()

    /** A state changing in place: a selection, an icon giving way to a spinner. */
    fun <T> stateChange(): FiniteAnimationSpec<T> = spec(QUICK, Standard)

    /** A crossfade for content that may overlap, unlike a card or a whole screen. */
    fun <T> contentSwap(): FiniteAnimationSpec<T> = spec(SHORT, Fade)

    /** A quantity or a position changing: a bar's fill, a list item's place, a pushed screen. */
    fun <T> move(): FiniteAnimationSpec<T> = spec(MEDIUM, Standard)

    // The parts of a fade-through: what leaves goes first, then the rest moves and the new
    // arrives. Nothing is drawn over something that is still fading out.
    fun <T> exit(): FiniteAnimationSpec<T> = spec(BRIEF, Fade)
    /** The new arrives where the old was: straight after the exit. */
    fun <T> enterAfterExit(): FiniteAnimationSpec<T> = spec(MEDIUM - BRIEF, Fade, delayMs = BRIEF)
    /** Derselbe Eintritt, wenn der Aufrufer den Austritt bereits abgewartet hat. */
    internal fun <T> enterFromHidden(): FiniteAnimationSpec<T> = spec(MEDIUM - BRIEF, Fade)
    fun <T> moveAfterExit(): FiniteAnimationSpec<T> = spec(MEDIUM, Standard, delayMs = BRIEF)
    /**
     * The new arrives where its neighbours have to make room first: it waits for most of their
     * move and ends with it, so it does not fade in underneath them.
     */
    fun <T> enterAfterMove(): FiniteAnimationSpec<T> = spec(MEDIUM - BRIEF, Fade, delayMs = 2 * BRIEF)

    /** What a finger moves away: it fades over the whole of the move, so every position shows it. */
    fun <T> gestureFade(): FiniteAnimationSpec<T> = spec(MEDIUM, Fade)

    /** The launch icon rising, then the overlay dissolving over the content. */
    fun <T> launchReveal(): FiniteAnimationSpec<T> = spec(LONG, Reveal)
    fun <T> launchDissolve(): FiniteAnimationSpec<T> = spec(MEDIUM, Fade)

    companion object {
        val Full = UsageMotion(enabled = true)
        val Reduced = UsageMotion(enabled = false)

        val current: UsageMotion
            @Composable @ReadOnlyComposable get() = if (LocalMotionEnabled.current) Full else Reduced
    }
}

/**
 * A lazy list item in the list's fade-through: items that leave fade out first, then the rest
 * moves, and new items fade in as the move ends.
 */
fun LazyItemScope.itemMotion(motion: UsageMotion): Modifier =
    if (!motion.enabled) Modifier else Modifier.animateItem(
        fadeInSpec = motion.enterAfterMove(),
        placementSpec = motion.moveAfterExit(),
        fadeOutSpec = motion.exit(),
    )

/**
 * Peers trading places (tab to tab): the old screen fades out, then the new one fades in, in
 * place. Two whole screens crossfading read as a double exposure.
 */
fun UsageMotion.swapIn(): EnterTransition = fadeIn(enterAfterExit())
fun UsageMotion.swapOut(): ExitTransition = fadeOut(exit())

/**
 * Going deeper or coming back: the old screen leaves first, the new one arrives shifted by
 * [offsetPx] along the reading axis. Positive comes from or goes to the end side.
 */
fun UsageMotion.axisIn(offsetPx: Int): EnterTransition =
    fadeIn(enterAfterExit()) + slideInHorizontally(move()) { offsetPx }
fun UsageMotion.axisOut(offsetPx: Int): ExitTransition =
    fadeOut(exit()) + slideOutHorizontally(move()) { offsetPx }

/**
 * The back gesture: the finger scrubs through the pop, and may stop anywhere in it. A
 * fade-through held half-way is an almost empty screen (what leaves is gone before what arrives
 * has come), so here the screen below is there from the start, shifted by [offsetPx], and the
 * one on top fades over the whole of the move while it shifts away.
 *
 * With motion off the gesture has no preview at all: the navigation shell takes it away from
 * the library, which would otherwise seek these.
 */
fun UsageMotion.peekIn(offsetPx: Int): EnterTransition = slideInHorizontally(move()) { offsetPx }
fun UsageMotion.peelOut(offsetPx: Int): ExitTransition = fadeOut(gestureFade()) + slideOutHorizontally(move()) { offsetPx }
