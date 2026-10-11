package com.usagelimits.core.model

import com.usagelimits.core.network.JsonSupport
import com.usagelimits.providers.claude.ClaudeUsageParser
import com.usagelimits.providers.xai.XaiBillingParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Synthetic quota contracts, using APIs shared by the baseline and corrected revisions.
 * These payloads reconstruct the reported numbers; they are not captured provider responses.
 * Dates are synthetic, preserving the reported 25-day Fable reset interval exactly.
 */
class QuotaScreenshotRegressionTest {
    private val now = Instant.parse("2026-10-11T00:00:00Z").toEpochMilli()

    private fun snapshot(windows: List<UsageWindow>) =
        UsageSnapshot("synthetic-quota-contract", now, SnapshotStatus.OK, windows)

    private fun billing(raw: String) =
        XaiBillingParser.parseBilling(JsonSupport.parseObject(raw), now)

    private fun credits(raw: String) =
        XaiBillingParser.parseCredits(JsonSupport.parseObject(raw), now)

    private fun assertRemaining(expected: Double, window: UsageWindow) {
        assertNotNull("${window.id} must retain its measured percentage", window.remainingPercent)
        assertEquals(expected, window.remainingPercent!!, 0.0)
    }

    @Test
    fun `Claude 100 37 and Fable zero retain the 25 day reset without global exhaustion`() {
        val windows = ClaudeUsageParser.parse(JsonSupport.parseObject("""
            {"limits":[
              {"kind":"session","percent":0,"resets_at":"2026-10-11T05:00:00Z"},
              {"kind":"weekly_all","percent":63,"resets_at":"2026-10-18T00:00:00Z"},
              {"kind":"weekly_scoped","percent":100,"resets_at":"2026-11-05T00:00:00Z",
               "scope":{"model":{"display_name":"Fable"}}}
            ]}
        """), now)

        assertEquals(3, windows.size)
        assertRemaining(100.0, windows.single { it.id == "five-hour" })
        assertRemaining(37.0, windows.single { it.id == "seven-day" })
        val fable = windows.single { it.id == "seven-day-fable" }
        assertRemaining(0.0, fable)
        assertEquals("Weekly (Fable)", fable.label)
        assertEquals(Instant.parse("2026-11-05T00:00:00Z").toEpochMilli(), fable.resetAt)
        assertEquals(25L * 24 * 60 * 60 * 1000, fable.resetAt!! - now)
        assertEquals("WEEKLY", fable.category.name)
        assertEquals(604_800L, fable.periodSeconds)

        val result = snapshot(windows)
        assertEquals("A model-specific zero is a restriction, not global exhaustion", "LIMITED", result.severity.name)
        assertEquals("seven-day", result.mostCritical?.id)
        assertRemaining(37.0, result.mostCritical!!)
    }

    @Test
    fun `Grok weekly eighty remains usable beside an unknown monthly value`() {
        val weekly = credits("""{"creditUsagePercent":20,"usagePeriodType":"weekly"}""")
        val monthly = billing("""{"monthlyLimit":1000}""")
        assertEquals(1, weekly.size)
        assertEquals(1, monthly.size)
        assertRemaining(80.0, weekly.single())
        assertEquals("xai-monthly", monthly.single().id)
        assertNull(monthly.single().usedPercent)
        assertNull(monthly.single().remainingPercent)
        assertFalse(monthly.single().exhausted)

        // The two parser rows reconstruct the reported screenshot/cache state directly.
        val screenshot = snapshot(weekly + monthly)
        assertEquals("An unknown optional row must not replace measured availability with Error", "HEALTHY", screenshot.severity.name)
        assertEquals("xai-credits", screenshot.mostCritical?.id)
        assertRemaining(80.0, screenshot.mostCritical!!)
        assertEquals("UNKNOWN", snapshot(monthly).severity.name)

        // The fresh provider merge may omit the deprecated monthly representation.
        val merged = snapshot(XaiBillingParser.merge(weekly, monthly))
        assertEquals("HEALTHY", merged.severity.name)
        assertRemaining(80.0, merged.mostCritical!!)

        // A supplied partial-fetch diagnostic remains distinct from quota availability.
        // This checks the snapshot contract, not HTTP transport behavior.
        val warning = "Optional billing fetch failed"
        val partial = screenshot.copy(status = SnapshotStatus.PARTIAL, errorMessage = warning)
        assertEquals(SnapshotStatus.PARTIAL, partial.status)
        assertEquals(warning, partial.errorMessage)
        assertEquals("HEALTHY", partial.severity.name)
        assertRemaining(80.0, partial.mostCritical!!)
    }

    @Test
    fun `Grok current period does not turn missing null or malformed credits into zero spend`() {
        val fields = listOf("", "\"creditUsagePercent\":null,", "\"creditUsagePercent\":\"invalid\",")
        for (field in fields) {
            val windows = credits("""
                {$field"currentPeriod":{"type":"weekly",
                  "start":"2026-10-10T00:00:00Z","end":"2026-10-17T00:00:00Z"}}
            """)
            assertEquals("The known period must retain its unknown row: $field", 1, windows.size)
            val window = windows.single()
            assertEquals("xai-credits", window.id)
            assertNull("Missing or unreadable usage is not measured zero: $field", window.usedPercent)
            assertNull(window.remainingPercent)
            assertFalse(window.exhausted)
            assertEquals("UNKNOWN", snapshot(windows).severity.name)
            assertEquals(Instant.parse("2026-10-17T00:00:00Z").toEpochMilli(), window.resetAt)
        }
    }

    @Test
    fun `Grok preserves explicit zero money and refuses invalid spend or alias fallback`() {
        // Valid numeric zero works in both documented numeric shapes.
        for (used in listOf("0", "{\"val\":0}")) {
            val windows = billing("""{"monthlyLimit":1000,"used":$used}""")
            assertEquals(1, windows.size)
            assertEquals(0.0, windows.single().usedPercent!!, 0.0)
            assertRemaining(100.0, windows.single())
            assertEquals("HEALTHY", snapshot(windows).severity.name)
        }

        for (used in listOf("-1", "\"invalid\"", "null", "{\"val\":null}", "{\"val\":\"invalid\"}")) {
            val windows = billing("""{"monthlyLimit":1000,"used":$used,"includedUsed":0}""")
            assertEquals(1, windows.size)
            val window = windows.single()
            assertNull("A present invalid/null spend must not fall back to includedUsed zero: $used", window.usedPercent)
            assertNull(window.remainingPercent)
            assertFalse(window.exhausted)
            assertEquals("UNKNOWN", snapshot(windows).severity.name)
        }

        // Only a present empty Cent object has the verified protobuf zero default.
        val emptyCent = billing("""{"monthlyLimit":1000,"used":{}}""").single()
        assertEquals(0.0, emptyCent.usedPercent!!, 0.0)
        assertRemaining(100.0, emptyCent)
    }
}
