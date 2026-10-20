package com.usagelimits.widget

import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import com.usagelimits.core.model.CreditStatus
import com.usagelimits.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** What the Credits widget is given: worst first, aged at render time, 1/3/6 by size. */
class CreditWidgetBuilderTest {

    private val now = 1_757_000_000_000L
    private val stale = com.usagelimits.core.model.Severity.staleAfterMs(15)

    private fun entry(id: String, amount: Int?, fetchedAt: Long = now, status: CreditStatus = CreditStatus.OK) =
        CreditEntry(
            CreditAccount(id, CreditProviderId.FAL, id.uppercase(), "credit:$id", createdAt = now),
            snapshot = if (amount == null) null
            else CreditSnapshot(id, listOf(CreditBalance("USD", BigDecimal(amount))), fetchedAt, status = status),
        )

    @Test fun `accounts are listed worst first and ties keep the user's order`() {
        val snapshot = CreditWidgetBuilder.build(
            listOf(entry("a", 100), entry("b", 1), entry("c", 0), entry("d", 100)), now, stale,
        )
        assertEquals(listOf("c", "b", "a", "d"), snapshot.rows.map { it.accountId })
        assertEquals(
            listOf(Severity.EXHAUSTED, Severity.LOW, Severity.HEALTHY, Severity.HEALTHY),
            snapshot.rows.map { it.severity },
        )
    }

    @Test fun `an old snapshot reads stale and sinks below healthy ones`() {
        val snapshot = CreditWidgetBuilder.build(
            listOf(entry("old", 100, fetchedAt = now - stale - 1), entry("fresh", 100)), now, stale,
        )
        assertEquals(listOf("fresh", "old"), snapshot.rows.map { it.accountId })
        assertEquals(Severity.STALE, snapshot.rows.last().severity)
    }

    @Test fun `a never read account has no figure and a failed empty one is an error`() {
        val never = CreditWidgetBuilder.build(listOf(entry("n", null)), now, stale).rows.single()
        assertEquals(Severity.UNKNOWN, never.severity)
        assertNull(never.fetchedAt)
        assertEquals("—", amountText(never))

        val failed = CreditEntry(
            entry("f", 1).account,
            CreditSnapshot("f", emptyList(), now, status = CreditStatus.FAILED, error = "rejected"),
        )
        val row = CreditWidgetBuilder.build(listOf(failed), now, stale).rows.single()
        assertEquals(Severity.ERROR, row.severity)
        // Never the word "Error" in the figure slot: a quiet dash, with the severity dot beside it.
        assertEquals("—", amountText(row))
        assertEquals("rejected", statusText(row))
    }

    @Test fun `a failed refresh with a cached balance keeps its colour and still says it failed`() {
        val failedWithBalance = CreditEntry(
            entry("a", 1).account,
            CreditSnapshot("a", listOf(CreditBalance("USD", BigDecimal(100))), now, status = CreditStatus.FAILED, error = "rejected"),
        )
        val row = CreditWidgetBuilder.build(listOf(failedWithBalance), now, stale).rows.single()
        assertEquals(Severity.HEALTHY, row.severity)
        assertTrue(amountText(row), amountText(row).contains("100"))
        assertTrue(refreshFailed(row))
        assertTrue(statusText(row), statusText(row).startsWith("Refresh failed: rejected"))

        val fine = CreditWidgetBuilder.build(listOf(entry("b", 100)), now, stale).rows.single()
        assertFalse(refreshFailed(fine))
        assertTrue(statusText(fine), fine.providerName in statusText(fine))
    }

    @Test fun `the figure shown is the worst balance`() {
        val account = entry("a", 1).account
        val both = CreditEntry(
            account,
            CreditSnapshot("a", listOf(CreditBalance("CNY", BigDecimal(200)), CreditBalance("USD", BigDecimal(1))), now),
        )
        val row = CreditWidgetBuilder.build(listOf(both), now, stale).rows.single()
        assertEquals("USD", row.balances.first().currency)
        assertEquals(Severity.LOW, row.severity)
    }

    @Test fun `size decides how many accounts show`() {
        assertEquals(1, CreditWidgetBuilder.capacity(110f, 110f))
        assertEquals(3, CreditWidgetBuilder.capacity(250f, 110f))
        assertEquals(6, CreditWidgetBuilder.capacity(250f, 250f))
        assertEquals(1, CreditWidgetBuilder.capacity(110f, 200f))
    }
}
