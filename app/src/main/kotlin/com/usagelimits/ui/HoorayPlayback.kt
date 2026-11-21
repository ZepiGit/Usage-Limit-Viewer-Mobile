package com.usagelimits.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

internal const val HOORAY_NANOS = 4_000_000_000L
internal val LocalHoorayPlayback = staticCompositionLocalOf<HoorayPlayback?> { null }

/** One opening's opportunity, and one elapsed presentation shared by every real account slot. */
@Stable
internal class HoorayPlayback {
    var opening by mutableStateOf(0L)
        private set
    private val pending = mutableSetOf<String>()
    private val visible = mutableMapOf<String, Int>()
    private val active = mutableStateMapOf<String, Long>()

    fun open(accounts: Set<String>) {
        stop()
        pending.addAll(accounts)
        opening++
    }

    fun visible(accountId: String) { visible[accountId] = (visible[accountId] ?: 0) + 1 }
    fun hidden(accountId: String) {
        val count = (visible[accountId] ?: 0) - 1
        if (count <= 0) visible.remove(accountId) else visible[accountId] = count
    }

    fun startVisible(frameNanos: Long) {
        for (account in pending) if (account in visible) active[account] = frameNanos
        pending.clear()
    }

    val hasActive: Boolean get() = active.isNotEmpty()
    fun startedAt(accountId: String): Long? = active[accountId]
    fun advance(frameNanos: Long) {
        val iterator = active.entries.iterator()
        while (iterator.hasNext()) if (frameNanos - iterator.next().value >= HOORAY_NANOS) iterator.remove()
    }
    fun stop() { pending.clear(); active.clear() }
}

/** Unseen accounts and plain artwork lose this opening, rather than replaying on a later route. */
@Composable
internal fun HoorayPresentation(playback: HoorayPlayback?, ready: Boolean): Modifier {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val opening = playback?.opening
    var positioned by remember(playback, opening, ready, lifecycle) { mutableStateOf(false) }
    LaunchedEffect(playback, opening, ready, lifecycle, positioned) {
        if (playback != null && ready && positioned && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            // Placement has delivered visibility; its effects enroll before this frame timestamp.
            withFrameNanos { playback.startVisible(it) }
        }
    }
    val playing = playback?.hasActive == true
    LaunchedEffect(playback, playing, lifecycle) {
        if (playback != null && playing && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            while (playback.hasActive) withFrameNanos { playback.advance(it) }
        }
    }
    return Modifier.onGloballyPositioned { positioned = true }
}
