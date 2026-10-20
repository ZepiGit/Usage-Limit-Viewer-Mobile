package com.usagelimits.providers.opencode

import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OpenCode Go usage shape from docs/providers-opencode.md. All values synthetic. */
class OpenCodeUsageParserTest {

    private fun parse(json: String) = OpenCodeUsageParser.parse(JsonSupport.parseObject(json))

    @Test
    fun `the documented example reads as 5h, weekly and monthly`() {
        val windows = parse(
            """{"usage":{
              "rolling":{"status":"ok","percent":98,"resetsAt":"2026-10-07T22:03:52.000Z"},
              "weekly":{"status":"ok","percent":39,"resetsAt":"2026-10-12T00:00:00.000Z"},
              "monthly":{"status":"ok","percent":19,"resetsAt":"2026-10-22T18:11:46.000Z"}}}""",
        )

        assertEquals(listOf("opencode-rolling", "opencode-weekly", "opencode-monthly"), windows.map { it.id })
        assertEquals(listOf("5h limit", "Weekly", "Monthly"), windows.map { it.label })
        assertEquals(
            listOf(WindowCategory.FIVE_HOUR, WindowCategory.WEEKLY, WindowCategory.MONTHLY),
            windows.map { it.category },
        )
        assertEquals(listOf(98.0, 39.0, 19.0), windows.map { it.usedPercent })
        assertEquals(java.time.Instant.parse("2026-10-07T22:03:52Z").toEpochMilli(), windows[0].resetAt)
    }

    @Test
    fun `any status but ok leaves the window unknown`() {
        val windows = parse(
            """{"usage":{"rolling":{"status":"degraded","percent":40,"resetsAt":"2026-10-07T22:03:52.000Z"},
                "weekly":{"status":"OK","percent":10}}}""",
        )

        assertNull(windows[0].usedPercent)
        assertTrue(!windows[0].exhausted)
        // Case-insensitive ok, and a window with no reset keeps a null reset.
        assertEquals(10.0, windows[1].usedPercent!!, 0.0)
        assertNull(windows[1].resetAt)
    }

    @Test
    fun `out of range or missing percent is unknown and absent windows are omitted`() {
        val windows = parse("""{"usage":{"rolling":{"status":"ok","percent":120},"monthly":{"status":"ok"}}}""")

        assertEquals(listOf("opencode-rolling", "opencode-monthly"), windows.map { it.id })
        assertTrue(windows.all { it.usedPercent == null })
    }

    @Test
    fun `a full window is exhausted`() {
        assertTrue(parse("""{"usage":{"weekly":{"status":"ok","percent":100}}}""").single().exhausted)
    }
}
