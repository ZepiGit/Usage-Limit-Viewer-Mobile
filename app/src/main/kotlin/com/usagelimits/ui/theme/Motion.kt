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
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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

    /** One content replacing another in the same place: tabs, sign-in steps, a figure. */
    fun <T> contentSwap(): FiniteAnimationSpec<T> = spec(SHORT, Fade)

    /** A quantity or a position changing: a bar's fill, a list item's place, a pushed screen. */
    fun <T> move(): FiniteAnimationSpec<T> = spec(MEDIUM, Standard)

    // The parts of a fade-through: what leaves goes first, then the rest moves and the new
    // arrives. Nothing is drawn over something that is still fading out.
    fun <T> exit(): FiniteAnimationSpec<T> = spec(BRIEF, Fade)
    /** The new arrives where the old was: straight after the exit. */
    fun <T> enterAfterExit(): FiniteAnimationSpec<T> = spec(MEDIUM - BRIEF, Fade, delayMs = BRIEF)
    fun <T> moveAfterExit(): FiniteAnimationSpec<T> = spec(MEDIUM, Standard, delayMs = BRIEF)
    /**
     * The new arrives where its neighbours have to make room first: it waits for most of their
     * move and ends with it, so it does not fade in underneath them.
     */
    fun <T> enterAfterMove(): FiniteAnimationSpec<T> = spec(MEDIUM - BRIEF, Fade, delayMs = 2 * BRIEF)

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
 *
 * [sequenced] false starts the move at once: under a finger dragging a card, a neighbour that
 * waits reads as lag.
 */
fun LazyItemScope.itemMotion(motion: UsageMotion, sequenced: Boolean = true): Modifier =
    if (!motion.enabled) Modifier else Modifier.animateItem(
        fadeInSpec = motion.enterAfterMove(),
        placementSpec = if (sequenced) motion.moveAfterExit() else motion.move(),
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
 * Navigation chrome leaving with the screen it belongs to and returning with it.
 *
 * The bottom bar shifts by [offsetPx], the distance the screens shift, without giving up its
 * height until it is gone: the content above it is anchored to the top, so nothing visible
 * depends on that height and no frame of the motion costs a layout pass. (Sliding by its whole
 * height would carry its labels through the system's navigation buttons.) The side rail does
 * change the width the centred content sits in, so it gives its width back gradually.
 */
fun UsageMotion.barIn(offsetPx: Int): EnterTransition = fadeIn(enterAfterExit()) + slideInVertically(move()) { offsetPx }
fun UsageMotion.barOut(offsetPx: Int): ExitTransition = fadeOut(exit()) + slideOutVertically(move()) { offsetPx }
fun UsageMotion.railIn(): EnterTransition = fadeIn(enterAfterExit()) + expandHorizontally(move())
fun UsageMotion.railOut(): ExitTransition = fadeOut(exit()) + shrinkHorizontally(move())
