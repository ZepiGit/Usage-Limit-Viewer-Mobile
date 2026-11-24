package com.usagelimits.core.time

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.ui.text.ResourceCountdownTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The same countdown cases as [CountdownTest], read through the German resources (R4).
 *
 * The shapes are decided by the resources and the glossary, not by the formatter: "Std." and
 * "Min." are abbreviations and so never take a plural, and the weekday and date come from the
 * locale's own `DateTimeFormatter`, so they are German because the locale is, not because a
 * word was hard-coded here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
class CountdownGermanTest {

    private val texts = ResourceCountdownTexts(
        ApplicationProvider.getApplicationContext<Context>().resources,
    )

    private val now = Instant.parse("2026-09-09T12:00:00Z").toEpochMilli()
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val utc = ZoneId.of("UTC")
    private val de = Locale.GERMANY

    @Test
    fun `durations use the compact German units`() {
        assertEquals("5 T. 2 Std.", Countdown.format(5 * day + 2 * hour, texts))
        assertEquals("3 T.", Countdown.format(3 * day, texts))
        assertEquals("3 T.", Countdown.format(3 * day + 45 * minute, texts))
        assertEquals("1 Std. 16 Min.", Countdown.format(hour + 16 * minute, texts))
        assertEquals("2 Std.", Countdown.format(2 * hour, texts))
        assertEquals("45 Min.", Countdown.format(45 * minute, texts))
        assertEquals("<1 Min.", Countdown.format(30_000L, texts))
        assertEquals("jetzt", Countdown.format(0L, texts))
        assertEquals("jetzt", Countdown.format(-5 * day, texts))
    }

    @Test
    fun `a reset counts down or is due`() {
        assertEquals("Reset in 1 Std. 16 Min.", Countdown.resetLabel(now + hour + 16 * minute, now, texts))
        assertEquals("Reset in 5 T. 2 Std.", Countdown.resetLabel(now + 5 * day + 2 * hour, now, texts))
        assertEquals("Reset fällig", Countdown.resetLabel(now, now, texts))
        assertEquals("Reset fällig", Countdown.resetLabel(now - 3 * hour, now, texts))
        assertNull(Countdown.resetLabel(null, now, texts))
    }

    @Test
    fun `freshness says how long ago, just now, or never`() {
        assertEquals("Nie aktualisiert", Countdown.freshnessLabel(null, now, texts))
        assertEquals("Gerade aktualisiert", Countdown.freshnessLabel(now - 59_999L, now, texts))
        assertEquals("Aktualisiert vor 37 Min.", Countdown.freshnessLabel(now - 37 * minute, now, texts))
        assertEquals("Aktualisiert vor 2 Std. 5 Min.", Countdown.freshnessLabel(now - 2 * hour - 5 * minute, now, texts))
        assertEquals("Aktualisiert vor 3 T.", Countdown.freshnessLabel(now - 3 * day, now, texts))
    }

    @Test
    fun `an absolute reset names the clock time, the weekday or the date`() {
        assertEquals(
            "Reset 14:05",
            Countdown.absoluteResetLabel(Instant.parse("2026-09-09T14:05:00Z").toEpochMilli(), now, utc, de, texts),
        )
        assertEquals(
            "Reset Mo. 09:00",
            Countdown.absoluteResetLabel(Instant.parse("2026-09-14T09:00:00Z").toEpochMilli(), now, utc, de, texts),
        )
        assertEquals(
            "Reset 9. Okt.",
            Countdown.absoluteResetLabel(Instant.parse("2026-10-09T09:00:00Z").toEpochMilli(), now, utc, de, texts),
        )
        assertEquals("Reset fällig", Countdown.absoluteResetLabel(now - minute, now, utc, de, texts))
        assertNull(Countdown.absoluteResetLabel(null, now, utc, de, texts))
    }

    @Test
    fun `the as-of stamp reads Stand with the German clock`() {
        val fetchedAt = Instant.parse("2026-09-09T11:23:00Z").toEpochMilli()
        assertEquals("Stand 11:23", Countdown.asOfLabel(fetchedAt, utc, de, texts = texts))
        assertEquals("Stand 11:23", Countdown.asOfLabel(fetchedAt, utc, de, now, texts))
        assertEquals("Nie aktualisiert", Countdown.asOfLabel(null, utc, de, texts = texts))
    }

    @Test
    fun `an as-of stamp from another day carries its German date`() {
        val fetchedAt = Instant.parse("2026-09-08T11:23:00Z").toEpochMilli()
        assertEquals("Stand 08.09.26, 11:23", Countdown.asOfLabel(fetchedAt, utc, de, now, texts))
    }
}
