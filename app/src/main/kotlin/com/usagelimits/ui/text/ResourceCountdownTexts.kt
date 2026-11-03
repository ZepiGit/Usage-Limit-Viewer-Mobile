package com.usagelimits.ui.text

import android.content.res.Resources
import com.usagelimits.R
import com.usagelimits.core.time.CountdownTexts

/**
 * [CountdownTexts] read from `strings.xml`, so the language follows the resources' configuration.
 *
 * Takes [Resources] rather than a Context so a caller that has only the former (a worker, a Glance
 * provider) can use it, and so a test can hand it resources with a locale qualifier applied.
 */
class ResourceCountdownTexts(private val resources: Resources) : CountdownTexts {
    override fun now(): String = resources.getString(R.string.countdown_now)

    override fun daysHours(days: Long, hours: Long): String =
        resources.getString(R.string.countdown_days_hours, days, hours)

    override fun days(days: Long): String = resources.getString(R.string.countdown_days, days)

    override fun hoursMinutes(hours: Long, minutes: Long): String =
        resources.getString(R.string.countdown_hours_minutes, hours, minutes)

    override fun hours(hours: Long): String = resources.getString(R.string.countdown_hours, hours)

    override fun minutes(minutes: Long): String =
        resources.getString(R.string.countdown_minutes, minutes)

    override fun underOneMinute(): String = resources.getString(R.string.countdown_under_one_minute)

    override fun resetDue(): String = resources.getString(R.string.countdown_reset_due)

    override fun resetIn(duration: String): String =
        resources.getString(R.string.countdown_reset_in, duration)

    override fun resetsAt(time: String): String =
        resources.getString(R.string.countdown_resets_at, time)

    override fun resetsOn(weekday: String, time: String): String =
        resources.getString(R.string.countdown_resets_on, weekday, time)

    override fun resetsOnDate(date: String): String =
        resources.getString(R.string.countdown_resets_on_date, date)

    override fun weekdayStamp(weekday: String, time: String): String =
        resources.getString(R.string.countdown_weekday_stamp, weekday, time)

    override fun resetDatePattern(): String = resources.getString(R.string.countdown_reset_date_pattern)

    override fun neverUpdated(): String = resources.getString(R.string.countdown_never_updated)

    override fun updatedJustNow(): String = resources.getString(R.string.countdown_updated_just_now)

    override fun updatedAgo(duration: String): String =
        resources.getString(R.string.countdown_updated_ago, duration)

    override fun asOf(stamp: String): String = resources.getString(R.string.countdown_as_of, stamp)
}
