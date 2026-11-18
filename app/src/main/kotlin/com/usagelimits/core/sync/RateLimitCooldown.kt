package com.usagelimits.core.sync

internal const val RATE_LIMITED_PAUSE_MESSAGE = "Rate limited. Automatic refresh is paused. Refresh manually to try now."

internal object RateLimitCooldown {
    private const val MIN_WAIT_MS = 15 * 60_000L
    private const val MAX_WAIT_MS = 6 * 60 * 60_000L

    fun deadline(nowMs: Long, retryAfterMs: Long?): Long =
        nowMs + (retryAfterMs ?: MIN_WAIT_MS).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)

    inline fun shouldSkip(automatic: Boolean, nowMs: () -> Long, deadline: () -> Long?): Boolean =
        automatic && deadline()?.let { it - nowMs() in 1L..MAX_WAIT_MS } == true
}
