package com.usagelimits.providers.minimax

import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The Token Plan's `model_remains[]`, as MiniMax's own CLI reads it. Every value is synthetic and
 * chosen so the arithmetic of `resolveQuotaCounts` is visible by hand.
 */
class MiniMaxUsageParserTest {

    private fun parse(json: String) = MiniMaxUsageParser.parse(JsonSupport.parseObject(json))

    private val reset = Instant.parse("2026-01-01T05:00:00Z").toEpochMilli()
    private val weeklyReset = Instant.parse("2026-01-08T00:00:00Z").toEpochMilli()

    @Test
    fun `a model yields a five-hour and a weekly window, labelled and scoped to the model`() {
        val windows = parse(
            """{"model_remains":[{"model_name":"general","end_time":1767243600000,
               "current_interval_total_count":100,"current_interval_usage_count":40,
               "current_weekly_total_count":1000,"current_weekly_usage_count":250,
               "weekly_end_time":1767830400000}]}""",
        )

        assertEquals(listOf("general:5h", "general:weekly"), windows.map { it.id })
        assertEquals(listOf("general · 5-hour", "general · Weekly"), windows.map { it.label })
        assertEquals(listOf(WindowCategory.FIVE_HOUR, WindowCategory.WEEKLY), windows.map { it.category })
        assertEquals(listOf(18_000L, 604_800L), windows.map { it.periodSeconds })
        // Counts are what REMAINS until a percentage says otherwise: 40 of 100 spent 60.
        assertEquals(listOf(60.0, 75.0), windows.map { it.usedPercent })
        assertEquals(listOf(reset, weeklyReset), windows.map { it.resetAt })
        assertTrue(windows.all { it.scope == UsageScope.MODEL })
    }

    @Test
    fun `a percentage agreeing with the consumed reading flips the count`() {
        val windows = parse(
            """{"model_remains":[{"model_name":"general",
               "current_interval_total_count":100,"current_interval_usage_count":90,
               "current_interval_remaining_percent":10}]}""",
        )

        assertEquals(90.0, windows.single().usedPercent!!, 1e-9)
    }

    @Test
    fun `a percentage agreeing with the remaining reading keeps the count as it is`() {
        val windows = parse(
            """{"model_remains":[{"model_name":"general",
               "current_interval_total_count":100,"current_interval_usage_count":40,
               "current_interval_remaining_percent":40}]}""",
        )

        assertEquals(60.0, windows.single().usedPercent!!, 1e-9)
    }

    @Test
    fun `a percentage that matches neither reading leaves the window unknown`() {
        val windows = parse(
            """{"model_remains":[{"model_name":"general",
               "current_interval_total_count":100,"current_interval_usage_count":40,
               "current_interval_remaining_percent":50}]}""",
        )

        assertNull(windows.single().usedPercent)
        assertTrue(!windows.single().exhausted)
    }

    @Test
    fun `unusable counts are unknown, never zero`() {
        val windows = parse(
            """{"model_remains":[
               {"model_name":"zero","current_interval_total_count":0,"current_interval_usage_count":0},
               {"model_name":"over","current_interval_total_count":100,"current_interval_usage_count":150},
               {"model_name":"negative","current_interval_total_count":100,"current_interval_usage_count":-1},
               {"model_name":"absent"}]}""",
        )

        assertEquals(listOf("zero:5h", "over:5h", "negative:5h", "absent:5h"), windows.map { it.id })
        assertTrue(windows.all { it.usedPercent == null && !it.exhausted })
    }

    @Test
    fun `status 2 is exhausted and status 3 is an unlimited window with no week`() {
        val windows = parse(
            """{"model_remains":[
               {"model_name":"spent","current_interval_total_count":100,"current_interval_usage_count":0,
                "current_interval_status":2,"current_weekly_total_count":100,
                "current_weekly_usage_count":0,"current_weekly_status":2},
               {"model_name":"unlimited","current_interval_total_count":100,"current_interval_usage_count":100,
                "current_interval_status":3,"current_weekly_total_count":500,
                "current_weekly_usage_count":500,"current_weekly_status":3}]}""",
        )

        assertEquals(listOf("spent:5h", "spent:weekly", "unlimited:5h"), windows.map { it.id })
        assertEquals(100.0, windows[0].usedPercent!!, 1e-9)
        assertTrue(windows[0].exhausted)
        assertEquals(100.0, windows[1].usedPercent!!, 1e-9)
        assertNull(windows[2].usedPercent)
        assertTrue(!windows[2].exhausted)
    }

    @Test
    fun `a zero weekly total states no weekly allowance`() {
        val windows = parse(
            """{"model_remains":[{"model_name":"general","current_interval_total_count":100,
               "current_interval_usage_count":25,"current_weekly_total_count":0,
               "current_weekly_usage_count":0,"current_weekly_status":1}]}""",
        )

        assertEquals(listOf("general:5h"), windows.map { it.id })
    }

    @Test
    fun `an entry without a model name yields nothing and an empty payload no windows`() {
        assertEquals(emptyList<Any>(), parse("""{"model_remains":[{"current_interval_total_count":10}]}"""))
        assertEquals(emptyList<Any>(), parse("""{}"""))
    }

    @Test
    fun `equal model names keep separate stable ids`() {
        val windows = parse(
            """{"model_remains":[
               {"model_name":"general","current_interval_total_count":10,"current_interval_usage_count":1},
               {"model_name":"general","current_interval_total_count":10,"current_interval_usage_count":2}]}""",
        )

        assertEquals(listOf("general:5h", "general:5h:2"), windows.map { it.id })
    }
}
