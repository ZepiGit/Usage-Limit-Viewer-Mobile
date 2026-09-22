package com.usagelimits.providers.devin

import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic CPAMC responses only; no live Devin credentials or payloads are used. */
class DevinQuotaParserTest {

    private val payload = JsonSupport.parseObject(
        """
        {
          "userStatus": {
            "planStatus": {
              "planInfo": { "planName": " Pro " },
              "dailyQuotaRemainingPercent": 54,
              "weeklyQuotaRemainingPercent": "77",
              "dailyQuotaResetAtUnix": "1789372800",
              "weeklyQuotaResetAtUnix": 1789891200,
              "planStart": "2026-09-11T00:42:44Z",
              "planEnd": "2026-10-11T00:42:44Z"
            }
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `daily and weekly remaining percentages become consumed windows`() {
        val windows = DevinQuotaParser.parse(payload)

        assertEquals(listOf("devin-daily", "devin-weekly"), windows.map { it.id })
        assertEquals(46.0, windows[0].usedPercent!!, 0.0001)
        assertEquals(23.0, windows[1].usedPercent!!, 0.0001)
        assertEquals(54.0, windows[0].remainingPercent!!, 0.0001)
        assertEquals(77.0, windows[1].remainingPercent!!, 0.0001)
        assertEquals(86_400L, windows[0].periodSeconds)
        assertEquals(604_800L, windows[1].periodSeconds)
        assertEquals(WindowCategory.OTHER, windows[0].category)
        assertEquals(WindowCategory.WEEKLY, windows[1].category)
        assertEquals(1_789_372_800_000L, windows[0].resetAt)
        assertEquals(1_789_891_200_000L, windows[1].resetAt)
        assertFalse(windows[0].exhausted)
        assertFalse(windows[1].exhausted)
    }

    @Test
    fun `plan is read from planInfo and trimmed`() {
        assertEquals("Pro", DevinQuotaParser.parsePlan(payload))
        assertEquals(
            "Starter",
            DevinQuotaParser.parsePlan(JsonSupport.parseObject("""{ "plan": " Starter " }""")),
        )
    }

    @Test
    fun `zero and one hundred remaining values are preserved`() {
        val zero = JsonSupport.parseObject(
            """{ "userStatus": { "planStatus": {
                "dailyQuotaRemainingPercent": 0,
                "weeklyQuotaRemainingPercent": 100
            } } }""",
        )

        val windows = DevinQuotaParser.parse(zero)
        assertEquals(100.0, windows[0].usedPercent!!, 0.0001)
        assertTrue(windows[0].exhausted)
        assertEquals(0.0, windows[1].usedPercent!!, 0.0001)
        assertFalse(windows[1].exhausted)
    }

    @Test
    fun `malformed percentages and reset values stay unknown independently`() {
        val malformed = JsonSupport.parseObject(
            """{ "userStatus": { "planStatus": {
                "dailyQuotaRemainingPercent": 101,
                "dailyQuotaResetAtUnix": "bad",
                "weeklyQuotaRemainingPercent": 42.5,
                "weeklyQuotaResetAtUnix": 1.5
            } } }""",
        )

        val windows = DevinQuotaParser.parse(malformed)
        assertEquals(listOf("devin-weekly"), windows.map { it.id })
        assertEquals(57.5, windows.single().usedPercent!!, 0.0001)
        assertNull(windows.single().resetAt)
    }

    @Test
    fun `missing status and alternate snake case names are handled`() {
        assertTrue(DevinQuotaParser.parse(JsonSupport.parseObject("{}"), 0).isEmpty())

        val snake = JsonSupport.parseObject(
            """{ "user_status": { "plan_status": {
                "plan_info": { "plan_name": "Team" },
                "daily_quota_remaining_percent": 90,
                "daily_quota_reset_at_unix": 1789372800
            } } }""",
        )
        val window = DevinQuotaParser.parse(snake).single()
        assertEquals("devin-daily", window.id)
        assertEquals("Team", DevinQuotaParser.parsePlan(snake))
    }
}
