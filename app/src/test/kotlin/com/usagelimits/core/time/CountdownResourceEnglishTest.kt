package com.usagelimits.core.time

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.usagelimits.ui.text.ResourceCountdownTexts
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Moving the words into resources must not move a single English character (R4).
 *
 * [CountdownTest] pins the English strings against [EnglishCountdownTexts], the default pure code
 * and widgets without a Context rely on. This sweep pins the resource-backed twin to it, so the two
 * cannot drift: every duration shape, every state, and the three absolute forms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class CountdownResourceEnglishTest {

    private val resources = ResourceCountdownTexts(
        ApplicationProvider.getApplicationContext<Context>().resources,
    )

    private val now = Instant.parse("2026-09-09T12:00:00Z").toEpochMilli()
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val utc = ZoneId.of("UTC")
    private val uk = Locale.UK

    private val durations: List<Long> = buildList {
        addAll(listOf(-5 * day, -1L, 0L, 1L, 30_000L, 59_999L))
        // Every minute across the first two hours, then a stride across the days that crosses
        // each unit boundary: the shapes change at 1 minute, 1 hour and 1 day.
        addAll((1L..130L).map { it * minute })
        addAll((1L..(9 * 24 * 60 / 7)).map { it * 7 * minute })
        addAll(listOf(day, day + hour, day + 45 * minute, 3 * day, 3 * day + 45 * minute, 23 * hour + 59 * minute))
    }

    @Test
    fun `every duration reads the same from resources as from the default`() {
        for (ms in durations) {
            assertEquals("format($ms)", Countdown.format(ms), Countdown.format(ms, resources))
        }
    }

    @Test
    fun `every relative label reads the same from resources as from the default`() {
        for (ms in durations) {
            assertEquals("resetLabel($ms)", Countdown.resetLabel(now + ms, now), Countdown.resetLabel(now + ms, now, resources))
            assertEquals("freshness($ms)", Countdown.freshnessLabel(now - ms, now), Countdown.freshnessLabel(now - ms, now, resources))
        }
        assertEquals(Countdown.resetLabel(null, now), Countdown.resetLabel(null, now, resources))
        assertEquals(Countdown.freshnessLabel(null, now), Countdown.freshnessLabel(null, now, resources))
    }

    @Test
    fun `every absolute label reads the same from resources as from the default`() {
        val instants = listOf(
            "2026-09-09T11:59:00Z", "2026-09-09T14:05:00Z", "2026-09-09T23:59:00Z",
            "2026-09-10T00:01:00Z", "2026-09-14T09:00:00Z", "2026-09-15T09:00:00Z",
            "2026-10-09T09:00:00Z", "2027-01-02T09:00:00Z",
        ).map { Instant.parse(it).toEpochMilli() }
        for (at in instants) {
            assertEquals(
                "absoluteResetLabel($at)",
                Countdown.absoluteResetLabel(at, now, utc, uk),
                Countdown.absoluteResetLabel(at, now, utc, uk, resources),
            )
            assertEquals(
                "asOf($at)",
                Countdown.asOfLabel(at, utc, uk, now),
                Countdown.asOfLabel(at, utc, uk, now, resources),
            )
            assertEquals(
                "asOf($at) without now",
                Countdown.asOfLabel(at, utc, uk),
                Countdown.asOfLabel(at, utc, uk, texts = resources),
            )
        }
        assertEquals(Countdown.asOfLabel(null, utc, uk), Countdown.asOfLabel(null, utc, uk, texts = resources))
    }
}
