package com.usagelimits.core.credits

import com.usagelimits.core.database.CreditRepository
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.providers.credits.CreditError
import com.usagelimits.providers.credits.CreditRegistry
import com.usagelimits.providers.RecordedRequest
import com.usagelimits.providers.scriptedHttp
import com.usagelimits.core.auth.CredentialStore
import com.usagelimits.core.auth.OAuthCredentials
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/** Adding proves the key first, keeps it in the credential store only, and removing deletes it. */
class CreditAccountServiceTest {

    private val now = 1_757_000_000_000L
    private val dao = FakeCreditDao()
    private val repository = CreditRepository(dao)
    private val store = MemoryCredentialStore()

    private fun service(vararg replies: Pair<Int, String>, seen: MutableList<RecordedRequest> = mutableListOf()) =
        CreditAccountService(repository, store, CreditRegistry(scriptedHttp(*replies, seen = seen))) { now }

    @Test fun `adding stores the key in the credential store under the account reference only`() = runBlocking {
        val added = service(200 to """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"8.00"}]}""")
            .add(CreditProviderId.DEEPSEEK, "  ds-secret  ", label = " Work ")

        assertEquals("Work", added.label)
        assertTrue(added.id.startsWith("credit-deepseek-"))
        assertEquals("credit:${added.id}", added.credentialReference)
        assertEquals("ds-secret", store.items[added.credentialReference]!!.accessToken)
        // The key is in no Room row.
        val rows = dao.accounts.values.toString() + dao.snapshots.values.toString()
        assertFalse(rows.contains("ds-secret"))
        // The first read is stored, so the card is not empty before the first sync.
        assertEquals(0, BigDecimal(8).compareTo(repository.snapshot(added.id)!!.balances[0].amount))
    }

    @Test fun `a rejected key leaves no account and no key`() = runBlocking {
        try {
            service(401 to "{}").add(CreditProviderId.DEEPSEEK, "bad")
            fail("expected a rejection")
        } catch (e: CreditError.Rejected) {
            assertEquals(401, e.status)
        }
        assertTrue(repository.accounts().isEmpty())
        assertTrue(store.items.isEmpty())
    }

    /** Writes the key, then the caller is cancelled (Back) and the next suspension point notices. */
    private class CancelAfterSave(private val inner: MemoryCredentialStore, private val cancel: () -> Unit) :
        CredentialStore by inner {
        override suspend fun save(reference: String, credentials: OAuthCredentials) {
            inner.save(reference, credentials)
            cancel()
            yield()
        }
    }

    @Test fun `cancelling right after the key is saved neither strands the key nor loses the account`() = runBlocking {
        lateinit var job: Job
        val svc = CreditAccountService(
            repository, CancelAfterSave(store) { job.cancel() },
            CreditRegistry(scriptedHttp(200 to """{"data":{"available_balance":3}}""")),
        ) { now }
        job = launch(start = CoroutineStart.LAZY) { svc.add(CreditProviderId.MOONSHOT, "mk") }
        job.join()

        assertTrue(job.isCancelled)
        // Either both exist or neither: never a key that no account names.
        assertEquals(store.items.keys, repository.accounts().map { it.credentialReference }.toSet())
    }

    @Test fun `a blank key is refused before any request`() = runBlocking {
        val seen = mutableListOf<RecordedRequest>()
        try {
            service(200 to "{}", seen = seen).add(CreditProviderId.DEEPSEEK, "   ")
            fail("expected NoKey")
        } catch (_: CreditError.NoKey) {
        }
        assertTrue(seen.isEmpty())
    }

    @Test fun `the same key pasted again updates the same account`() = runBlocking {
        val body = """{"data":{"available_balance":3}}"""
        val first = service(200 to body).add(CreditProviderId.MOONSHOT, "mk", region = ProviderRegion.CN)
        val second = service(200 to body).add(CreditProviderId.MOONSHOT, "mk", label = "Renamed", region = ProviderRegion.CN)
        assertEquals(first.id, second.id)
        assertEquals(1, repository.accounts().size)
        assertEquals("Renamed", repository.accounts().single().label)
        assertEquals("cn", repository.accounts().single().attributes["region"])
        // A different key is a different account.
        val other = service(200 to body).add(CreditProviderId.MOONSHOT, "mk2")
        assertTrue(other.id != first.id)
    }

    @Test fun `an xai team id is resolved at add time and stored`() = runBlocking {
        val added = service(
            200 to """{"scope":"SCOPE_TEAM","scopeId":"team-9"}""",
            200 to """{"total":{"val":"-1000"}}""",
        ).add(CreditProviderId.XAI, "mk")
        assertEquals("team-9", added.teamId)

        // A typed id wins and skips the validation call.
        val seen = mutableListOf<RecordedRequest>()
        val typed = service(200 to """{"total":{"val":"-1000"}}""", seen = seen)
            .add(CreditProviderId.XAI, "mk2", teamId = " typed ")
        assertEquals("typed", typed.teamId)
        assertEquals(1, seen.size)

        // An organisation key with no typed id fails while the user is on the form.
        try {
            service(200 to """{"scope":"SCOPE_ORGANIZATION","scopeId":"o"}""").add(CreditProviderId.XAI, "mk3")
            fail("expected TeamIdRequired")
        } catch (_: CreditError.TeamIdRequired) {
        }
        assertEquals(2, repository.accounts().size)
    }

    @Test fun `editing the threshold keeps the balance and removing deletes the key first`() = runBlocking {
        val added = service(200 to """{"credits":{"current_balance":8,"currency":"USD"}}""").add(CreditProviderId.FAL, "fk")
        val svc = service()
        svc.update(added.id, "Fal", BigDecimal(20))
        assertEquals("Fal", repository.account(added.id)!!.label)
        assertEquals(0, BigDecimal(20).compareTo(repository.account(added.id)!!.lowThreshold("USD")))
        assertNotNull(repository.snapshot(added.id))

        svc.remove(added.id)
        assertNull(repository.account(added.id))
        assertNull(store.load(added.credentialReference))
        assertTrue(dao.snapshots.isEmpty())
    }
}
