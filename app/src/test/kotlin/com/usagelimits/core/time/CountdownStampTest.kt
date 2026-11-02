package com.usagelimits.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The bare stamps tiles use under their own lead-in. They exist so no surface has to cut the
 * word "Resets" or "As of" off a label, which only works in the language the label was written in.
 */
class CountdownStampTest {

    private val now = Instant.parse("2026-09-09T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("UTC")
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun label(resetAt: Long?) = Countdown.absoluteResetLabel(resetAt, now, zone, Locale.US)
    private fun stamp(resetAt: Long?) = Countdown.absoluteResetStamp(resetAt, now, zone, Locale.US)

    @Test
    fun `the reset stamp is the label without its word in every shape`() {
        for (offset in listOf(2 * hour, day + 3 * hour, 3 * day, 20 * day)) {
            val resetAt = now + offset
            assertEquals("offset $offset", label(resetAt)!!.removePrefix("Resets "), stamp(resetAt))
        }
    }

    @Test
    fun `the reset stamp keeps the due word, and nothing for no reset`() {
        assertEquals("Reset due", stamp(now - 1))
        assertEquals(label(now - 1), stamp(now - 1))
        assertNull(stamp(null))
    }

    @Test
    fun `the stamp takes its words from the texts it is given`() {
        val spaced = object : CountdownTexts by EnglishCountdownTexts {
            override fun resetsAt(time: String) = "Zuruecksetzung um $time"
            override fun resetsOn(weekday: String, time: String) = "Zuruecksetzung $weekday $time"
            override fun resetsOnDate(date: String) = "Zuruecksetzung am $date"
            override fun resetDatePattern() = "d. MMM"
        }
        val sameDay = now + 2 * hour
        assertEquals("14:00", Countdown.absoluteResetStamp(sameDay, now, zone, Locale.GERMANY, spaced))
        assertEquals("Zuruecksetzung um 14:00", Countdown.absoluteResetLabel(sameDay, now, zone, Locale.GERMANY, spaced))
        val far = Countdown.absoluteResetStamp(now + 20 * day, now, zone, Locale.GERMANY, spaced)
        assertEquals("the day leads with the texts' own date pattern", true, far!!.startsWith("29. "))
    }

    @Test
    fun `the as-of stamp is the label without its lead-in`() {
        val at = now - 2 * hour
        assertEquals("As of " + Countdown.asOfStamp(at, zone, Locale.US), Countdown.asOfLabel(at, zone, Locale.US))
        val earlier = now - 2 * day
        assertEquals("As of " + Countdown.asOfStamp(earlier, zone, Locale.US, now), Countdown.asOfLabel(earlier, zone, Locale.US, now))
        assertEquals(true, Countdown.asOfStamp(earlier, zone, Locale.US, now).length > Countdown.asOfStamp(earlier, zone, Locale.US).length)
    }
}
