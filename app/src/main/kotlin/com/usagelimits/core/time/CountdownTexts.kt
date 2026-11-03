package com.usagelimits.core.time

/**
 * The words [Countdown] composes its labels from.
 *
 * [Countdown] stays free of Android so it can run in a unit test and inside a widget, which means
 * it cannot reach `Resources`. Handing the words in keeps one code path for the arithmetic and lets
 * each surface choose the language: [EnglishCountdownTexts] is the default every pure caller and
 * test gets, and the app layer supplies a resource-backed implementation.
 *
 * Each function takes the pieces (a count, an already formatted duration or clock time) rather than
 * returning a fragment to concatenate, because word order is not the same in every language.
 */
interface CountdownTexts {
    /** A duration that has run out or is not positive. */
    fun now(): String
    fun daysHours(days: Long, hours: Long): String
    fun days(days: Long): String
    fun hoursMinutes(hours: Long, minutes: Long): String
    fun hours(hours: Long): String
    fun minutes(minutes: Long): String
    fun underOneMinute(): String

    fun resetDue(): String

    /** [duration] is already formatted, e.g. "1h 16m". */
    fun resetIn(duration: String): String

    /** A reset later today: [time] is a localized wall-clock time. */
    fun resetsAt(time: String): String

    /** A reset within the week: [weekday] is the locale's abbreviated weekday. */
    fun resetsOn(weekday: String, time: String): String

    /** A reset beyond the week: [date] is formatted with [resetDatePattern]. */
    fun resetsOnDate(date: String): String

    /** [resetsOn] without its lead-in, for a tile that already says what it is. */
    fun weekdayStamp(weekday: String, time: String): String

    /**
     * The `DateTimeFormatter` pattern for [resetsOnDate]'s date.
     *
     * A pattern rather than a fixed one because the shape of a short date is language-specific:
     * German puts a dot after the day ("9. Okt."), English does not.
     */
    fun resetDatePattern(): String

    fun neverUpdated(): String
    fun updatedJustNow(): String

    /** [duration] is already formatted, e.g. "37m". */
    fun updatedAgo(duration: String): String

    /** [stamp] is a localized time, or date and time when it is not from today. */
    fun asOf(stamp: String): String
}

/** The wording the app shipped with, kept as the default so context-free callers are unchanged. */
object EnglishCountdownTexts : CountdownTexts {
    override fun now() = "now"
    override fun daysHours(days: Long, hours: Long) = "${days}d ${hours}h"
    override fun days(days: Long) = "${days}d"
    override fun hoursMinutes(hours: Long, minutes: Long) = "${hours}h ${minutes}m"
    override fun hours(hours: Long) = "${hours}h"
    override fun minutes(minutes: Long) = "${minutes}m"
    override fun underOneMinute() = "<1m"
    override fun resetDue() = "Reset due"
    override fun resetIn(duration: String) = "Reset in $duration"
    override fun resetsAt(time: String) = "Resets $time"
    override fun resetsOn(weekday: String, time: String) = "Resets $weekday $time"
    override fun resetsOnDate(date: String) = "Resets $date"
    override fun weekdayStamp(weekday: String, time: String) = "$weekday $time"
    override fun resetDatePattern() = "d MMM"
    override fun neverUpdated() = "Never updated"
    override fun updatedJustNow() = "Updated just now"
    override fun updatedAgo(duration: String) = "Updated $duration ago"
    override fun asOf(stamp: String) = "As of $stamp"
}
