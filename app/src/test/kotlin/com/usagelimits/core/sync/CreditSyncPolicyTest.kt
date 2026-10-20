package com.usagelimits.core.sync

import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** A widget tick refreshes credit accounts on the same interval rule as usage. */
class CreditSyncPolicyTest {

    private val now = 1_757_000_000_000L
    private val minute = 60_000L

    private fun entry(id: String, lastAttemptAt: Long?) = CreditEntry(
        CreditAccount(id, CreditProviderId.FAL, id, "credit:$id", createdAt = now),
        lastAttemptAt?.let {
            CreditSnapshot(id, listOf(CreditBalance("USD", BigDecimal.ONE)), fetchedAt = it, lastAttemptAt = it)
        },
    )

    @Test fun `fresh credits need nothing, due or unread ones do`() {
        assertFalse(SyncWorker.creditCacheNeedsSync(emptyList(), 15, now))
        assertFalse(SyncWorker.creditCacheNeedsSync(listOf(entry("a", now - 5 * minute)), 15, now))
        assertTrue(SyncWorker.creditCacheNeedsSync(listOf(entry("a", now - 15 * minute)), 15, now))
        assertTrue(SyncWorker.creditCacheNeedsSync(listOf(entry("a", now), entry("b", null)), 15, now))
    }

    @Test fun `one fresh account cannot hide a due one`() {
        assertTrue(SyncWorker.creditCacheNeedsSync(listOf(entry("a", now), entry("b", now - 60 * minute)), 15, now))
    }

    @Test fun `a pass of only failed credit accounts retries like usage`() {
        val failed = listOf(SyncOutcome("c", success = false, message = "x"))
        assertEquals(androidx.work.ListenableWorker.Result.retry(), SyncWorker.resultFor(failed, periodic = true))
    }
}
