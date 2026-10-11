package com.usagelimits.core.model

import com.usagelimits.core.network.JsonSupport
import com.usagelimits.providers.claude.ClaudeUsageParser
import com.usagelimits.providers.xai.XaiBillingParser
import org.junit.Assert.*
import org.junit.Test

/** Synthetic quota traces; no live account values or credentials. */
class QuotaPolicyTest {
    private fun snapshot(windows: List<UsageWindow>) = UsageSnapshot("synthetic", 1L, SnapshotStatus.OK, windows)

    @Test fun `Claude model exhaustion does not claim the global allowance is exhausted`() {
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject("""
            {"limits":[
              {"kind":"session","percent":0},
              {"kind":"weekly_all","percent":63},
              {"kind":"weekly_scoped","percent":100,"scope":{"model":{"display_name":"Fable"}}}
            ]}
        """), 1L)
        val result = snapshot(windows)
        assertEquals("LIMITED", result.severity.name)
        assertEquals("seven-day", result.mostCritical?.id)
        assertEquals(37.0, result.mostCritical!!.remainingPercent!!, 0.0)
        assertEquals(0.0, windows.last().remainingPercent!!, 0.0)
    }

    @Test fun `a real global five hour blocker remains exhausted despite available weekly quota`() {
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject("""
            {"limits":[{"kind":"session","percent":100},{"kind":"weekly_all","percent":20}]}
        """), 1L)
        assertEquals(Severity.EXHAUSTED, snapshot(windows).severity)
        assertEquals("five-hour", snapshot(windows).mostCritical?.id)
    }

    @Test fun `unknown monthly billing cannot turn known weekly eighty remaining into Error`() {
        val windows = XaiBillingParser.parseBilling(JsonSupport.parseObject("""
            {"creditUsagePercent":20,"usagePeriodType":"weekly","monthlyLimit":1000}
        """), 1L)
        val result = snapshot(windows)
        assertEquals(Severity.HEALTHY, result.severity)
        assertEquals(80.0, result.mostCritical!!.remainingPercent!!, 0.0)
        assertNull(windows.single { it.id == "xai-monthly" }.remainingPercent)
    }

    @Test fun `a successful response with only unknown usage is unknown rather than failed`() {
        val windows = XaiBillingParser.parseBilling(JsonSupport.parseObject("""{"monthlyLimit":1000}"""), 1L)
        assertEquals("UNKNOWN", snapshot(windows).severity.name)
        assertEquals(Severity.ERROR, snapshot(windows).copy(status = SnapshotStatus.FAILED).severity)
    }

    @Test fun `percentage labels never round small positive quota to exhausted or nearly full to full`() {
        assertEquals("<1%", percentLabel(0.1))
        assertEquals("<1%", percentLabel(0.9))
        assertEquals(">99%", percentLabel(99.9))
        assertEquals("0%", percentLabel(0.0))
        assertEquals("100%", percentLabel(100.0))
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 101.0)) {
            assertEquals("—", percentLabel(value))
        }
    }
    @Test fun `all independent model pools are exhausted when a binding window in each is exhausted`() {
        fun row(id: String, group: String, used: Double) = UsageWindow(id, id, WindowCategory.OTHER, used, null, null, false, group, UsageScope.MODEL)
        val result = snapshot(listOf(row("a-short", "a", 0.0), row("a-long", "a", 100.0), row("b-short", "b", 0.0), row("b-long", "b", 100.0)))
        assertEquals(Severity.EXHAUSTED, result.severity)
    }

    @Test fun `explicit positive percentage and blocking flag remain visibly contradictory`() {
        val row = UsageWindow("global", "Global", WindowCategory.OTHER, 20.0, null, null, true)
        assertTrue(row.hasConflictingAvailability)
        assertEquals(80.0, row.remainingPercent!!, 0.0)
        assertEquals(Severity.EXHAUSTED, snapshot(listOf(row)).severity)
    }

}
