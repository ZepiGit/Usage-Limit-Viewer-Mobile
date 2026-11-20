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
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
 * The one clock the screens read: the instant and zone every relative time, rank, filter and
 * header on screen is computed for.
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
     * Runs until cancelled: waits until the next tick or [boundary], whichever is sooner, or
     * until [reread]; then reads the clock again. [boundary] is asked afresh each round, so a new
     * reading or a passed reset moves the next wake-up.
     */
    suspend fun run(boundary: (nowMs: Long, zone: ZoneId) -> Long?) {
        while (true) {
            val at = nowMs
            val next = boundary(at, zoneId)?.takeIf { it > at }
            val wait = if (next == null) TICK_INTERVAL_MS else minOf(TICK_INTERVAL_MS, next - at)
            withTimeoutOrNull(wait) { wake.receive() }
            nowMs = now()
            zoneId = zone()
        }
    }
}

/** A [ScreenClock] for this composition, re-read on foreground return and on time changes. */
@Composable
internal fun rememberScreenClock(boundary: (nowMs: Long, zone: ZoneId) -> Long?): ScreenClock {
    val clock = remember { ScreenClock() }
    val currentBoundary by rememberUpdatedState(boundary)
    LaunchedEffect(clock) { clock.run { at, zone -> currentBoundary(at, zone) } }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, clock) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) clock.reread() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

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
