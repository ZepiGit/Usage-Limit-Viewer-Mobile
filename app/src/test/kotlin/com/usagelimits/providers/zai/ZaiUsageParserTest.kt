package com.usagelimits.providers.zai

import com.usagelimits.core.model.WindowCategory
import com.usagelimits.core.network.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Z.ai quota shapes from docs/providers-zai.md. All values synthetic. */
class ZaiUsageParserTest {

    private fun parse(json: String) = ZaiUsageParser.parse(JsonSupport.parseObject(json))

    private fun limits(vararg entries: String) =
        """{"code":200,"data":{"limits":[${entries.joinToString(",")}],"level":"lite"},"success":true}"""

    @Test
    fun `Lite credit limits read as the 5h and weekly windows`() {
        val windows = parse(
            """{"code":200,"msg":"Operation successful","data":{"limits":[
              {"type":"CREDIT_LIMIT","unit":3,"number":5,"usage":2000,"currentValue":402,"remaining":1597,"percentage":20,"nextResetTime":1788351145586},
              {"type":"CREDIT_LIMIT","unit":6,"number":1,"usage":10000,"currentValue":5207,"remaining":4792,"percentage":52,"nextResetTime":1788784466996}
            ],"level":"lite"},"success":true}""",
        )

        assertEquals(2, windows.size)
        assertEquals("zai-credit_limit-18000", windows[0].id)
        assertEquals("5h limit", windows[0].label)
        assertEquals(WindowCategory.FIVE_HOUR, windows[0].category)
        assertEquals(20.0, windows[0].usedPercent!!, 0.0)
        assertEquals(1788351145586L, windows[0].resetAt)
        assertEquals("Weekly", windows[1].label)
        assertEquals(WindowCategory.WEEKLY, windows[1].category)
        assertEquals(604_800L, windows[1].periodSeconds)
        assertEquals(52.0, windows[1].usedPercent!!, 0.0)
        assertEquals("Lite", ZaiUsageParser.plan(JsonSupport.parseObject(limits())))
    }

    @Test
    fun `a Max entry with only a percentage and a reset still reads`() {
        val windows = parse(limits("""{"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":37,"nextResetTime":1788351145}"""))

        assertEquals(37.0, windows.single().usedPercent!!, 0.0)
        // Epoch seconds are scaled to millis.
        assertEquals(1788351145000L, windows.single().resetAt)
    }

    @Test
    fun `no nextResetTime means no reset, not a reset at zero`() {
        val window = parse(limits("""{"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":0}""")).single()

        assertNull(window.resetAt)
        assertEquals(0.0, window.usedPercent!!, 0.0)
        assertFalse(window.exhausted)
    }

    @Test
    fun `without a percentage the used figure divides by the cap`() {
        val window = parse(limits("""{"type":"TOKENS_LIMIT","unit":6,"number":1,"usage":200,"currentValue":50}""")).single()

        assertEquals(25.0, window.usedPercent!!, 0.0)
    }

    @Test
    fun `the monthly TIME_LIMIT sent as one minute reads as thirty days`() {
        val window = parse(limits("""{"type":"TIME_LIMIT","unit":5,"number":1,"usage":1000,"currentValue":100}""")).single()

        assertEquals(30L * 86_400, window.periodSeconds)
        assertEquals(WindowCategory.MONTHLY, window.category)
        assertEquals("Monthly", window.label)
        assertEquals(10.0, window.usedPercent!!, 0.0)
    }

    @Test
    fun `an unreadable figure is unknown, never zero and never full`() {
        val windows = parse(
            limits(
                """{"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":140}""",
                """{"type":"TOKENS_LIMIT","unit":6,"number":1,"usage":10,"currentValue":50}""",
                """{"type":"CREDIT_LIMIT","unit":3,"number":5,"usage":0,"currentValue":0}""",
                """{"type":"TIME_LIMIT","unit":9,"number":2,"percentage":10}""",
                // Present but invalid does NOT fall back to the ratio.
                """{"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":-5,"usage":100,"currentValue":10}""",
            ),
        )

        assertEquals(5, windows.size)
        assertTrue(windows.take(3).all { it.usedPercent == null && !it.exhausted })
        assertNull(windows[4].usedPercent)
        // Unknown unit: no length, category other, labelled by type.
        assertNull(windows[3].periodSeconds)
        assertEquals(WindowCategory.OTHER, windows[3].category)
        assertEquals("Time limit", windows[3].label)
        assertEquals(10.0, windows[3].usedPercent!!, 0.0)
        assertEquals("a known length labels by category, not by type", "5h limit", windows[2].label)
    }

    @Test
    fun `an unknown length is labelled by type`() {
        val labels = parse(
            limits(
                """{"type":"CREDIT_LIMIT","unit":9,"number":1,"percentage":1}""",
                """{"type":"TOKENS_LIMIT","unit":9,"number":1,"percentage":1}""",
            ),
        ).map { it.label }

        assertEquals(listOf("Credit limit", "Token limit"), labels)
    }

    @Test
    fun `a full window is exhausted`() {
        assertTrue(parse(limits("""{"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":100}""")).single().exhausted)
    }
}
