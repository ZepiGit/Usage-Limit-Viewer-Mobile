package com.usagelimits.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

/** Severity, thresholds and money text, with the same expectations as the iOS CreditsTests. */
class CreditPresentationTest {

    private val now = 1_757_000_000_000L
    private fun dec(text: String) = BigDecimal(text)

    /** The no-break spaces CLDR puts before a trailing symbol, as plain spaces. */
    private fun plain(text: String) = text.replace('\u00a0', ' ').replace('\u202f', ' ')

    private fun account(attributes: Map<String, String> = emptyMap()) =
        CreditAccount("a", CreditProviderId.DEEPSEEK, "A", "credit:a", attributes, createdAt = now)

    private fun level(amount: String, total: String? = null, low: Int = 5) =
        CreditSeverity.of(CreditBalance("USD", dec(amount), total?.let(::dec)), BigDecimal(low))

    @Test fun `severity with and without a total`() {
        assertEquals(Severity.EXHAUSTED, level("0"))
        assertEquals(Severity.EXHAUSTED, level("-3"))
        assertEquals(Severity.LOW, level("4.99"))
        assertEquals(Severity.MEDIUM, level("5"))
        assertEquals(Severity.MEDIUM, level("9.99"))
        assertEquals(Severity.HEALTHY, level("10"))
        assertEquals(Severity.MEDIUM, level("30", low = 20))
        // With a total the share decides, whatever the threshold says.
        assertEquals(Severity.HEALTHY, level("60", total = "100"))
        assertEquals(Severity.MEDIUM, level("50", total = "100"))
        assertEquals(Severity.LOW, level("20", total = "100"))
        assertEquals(Severity.LOW, level("1", total = "100"))
        assertEquals(Severity.HEALTHY, level("150", total = "100"))
        assertEquals(Severity.EXHAUSTED, level("0", total = "100"))
    }

    @Test fun `default threshold is five units and thirty five for yuan`() {
        assertEquals(0, BigDecimal(5).compareTo(account().lowThreshold("USD")))
        assertEquals(0, BigDecimal(35).compareTo(account().lowThreshold("CNY")))
        assertEquals(0, dec("12.5").compareTo(account(mapOf("lowThreshold" to "12.5")).lowThreshold("CNY")))
        assertEquals(0, BigDecimal(5).compareTo(account(mapOf("lowThreshold" to "junk")).lowThreshold("USD")))
        assertEquals(0, BigDecimal(5).compareTo(account(mapOf("lowThreshold" to "-1")).lowThreshold("USD")))
    }

    @Test fun `editing the threshold changes the severity`() {
        val snapshot = CreditSnapshot("a", listOf(CreditBalance("USD", BigDecimal(8))), fetchedAt = now)
        assertEquals(Severity.MEDIUM, CreditSeverity.base(account(), snapshot))
        val edited = account().with("Fal", BigDecimal(20))
        assertEquals("Fal", edited.label)
        assertEquals(Severity.LOW, CreditSeverity.base(edited, snapshot))
        assertNull(edited.with("Fal", null).customThreshold)
    }

    @Test fun `account severity takes the worst balance and ages like usage`() {
        val balances = listOf(CreditBalance("CNY", BigDecimal(200)), CreditBalance("USD", BigDecimal(1)))
        val fresh = CreditSnapshot("a", balances, fetchedAt = now)
        assertEquals(Severity.LOW, CreditSeverity.base(account(), fresh))
        assertEquals(Severity.UNKNOWN, CreditSeverity.base(account(), null))
        val failedEmpty = CreditSnapshot("a", emptyList(), fetchedAt = now, status = CreditStatus.FAILED, error = "x")
        assertEquals(Severity.ERROR, CreditSeverity.base(account(), failedEmpty))

        val stale = Severity.staleAfterMs(15)
        assertEquals(Severity.LOW, CreditSeverity.aged(Severity.LOW, now, now, stale))
        assertEquals(Severity.STALE, CreditSeverity.aged(Severity.LOW, now, now + stale, stale))
        assertEquals(Severity.ERROR, CreditSeverity.aged(Severity.ERROR, now, now + stale, stale))

        val entry = CreditEntry(account(), fresh)
        assertEquals(Severity.LOW, CreditSeverity.of(entry, now, stale))
        assertEquals(Severity.STALE, CreditSeverity.of(entry, now + stale, stale))
        assertEquals("USD", CreditSeverity.worstFirst(entry).first().currency)
    }

    @Test fun `currency formatting in US and German locales`() {
        val us = Locale.US
        assertEquals("$12.30", CreditFormat.string(dec("12.3"), "USD", us))
        assertEquals("<$0.01", CreditFormat.string(dec("0.004"), "USD", us))
        assertEquals("$0.00", CreditFormat.string(BigDecimal.ZERO, "USD", us))
        assertEquals("-$3.00", CreditFormat.string(dec("-3"), "USD", us))
        assertEquals("$1,234.50", CreditFormat.string(dec("1234.5"), "USD", us))
        assertTrue(CreditFormat.string(BigDecimal(110), "CNY", us).contains("110.00"))

        val de = Locale.GERMANY
        assertEquals("12,30 €", plain(CreditFormat.string(dec("12.3"), "EUR", de)))
        assertEquals("1.234,50 €", plain(CreditFormat.string(dec("1234.5"), "EUR", de)))
        assertEquals("<0,01 €", plain(CreditFormat.string(dec("0.004"), "EUR", de)))
        assertTrue(plain(CreditFormat.string(dec("5"), "USD", de)).startsWith("5,00 "))
    }

    @Test fun `summary joins balances and an uncapped key reads as spend`() {
        val summary = CreditFormat.summary(
            listOf(CreditBalance("CNY", BigDecimal(110)), CreditBalance("USD", BigDecimal(5))), Locale.US,
        )
        assertTrue(summary.endsWith(" · $5.00"))
        val unlimited = CreditBalance("USD", dec("12.5"), label = "No limit", unlimited = true)
        assertEquals("$12.50 spent this month", CreditFormat.line(unlimited, Locale.US))
    }

    @Test fun `a code that is not a currency still formats`() {
        assertEquals("5.00 XXX1", CreditFormat.string(BigDecimal(5), "XXX1", Locale.US))
    }

    @Test fun `ids and raw values are the shared contract`() {
        assertEquals(
            listOf("openrouter", "deepseek", "moonshot", "xai", "fal", "novita"),
            CreditProviderId.entries.map { it.id },
        )
        assertEquals(listOf("anthropic", "openai"), CreditProviderId.notPossible.map { it.id })
        assertEquals("credit-fal-abc", CreditAccount.idFor(CreditProviderId.FAL, "abc"))
        assertEquals("credit:credit-fal-abc", CreditAccount.referenceFor("credit-fal-abc"))
    }
}
