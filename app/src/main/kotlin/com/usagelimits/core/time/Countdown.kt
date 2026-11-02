package com.usagelimits.core.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Countdown and staleness formatting.
 *
 * Kept free of Android APIs so it is unit-testable and behaves identically on the main screen
 * and inside a widget, where a Composable context may not be available. `java.time` is fine
 * here: `minSdk` is 26, which is where it landed natively.
 */
object Countdown {

    /**
     * "1h 16m", "5d 2h", "now".
     *
     * Resolution drops as the distance grows — days-and-hours past a day, hours-and-minutes
     * below that — which is what the reference design shows and keeps the text narrow enough
     * for a widget row.
     *
     * The examples throughout are the English [texts]; the arithmetic is the same in any language.
     */
    fun format(remainingMs: Long, texts: CountdownTexts = EnglishCountdownTexts): String {
        if (remainingMs <= 0) return texts.now()
        val totalMinutes = remainingMs / 60_000
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 && hours > 0 -> texts.daysHours(days, hours)
            days > 0 -> texts.days(days)
            hours > 0 && minutes > 0 -> texts.hoursMinutes(hours, minutes)
            hours > 0 -> texts.hours(hours)
            minutes > 0 -> texts.minutes(minutes)
            else -> texts.underOneMinute()
        }
    }

    /**
     * "Reset in 1h 16m", or "Reset due" once the instant has passed.
     *
     * For a surface that re-renders on a clock. A widget does not — see [absoluteResetLabel].
     */
    fun resetLabel(
        resetAt: Long?,
        nowMs: Long,
        texts: CountdownTexts = EnglishCountdownTexts,
    ): String? {
        if (resetAt == null) return null
        val remaining = resetAt - nowMs
        return if (remaining <= 0) texts.resetDue() else texts.resetIn(format(remaining, texts))
    }

    /**
     * "Resets 14:05", "Resets Tue 09:00", "Resets 16 Sep".
     *
     * The form a surface that cannot tick must use. A Glance widget recomposes only when its
     * worker updates it, so a relative countdown is frozen at composition: rendered as
     * "Reset in 20m" and read twenty-five minutes later, it is not merely stale but wrong in
     * the one direction that matters, because the limit has already reset and the user is
     * still being told to wait. An absolute instant stays true however old the render is.
     *
     * The day is included beyond today because a bare clock time cannot say whether a weekly
     * limit returns this evening or next Tuesday, and dropped within today because that is the
     * common case and the date would only be noise.
     */
    fun absoluteResetLabel(
        resetAt: Long?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        texts: CountdownTexts = EnglishCountdownTexts,
    ): String? = resetMoment(resetAt, nowMs, zone, locale, texts, bare = false)

    /**
     * [absoluteResetLabel] without its "Resets" word: "14:05", "Tue 09:00", "16 Sep", or "Reset
     * due" once the instant has passed.
     *
     * For a tile that already says what it is ("Next reset") and has no width for the word again.
     * Built from the same pieces rather than by cutting a prefix off the label, because the word
     * and its position differ per language.
     */
    fun absoluteResetStamp(
        resetAt: Long?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        texts: CountdownTexts = EnglishCountdownTexts,
    ): String? = resetMoment(resetAt, nowMs, zone, locale, texts, bare = true)

    private fun resetMoment(
        resetAt: Long?,
        nowMs: Long,
        zone: ZoneId,
        locale: Locale,
        texts: CountdownTexts,
        bare: Boolean,
    ): String? {
        if (resetAt == null) return null
        if (resetAt <= nowMs) return texts.resetDue()

        val reset = Instant.ofEpochMilli(resetAt).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, reset.toLocalDate())

        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .format(reset)

        return when {
            days == 0L -> if (bare) time else texts.resetsAt(time)
            // Inside a week the weekday is the most readable anchor; beyond it the day of the
            // month is, because "Tue" stops being unambiguous once more than one has passed.
            days in 1..6 -> {
                val weekday = DateTimeFormatter.ofPattern("EEE", locale).format(reset)
                if (bare) "$weekday $time" else texts.resetsOn(weekday, time)
            }
            else -> {
                val date = DateTimeFormatter.ofPattern(texts.resetDatePattern(), locale).format(reset)
                if (bare) date else texts.resetsOnDate(date)
            }
        }
    }

    /** "Updated just now" / "Updated 37m ago" — for a surface that re-renders on a clock. */
    fun freshnessLabel(
        fetchedAt: Long?,
        nowMs: Long,
        texts: CountdownTexts = EnglishCountdownTexts,
    ): String {
        if (fetchedAt == null) return texts.neverUpdated()
        val age = nowMs - fetchedAt
        if (age < 60_000) return texts.updatedJustNow()
        return texts.updatedAgo(format(age, texts))
    }

    /**
     * "As of 12:40" — the widget's freshness line.
     *
     * Same reasoning as [absoluteResetLabel] and the same defect avoided: "Updated 2m ago"
     * composed once and left on the home screen for three hours claims the numbers are two
     * minutes old for as long as the widget is not refreshed. A wall-clock stamp cannot age
     * into a lie, and it is what lets a reader judge the numbers for themselves.
     */
    fun asOfLabel(
        fetchedAt: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        /**
         * When supplied, a stamp that is not from today carries its date. A time alone is read
         * as today's — "09:15" on a Wednesday afternoon means this morning to anyone looking —
         * and a widget left on the home screen shows exactly such a stamp for days.
         */
        nowMs: Long? = null,
        texts: CountdownTexts = EnglishCountdownTexts,
    ): String =
        if (fetchedAt == null) texts.neverUpdated()
        else texts.asOf(asOfStamp(fetchedAt, zone, locale, nowMs))

    /**
     * The stamp inside [asOfLabel]: "12:40", or date and time when [nowMs] says it is not from
     * today. For a line that already carries its own lead-in and cannot repeat "As of".
     */
    fun asOfStamp(
        fetchedAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        nowMs: Long? = null,
    ): String {
        val at = Instant.ofEpochMilli(fetchedAt).atZone(zone)
        val sameDay = nowMs == null ||
            Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate() == at.toLocalDate()
        val style = if (sameDay) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        } else {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT)
        }
        return style.withLocale(locale).format(at)
    }
}
