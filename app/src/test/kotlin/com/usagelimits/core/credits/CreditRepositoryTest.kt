package com.usagelimits.core.credits

import com.usagelimits.core.database.CreditRepository
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import com.usagelimits.core.model.CreditStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Storage rules: stale over empty, order, no key material, nothing written for a vanished account. */
class CreditRepositoryTest {

    private val now = 1_757_000_000_000L
    private val dao = FakeCreditDao()
    private val repository = CreditRepository(dao)

    private fun account(id: String = "a", provider: CreditProviderId = CreditProviderId.DEEPSEEK) =
        CreditAccount(id, provider, id.uppercase(), "credit:$id", createdAt = now)

    @Test fun `a failed sync keeps the previous balances and the original fetch time`() = runBlocking {
        repository.upsert(account())
        repository.record("a", listOf(CreditBalance("USD", BigDecimal(9))), null, now)
        repository.record("a", null, "The key was rejected", now + 900_000)

        val snapshot = repository.snapshot("a")!!
        assertEquals(CreditStatus.FAILED, snapshot.status)
        assertEquals(1, snapshot.balances.size)
        assertEquals(0, BigDecimal(9).compareTo(snapshot.balances[0].amount))
        assertEquals(now, snapshot.fetchedAt)
        assertEquals(now + 900_000, snapshot.lastAttemptAt)
        assertEquals("The key was rejected", snapshot.error)

        // A later success replaces it and clears the error.
        repository.record("a", listOf(CreditBalance("USD", BigDecimal(4))), null, now + 1_800_000)
        val healed = repository.snapshot("a")!!
        assertEquals(CreditStatus.OK, healed.status)
        assertNull(healed.error)
        assertEquals(now + 1_800_000, healed.fetchedAt)
    }

    @Test fun `a first failure has no balances and is stamped with the attempt`() = runBlocking {
        repository.upsert(account())
        repository.record("a", null, "boom", now)
        val snapshot = repository.snapshot("a")!!
        assertTrue(snapshot.balances.isEmpty())
        assertEquals(now, snapshot.fetchedAt)
        assertEquals(CreditStatus.FAILED, snapshot.status)
    }

    @Test fun `amounts survive as exact decimals including the unlimited flag`() = runBlocking {
        repository.upsert(account())
        repository.record(
            "a",
            listOf(
                CreditBalance("USD", BigDecimal("49.58894"), BigDecimal("100"), "Key limit"),
                CreditBalance("USD", BigDecimal("0.30"), unlimited = true, label = "No limit"),
            ),
            null, now,
        )
        val stored = repository.snapshot("a")!!.balances
        assertEquals("49.58894", stored[0].amount.toPlainString())
        assertEquals("100", stored[0].total!!.toPlainString())
        assertEquals("Key limit", stored[0].label)
        assertEquals(false, stored[0].unlimited)
        assertEquals("0.30", stored[1].amount.toPlainString())
        assertEquals(true, stored[1].unlimited)
        // The persisted text holds decimal strings, not floats.
        assertTrue(dao.snapshots["a"]!!.balancesJson.contains("\"49.58894\""))
    }

    @Test fun `nothing is written for an account that was removed mid fetch`() = runBlocking {
        repository.upsert(account())
        repository.remove("a")
        repository.record("a", listOf(CreditBalance("USD", BigDecimal.ONE)), null, now)
        repository.record("a", null, "x", now)
        assertTrue(dao.snapshots.isEmpty())
    }

    @Test fun `accounts keep their order and a re-save keeps place snapshot and creation time`() = runBlocking {
        for (id in listOf("x", "y", "z")) repository.upsert(account(id, CreditProviderId.FAL))
        repository.record("x", listOf(CreditBalance("USD", BigDecimal(3))), null, now)
        repository.upsert(account("x", CreditProviderId.FAL).copy(label = "renamed", createdAt = now + 5))
        assertEquals(listOf("x", "y", "z"), repository.accounts().map { it.id })
        assertEquals("renamed", repository.accounts().first().label)
        assertEquals(now, repository.account("x")!!.createdAt)
        assertNotNull(repository.snapshot("x"))
        repository.remove("y")
        assertEquals(listOf("x", "z"), repository.accounts().map { it.id })
    }

    @Test fun `editing keeps the balance and sets or clears the threshold`() = runBlocking {
        repository.upsert(account(), CreditSnapshot("a", listOf(CreditBalance("USD", BigDecimal(8))), fetchedAt = now))
        repository.update("a", "Work", BigDecimal("20"))
        val edited = repository.account("a")!!
        assertEquals("Work", edited.label)
        assertEquals(0, BigDecimal(20).compareTo(edited.lowThreshold("USD")))
        assertEquals(1, repository.snapshot("a")!!.balances.size)
        repository.update("a", "Work", null)
        assertNull(repository.account("a")!!.customThreshold)
        repository.update("gone", "x", null) // no-op, no throw
    }

    @Test fun `an account of a provider this build does not know is left out`() = runBlocking {
        repository.upsert(account())
        dao.accounts["future"] = dao.accounts["a"]!!.copy(id = "future", provider = "not-a-provider")
        assertEquals(listOf("a"), repository.accounts().map { it.id })
        assertEquals(listOf("a"), repository.entriesOnce().map { it.id })
    }
}
