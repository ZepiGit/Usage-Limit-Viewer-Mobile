package com.usagelimits.providers.ollama

import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ollama Cloud's `GET /api/balance` as usage windows. All values synthetic. */
class OllamaUsageParserTest {

    private fun parse(json: String) = OllamaUsageParser.parse(JsonSupport.parseObject(json))

    @Test
    fun `the documented payload is one included-allowance window and no purchased window`() {
        val windows = parse(
            """{"included":{"balance_usd":72.5,"allowance_usd":100,
               "period":{"from":"2026-09-15T09:30:00Z","until":"2026-10-15T09:30:00Z"}},
               "purchased":{"balance_usd":25}}""",
        )

        val window = windows.single()
        assertEquals("included", window.id)
        assertEquals("Included allowance", window.label)
        assertEquals(WindowCategory.OTHER, window.category)
        // The allowance has spent 27.5 of its 100 dollars.
        assertEquals(27.5, window.usedPercent!!, 1e-9)
        assertEquals(java.time.Instant.parse("2026-10-15T09:30:00Z").toEpochMilli(), window.resetAt)
        assertNull("the doc states no fixed duration for the included period", window.periodSeconds)
        assertTrue(!window.exhausted)
    }

    @Test
    fun `a legacy plan states remaining percentages for its session and weekly limits`() {
        val windows = parse(
            """{"included":{"session":{"remaining_percent":75,"resets_at":"2026-10-01T07:00:00Z"},
               "weekly":{"remaining_percent":40,"resets_at":"2026-10-05T00:00:00Z"}},
               "purchased":{"balance_usd":25}}""",
        )

        assertEquals(listOf("session", "weekly"), windows.map { it.id })
        assertEquals(listOf("Session", "Weekly"), windows.map { it.label })
        assertEquals(listOf(WindowCategory.OTHER, WindowCategory.WEEKLY), windows.map { it.category })
        assertEquals(listOf(25.0, 60.0), windows.map { it.usedPercent })
        // No duration is documented for the session limit; a week is a week.
        assertEquals(listOf(null, 7L * 86_400), windows.map { it.periodSeconds })
    }

    @Test
    fun `a payload without the included block reads as no window at all`() {
        assertTrue(parse("""{"purchased":{"balance_usd":12.5}}""").isEmpty())
        assertTrue(parse("""{"included":{}}""").isEmpty())
        assertTrue(parse("""{"included":null}""").isEmpty())
    }

    @Test
    fun `a null period keeps the window and invents no reset`() {
        val window = parse("""{"included":{"balance_usd":90,"allowance_usd":100,"period":null}}""").single()

        assertEquals(10.0, window.usedPercent!!, 1e-9)
        assertNull(window.resetAt)
    }

    @Test
    fun `an out-of-range or missing remaining percent leaves that window unknown`() {
        val windows = parse(
            """{"included":{"session":{"remaining_percent":120,"resets_at":"2026-10-01T07:00:00Z"},
               "weekly":{"remaining_percent":-1}}}""",
        )

        assertEquals(listOf("session", "weekly"), windows.map { it.id })
        assertTrue(windows.all { it.usedPercent == null && !it.exhausted })
        // The reset is a fact about the window, so it survives an unreadable percentage.
        assertEquals(java.time.Instant.parse("2026-10-01T07:00:00Z").toEpochMilli(), windows[0].resetAt)

        assertNull(parse("""{"included":{"session":{}}}""").single().usedPercent)
    }

    @Test
    fun `an empty or missing allowance omits the included window instead of reading zero`() {
        assertTrue(parse("""{"included":{"balance_usd":0,"allowance_usd":0}}""").isEmpty())
        assertTrue(parse("""{"included":{"balance_usd":10}}""").isEmpty())
        assertTrue(parse("""{"included":{"allowance_usd":100}}""").isEmpty())
    }

    @Test
    fun `the included share is computed in exact decimals, not binary floats`() {
        // As a Double, 0.3 - 0.1 is 0.19999999999999998, and the share then reads
        // 66.66666666666667 used. In decimals it is 0.2 over 0.3: 66.666667 at six places with
        // HALF_EVEN, the scale and rounding Credits.kt's remainingPercent uses.
        val window = parse("""{"included":{"balance_usd":0.1,"allowance_usd":0.3}}""").single()

        assertEquals(66.666667, window.usedPercent!!, 1e-9)
    }

    @Test
    fun `the included share is clamped and a fully spent allowance is exhausted`() {
        // A balance above the allowance cannot have spent less than nothing.
        assertEquals(0.0, parse("""{"included":{"balance_usd":150,"allowance_usd":100}}""")[0].usedPercent!!, 1e-9)
        val spent = parse("""{"included":{"balance_usd":0,"allowance_usd":40}}""").single()
        assertEquals(100.0, spent.usedPercent!!, 1e-9)
        assertTrue(spent.exhausted)
    }
}
