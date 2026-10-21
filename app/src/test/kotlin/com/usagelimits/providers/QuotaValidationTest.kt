package com.usagelimits.providers

import com.usagelimits.core.model.Severity
import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageScope
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.network.JsonSupport
import com.usagelimits.providers.antigravity.AntigravityQuotaParser
import com.usagelimits.providers.codex.CodexUsageParser
import com.usagelimits.providers.kimi.KimiUsageParser
import com.usagelimits.providers.meta.MetaQuotaParser
import org.junit.Assert.*
import org.junit.Test

/** Synthetic wire values, including invalid scales; no live account responses. */
class QuotaValidationTest {
    @Test fun `invalid fractions remain unknown without hiding the bucket`() {
        for (value in listOf("-0.2", "1.4", "80", "null", "\"invalid\"")) {
            val window = AntigravityQuotaParser.parseRows(JsonSupport.parseObject("""
                {"groups":[{"displayName":"Pool","buckets":[
                  {"bucketId":"weekly","window":"weekly","remainingFraction":$value}
                ]}]}
            """)).map { it.window }.single()
            assertNull("fraction $value", window.remainingPercent)
            assertEquals(Severity.UNKNOWN, window.severity)
            assertEquals(UsageScope.MODEL, window.scope)
        }
    }

    @Test fun `invalid Meta percentages cannot become full or exhausted quota`() {
        for (value in listOf("-1", "101", "null", "\"NaN\"")) {
            val window = MetaQuotaParser.parse(JsonSupport.parseObject("""
                {"subs_usage":{"window":{"used_percent":$value}}}
            """), 0).first()
            assertNull("used $value", window.remainingPercent)
            assertFalse(window.exhausted)
        }
    }

    @Test fun `invalid Kimi counts stay unknown in their named window`() {
        for (counts in listOf(
            "\"limit\":100,\"used\":-1", "\"limit\":100,\"used\":101",
            "\"limit\":100,\"remaining\":101", "\"limit\":100,\"remaining\":-1",
            "\"limit\":0,\"used\":5", "\"used\":5",
        )) {
            val window = KimiUsageParser.parse(JsonSupport.parseObject("""{"usage":{$counts}}""")).single()
            assertNull(counts, window.remainingPercent)
            assertEquals(Severity.UNKNOWN, window.severity)
        }
    }

    @Test fun `an exhausted code review pool does not exhaust ordinary Codex usage`() {
        val windows = CodexUsageParser.parse(JsonSupport.parseObject("""
            {"rate_limit":{"primary_window":{"used_percent":20,"limit_window_seconds":18000}},
             "code_review_rate_limit":{"primary_window":{"used_percent":100,"limit_window_seconds":604800}}}
        """), 0)
        val snapshot = UsageSnapshot("synthetic", 0, SnapshotStatus.OK, windows)
        assertEquals(80.0, snapshot.mostCritical!!.remainingPercent!!, 0.0)
        assertEquals(Severity.LIMITED, snapshot.severity)
        assertEquals(UsageScope.MODEL, windows.last().scope)
    }

    @Test fun `a family block explained by one Codex window does not exhaust its sibling`() {
        val windows = CodexUsageParser.parse(JsonSupport.parseObject("""
            {"rate_limit":{"limit_reached":true,
              "primary_window":{"used_percent":100,"limit_window_seconds":18000},
              "secondary_window":{"used_percent":20,"limit_window_seconds":604800}}}
        """), 0)
        assertTrue(windows.first().exhausted)
        assertFalse(windows.last().exhausted)
        assertEquals(80.0, windows.last().remainingPercent!!, 0.0)
    }

    @Test fun `unexplained provider block stays visible alongside positive Codex figures`() {
        val windows = CodexUsageParser.parse(JsonSupport.parseObject("""
            {"rate_limit":{"allowed":false,
              "primary_window":{"used_percent":20,"limit_window_seconds":18000}}}
        """), 0)
        assertEquals(80.0, windows.single().remainingPercent!!, 0.0)
        assertTrue(windows.single().exhausted)
        assertEquals(Severity.EXHAUSTED, UsageSnapshot("synthetic", 0, SnapshotStatus.OK, windows).severity)
    }
}
