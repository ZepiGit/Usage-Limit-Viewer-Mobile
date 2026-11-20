package com.usagelimits.navigation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/**
 * How often relative times refresh.
 *
 * Countdowns are rendered at minute resolution, so a shorter tick would recompose without
 * changing a single character; a longer one would let "1m" sit on screen after it became "now".
 */
internal const val TICK_INTERVAL_MS = 30_000L

/**
 * The shell's clock: the instant and zone Overview ranks, filters and heads its list for, and the
 * instant every relative time in the shell is computed for. (Resets keeps its own zone.)
 *
 * It moves at the next countdown tick, and earlier at [boundary] when that comes first (a reset
 * falling due, a reading turning stale, the next local day), so Overview reorders at the moment
 * a reset passes rather than up to a tick later. It is read again whenever the app comes back to
 * the foreground and when the system reports a time, date or zone change, without a network
 * read. Reading the instant once and passing it down keeps every time on screen consistent.
 */
@Stable
internal class ScreenClock(
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    var nowMs by mutableLongStateOf(now())
        private set
    var zoneId: ZoneId by mutableStateOf(zone())
        private set

    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Re-reads the clock and the zone at once, and starts the wait over from there. */
    fun reread() {
        nowMs = now()
        zoneId = zone()
        wake.trySend(Unit)
    }

    /**
     * Runs until cancelled: waits until the next tick or [boundary], whichever is sooner, then
     * reads the clock again. [reread] ends the wait early and plans from there (a new reading or a
     * new stale threshold can move the boundary while the loop waits). [boundary] is asked afresh
     * each round, and the delay is measured from the time read at that moment, not from when the
     * round's clock value was taken.
     */
    suspend fun run(boundary: (nowMs: Long, zone: ZoneId) -> Long?) {
        while (true) {
            val at = now()
            val next = boundary(at, zoneId)?.takeIf { it > at }
            // Measured from now, after the boundary was found: finding it takes time too.
            val wait = if (next == null) TICK_INTERVAL_MS else minOf(TICK_INTERVAL_MS, (next - now()).coerceAtLeast(0))
            // A reread already moved the clock; a timeout reaches a tick or a boundary and moves it.
            if (withTimeoutOrNull(wait) { wake.receive() } == null) {
                nowMs = now()
                zoneId = zone()
            }
        }
    }

    /**
     * [run] while [lifecycle] is at least STARTED: no tick behind a stopped activity, and on every
     * start the clock is read at once and the next wake-up planned from there.
     */
    suspend fun runWhileStarted(lifecycle: Lifecycle, boundary: (nowMs: Long, zone: ZoneId) -> Long?) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            reread()
            run(boundary)
        }
    }
}

/**
 * A [ScreenClock] for this composition. Its loop runs only while the lifecycle is STARTED, so it
 * does not tick behind a stopped activity; on every start it reads the clock and plans again.
 * Time, date and zone changes re-read it too.
 */
@Composable
internal fun rememberScreenClock(boundary: (nowMs: Long, zone: ZoneId) -> Long?): ScreenClock {
    val clock = remember { ScreenClock() }
    val currentBoundary by rememberUpdatedState(boundary)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, clock) { clock.runWhileStarted(lifecycle) { at, zone -> currentBoundary(at, zone) } }

    val context = LocalContext.current
    DisposableEffect(context, clock) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = clock.reread()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        // System broadcasts only; nothing outside the system can tell the app its clock moved.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return clock
}
