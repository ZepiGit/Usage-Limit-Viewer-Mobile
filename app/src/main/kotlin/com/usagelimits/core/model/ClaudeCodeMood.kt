package com.usagelimits.core.model

/**
 * What the Claude Code figure shows. The first five are steady moods read from the account's
 * headline; [HOORAY] is never read from a reading: Resets always shows it, and a confirmed
 * reset plays it once on the next opening.
 */
enum class ClaudeCodeMood { ASLEEP, TIRED, WORKING, IDLE, DANCING, HOORAY }

/**
 * The steady mood of this account at [nowMs]: the headline of ADR 0002, then its last reported
 * raw percent left. A provider-declared exhausted headline is Asleep whatever figure it carries,
 * as it ranks at zero in Overview. No headline, or a figure that is missing or out of range, is
 * Idle. The display mode, the age of the reading and an expired sign-in play no part: an old
 * reading speaks with the figure it had, and a passed reset is not evidence of a refill.
 *
 * `ClaudeCodeMoodVectorsTest` proves this against `shared/test-vectors/claude-code-mood.json`,
 * which the Swift suite reads too.
 */
fun UsageSnapshot?.claudeCodeMoodAt(nowMs: Long): ClaudeCodeMood {
    val headline = this?.headlineAt(nowMs) ?: return ClaudeCodeMood.IDLE
    return claudeCodeMood(headline.remainingPercent, headline.exhausted)
}

/** Also used by placed widgets, whose headline has already been projected at presentation time. */
internal fun claudeCodeMood(remainingPercent: Double?, exhausted: Boolean): ClaudeCodeMood {
    if (exhausted) return ClaudeCodeMood.ASLEEP
    val left = remainingPercent ?: return ClaudeCodeMood.IDLE
    return when {
        left <= 0.0 -> ClaudeCodeMood.ASLEEP
        left <= 20.0 -> ClaudeCodeMood.TIRED
        left <= 50.0 -> ClaudeCodeMood.WORKING
        left <= 90.0 -> ClaudeCodeMood.IDLE
        else -> ClaudeCodeMood.DANCING
    }
}
