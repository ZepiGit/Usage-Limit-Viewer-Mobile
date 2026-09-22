package com.usagelimits.providers.meta

import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic Meta/Muse responses; no account or credential data is used. */
class MetaQuotaParserTest {

    private val now = 1_757_400_000_000L

    @Test
    fun `rolling and weekly windows report consumed percentages and unix second resets`() {
        val payload = JsonSupport.parseObject(
                """
                {
                  "subs_tier_name": "Muse Code Everyday Usage",
                  "is_subs_active": true,
                  "subs_usage": {
                    "window": {
                      "used_percent": 2.5,
                      "window_duration_mins": 300,
                      "resets_at": 1789678120
                    },
                    "weekly": {
                      "used_percent": 0,
                      "resets_at": 1789948800
                    }
                  }
                }
                """.trimIndent(),
            )
        val windows = MetaQuotaParser.parse(
            payload,
            now,
        )

        assertEquals("Muse Code Everyday Usage", MetaQuotaParser.parsePlan(payload))
        assertEquals(listOf("meta-window", "meta-weekly"), windows.map { it.id })
        val rolling = windows[0]
        assertEquals("5h limit", rolling.label)
        assertEquals(2.5, rolling.usedPercent!!, 1e-9)
        assertEquals(97.5, rolling.remainingPercent!!, 1e-9)
        assertEquals(18_000L, rolling.periodSeconds)
        assertEquals(WindowCategory.FIVE_HOUR, rolling.category)
        assertEquals(1_789_678_120_000L, rolling.resetAt)
        assertFalse(rolling.exhausted)

        val weekly = windows[1]
        assertEquals("Weekly", weekly.label)
        assertEquals(0.0, weekly.usedPercent!!, 1e-9)
        assertEquals(WindowCategory.WEEKLY, weekly.category)
        assertEquals(1_789_948_800_000L, weekly.resetAt)
    }

    @Test
    fun `arbitrary rolling duration is preserved and classified as other`() {
        val windows = MetaQuotaParser.parse(
            JsonSupport.parseObject(
                """
                { "subs_usage": { "window": {
                    "used_percent": 150,
                    "window_duration_mins": 90,
                    "resets_at": "1789678120"
                } } }
                """.trimIndent(),
            ),
            now,
        )
        assertEquals(2, windows.size)
        val window = windows.first()

        assertEquals("90 min limit", window.label)
        assertEquals(100.0, window.usedPercent!!, 1e-9)
        assertEquals(0.0, window.remainingPercent!!, 1e-9)
        assertEquals(5_400L, window.periodSeconds)
        assertEquals(WindowCategory.OTHER, window.category)
        assertTrue(window.exhausted)
    }

    @Test
    fun `missing figures remain unknown instead of becoming zero quota`() {
        val windows = MetaQuotaParser.parse(
            JsonSupport.parseObject(
                """
                { "subscription_usage": { "subscription": {
                    "window": {}, "weekly": { "used_percent": "not-a-number" }
                } } }
                """.trimIndent(),
            ),
            now,
        )

        assertEquals(2, windows.size)
        assertNull(windows[0].usedPercent)
        assertNull(windows[0].resetAt)
        assertNull(windows[0].periodSeconds)
        assertEquals(WindowCategory.OTHER, windows[0].category)
        assertNull(windows[1].usedPercent)
        assertEquals(WindowCategory.WEEKLY, windows[1].category)
    }

    @Test
    fun `a valid object without usage yields unknown windows rather than zero`() {
        val windows = MetaQuotaParser.parse(
            JsonSupport.parseObject("{ \"api_key\": \"synthetic\" }"),
            now,
        )

        assertEquals(listOf("meta-window", "meta-weekly"), windows.map { it.id })
        assertNull(windows[0].usedPercent)
        assertNull(windows[1].usedPercent)
    }
}
