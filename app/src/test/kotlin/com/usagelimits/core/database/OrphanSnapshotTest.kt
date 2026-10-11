package com.usagelimits.core.database

import com.usagelimits.core.model.SnapshotStatus
import com.usagelimits.core.model.UsageSnapshot
import com.usagelimits.core.model.UsageWindow
import com.usagelimits.core.model.WindowCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * A sync that lands after its account was removed must not write.
 *
 * `usage_snapshots.accountId` is a foreign key onto `accounts`, and Room's upsert issues a
 * plain INSERT, so a snapshot for a vanished parent raises SQLITE_CONSTRAINT_FOREIGNKEY.
 * Room 2.6.1 rethrows that (its uniqueness fallback only tolerates "unique"/2067/1555), it
 * escapes `SyncEngine.syncAccount`, cancels the sibling accounts inside `syncAll`'s scope and
 * reaches the uncaught handler. The trigger is two taps: "Refresh now" then "Remove account"
 * while the provider call is still outstanding.
 */
class OrphanSnapshotTest {

    private class FakeAccountDao(private val rows: MutableMap<String, AccountEntity>) : AccountDao {
        var markSyncedCalls = 0

        override fun observeAll(): Flow<List<AccountEntity>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<AccountEntity> = rows.values.toList()
        override suspend fun getById(localId: String): AccountEntity? = rows[localId]
        override suspend fun getByExternalId(provider: String, externalAccountId: String) =
            rows.values.firstOrNull { it.provider == provider && it.externalAccountId == externalAccountId }

        override suspend fun upsert(account: AccountEntity) { rows[account.localId] = account }
        override suspend fun deleteById(localId: String) { rows.remove(localId) }
        override suspend fun markSynced(localId: String, timestamp: Long) { markSyncedCalls++ }
        // Ordering plays no part in what these tests exercise; the reorder path has its own
        // double that actually records.
        override suspend fun setSortOrder(localId: String, order: Int) = Unit
        override suspend fun nextSortOrder(): Int = 0
    }

    /** Stands in for SQLite: rejects any insert whose parent account row is gone. */
    private class FakeSnapshotDao(private val accounts: Map<String, AccountEntity>) : UsageSnapshotDao {
        val rows = mutableMapOf<String, UsageSnapshotEntity>()
        var upsertAttempts = 0

        override fun observeAll(): Flow<List<UsageSnapshotEntity>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<UsageSnapshotEntity> = rows.values.toList()
        override suspend fun getForAccount(accountId: String) = rows[accountId]
        override suspend fun deleteForAccount(accountId: String) { rows.remove(accountId) }

        override suspend fun upsert(snapshot: UsageSnapshotEntity) {
            upsertAttempts++
            if (!accounts.containsKey(snapshot.accountId)) {
                throw IllegalStateException("FOREIGN KEY constraint failed (code 787)")
            }
            rows[snapshot.accountId] = snapshot
        }
    }

    private val now = 1_757_000_000_000L

    private fun account(id: String) = AccountEntity(
        localId = id,
        provider = "codex",
        externalAccountId = "ext-$id",
        email = null,
        displayName = null,
        plan = null,
        credentialReference = "codex_$id",
        createdAt = now,
        lastSuccessfulSync = null,
    )

    private fun snapshot(accountId: String) = UsageSnapshot(
        accountId = accountId,
        fetchedAt = now,
        status = SnapshotStatus.OK,
        windows = listOf(
            UsageWindow(
                id = "w1",
                label = "Weekly",
                category = WindowCategory.WEEKLY,
                usedPercent = 40.0,
                periodSeconds = 604_800,
                resetAt = now + 3_600_000,
                exhausted = false,
            ),
        ),
    )

    @Test
    fun `a snapshot for a removed account is dropped, not written`() = runBlocking {
        val accounts = mutableMapOf<String, AccountEntity>()
        val accountDao = FakeAccountDao(accounts)
        val snapshotDao = FakeSnapshotDao(accounts)
        val repository = UsageRepository(accountDao, snapshotDao)

        // The account existed when the fetch started and is gone by the time it returns.
        repository.saveSnapshot(snapshot("A"))

        assertEquals("no insert should have been attempted", 0, snapshotDao.upsertAttempts)
        assertEquals(0, accountDao.markSyncedCalls)
        assertTrue(snapshotDao.rows.isEmpty())
    }

    @Test
    fun `a failure for a removed account is dropped, not written`() = runBlocking {
        val accounts = mutableMapOf<String, AccountEntity>()
        val accountDao = FakeAccountDao(accounts)
        val snapshotDao = FakeSnapshotDao(accounts)
        val repository = UsageRepository(accountDao, snapshotDao)

        repository.saveFailure("A", "Sign-in expired — reconnect this account", now)

        assertEquals(0, snapshotDao.upsertAttempts)
        assertTrue(snapshotDao.rows.isEmpty())
    }

    @Test
    fun `a snapshot for a live account is still written`() = runBlocking {
        val accounts = mutableMapOf("A" to account("A"))
        val accountDao = FakeAccountDao(accounts)
        val snapshotDao = FakeSnapshotDao(accounts)
        val repository = UsageRepository(accountDao, snapshotDao)

        repository.saveSnapshot(snapshot("A"))
        repository.saveFailure("A", "No network connection", now)

        assertEquals(2, snapshotDao.upsertAttempts)
        assertEquals(1, accountDao.markSyncedCalls)
        assertEquals(SnapshotStatus.FAILED.name, snapshotDao.rows.getValue("A").status)
        // The failure keeps the numbers it found, which is the whole point of saveFailure.
        assertTrue(snapshotDao.rows.getValue("A").windowsJson.contains("Weekly"))
    }
    @Test
    fun `a failed attempt preserves measurement time and records its own time`() = runBlocking {
        val accounts = mutableMapOf("A" to account("A"))
        val snapshots = FakeSnapshotDao(accounts)
        val repository = UsageRepository(FakeAccountDao(accounts), snapshots)
        repository.saveSnapshot(snapshot("A"))
        repository.saveFailure("A", "No network connection", now + 60_000)
        val saved = repository.snapshot("A")!!
        assertEquals(now, saved.fetchedAt)
        assertEquals(now + 60_000, saved.lastAttemptAt)
        assertEquals(60.0, saved.windows.single().remainingPercent!!, 0.0)
    }

    @Test
    fun `window scope survives persistence`() = runBlocking {
        val accounts = mutableMapOf("A" to account("A"))
        val repository = UsageRepository(FakeAccountDao(accounts), FakeSnapshotDao(accounts))
        val original = snapshot("A")
        repository.saveSnapshot(original.copy(windows = original.windows.map {
            it.copy(scope = com.usagelimits.core.model.UsageScope.MODEL)
        }))
        assertEquals(com.usagelimits.core.model.UsageScope.MODEL, repository.snapshot("A")!!.windows.single().scope)
    }

    @Test
    fun `legacy Claude scopes are inferred from preserved identities`() = runBlocking {
        val accounts = mutableMapOf("A" to account("A").copy(provider = "claude"))
        val snapshots = FakeSnapshotDao(accounts)
        snapshots.rows["A"] = UsageSnapshotEntity(
            accountId = "A", fetchedAt = now, status = "OK", errorMessage = null,
            windowsJson = """[
                {"id":"five-hour","label":"5h limit","category":"FIVE_HOUR","usedPercent":0.0},
                {"id":"seven-day","label":"Weekly","category":"WEEKLY","usedPercent":63.0},
                {"id":"seven-day-fable","label":"Weekly (Fable)","category":"WEEKLY","usedPercent":100.0},
                {"id":"iguana-necktie","label":"Iguana necktie","category":"WEEKLY","usedPercent":100.0}
            ]""", resetCreditsJson = "[]",
        )
        val restored = UsageRepository(FakeAccountDao(accounts), snapshots).snapshot("A")!!
        assertEquals(
            listOf(com.usagelimits.core.model.UsageScope.GLOBAL, com.usagelimits.core.model.UsageScope.GLOBAL,
                com.usagelimits.core.model.UsageScope.MODEL, com.usagelimits.core.model.UsageScope.UNKNOWN),
            restored.windows.map { it.scope },
        )
        assertEquals(37.0, restored.mostCritical!!.remainingPercent!!, 0.0)
        assertEquals(com.usagelimits.core.model.Severity.LIMITED, restored.severity)
        assertEquals(now, restored.lastAttemptAt)
    }

    @Test
    fun `legacy xai cache uses measured credits before monthly fallback and scopes on demand`() = runBlocking {
        for ((used, expectedIds) in listOf(
            "20.0" to listOf("xai-credits", "xai-on-demand"),
            "null" to listOf("xai-credits", "xai-monthly", "xai-on-demand"),
        )) {
            val accounts = mutableMapOf("A" to account("A").copy(provider = "xai"))
            val snapshots = FakeSnapshotDao(accounts)
            snapshots.rows["A"] = UsageSnapshotEntity(
                accountId = "A", fetchedAt = now, status = "OK", errorMessage = null,
                windowsJson = """[
                    {"id":"xai-credits","label":"Weekly credits","category":"WEEKLY","usedPercent":$used},
                    {"id":"xai-monthly","label":"Monthly included","category":"MONTHLY","usedPercent":100.0},
                    {"id":"xai-on-demand","label":"On-demand","category":"MONTHLY","usedPercent":100.0}
                ]""", resetCreditsJson = "[]",
            )
            val restored = UsageRepository(FakeAccountDao(accounts), snapshots).snapshot("A")!!
            assertEquals(expectedIds, restored.windows.map { it.id })
            assertEquals(com.usagelimits.core.model.UsageScope.MODEL, restored.windows.last().scope)
            if (used != "null") {
                // A measured on-demand pool proves a positive cap, so the credit view is one
                // of two pools, exactly as the monthly representation of the same account.
                assertEquals(com.usagelimits.core.model.UsageScope.MODEL, restored.windows.first().scope)
                assertEquals(80.0, restored.windows.first().remainingPercent!!, 0.0)
                assertEquals(com.usagelimits.core.model.Severity.LIMITED, restored.severity)
            }
        }
    }

    private suspend fun cachedXai(windowsJson: String): UsageSnapshot {
        val accounts = mutableMapOf("A" to account("A").copy(provider = "xai"))
        val snapshots = FakeSnapshotDao(accounts)
        snapshots.rows["A"] = UsageSnapshotEntity(
            accountId = "A", fetchedAt = now, status = "OK", errorMessage = null,
            windowsJson = windowsJson, resetCreditsJson = "[]",
        )
        return UsageRepository(FakeAccountDao(accounts), snapshots).snapshot("A")!!
    }

    @Test
    fun `legacy xai cache retains available on demand beside exhausted monthly without a refresh`() = runBlocking {
        val restored = cachedXai("""[
            {"id":"xai-monthly","label":"Monthly included","category":"MONTHLY","usedPercent":100.0},
            {"id":"xai-on-demand","label":"On-demand","category":"MONTHLY","usedPercent":0.0}
        ]""")

        assertEquals(listOf(0.0, 100.0), restored.windows.map { it.remainingPercent })
        assertTrue(restored.windows.all { it.scope == com.usagelimits.core.model.UsageScope.MODEL })
        assertEquals(com.usagelimits.core.model.Severity.LIMITED, restored.severity)
        assertEquals(now, restored.fetchedAt)
    }

    @Test
    fun `cached exhausted credits beside available on demand stay limited after a reload`() = runBlocking {
        val restored = cachedXai("""[
            {"id":"xai-credits","label":"Monthly credits","category":"MONTHLY","usedPercent":100.0,"exhausted":true,"scope":"GLOBAL"},
            {"id":"xai-on-demand","label":"On-demand","category":"MONTHLY","usedPercent":0.0,"scope":"MODEL"}
        ]""")

        assertEquals(com.usagelimits.core.model.UsageScope.MODEL, restored.windows.first().scope)
        assertEquals(com.usagelimits.core.model.Severity.LIMITED, restored.severity)
    }

    @Test
    fun `legacy xai monthly without on demand remains a global constraint`() = runBlocking {
        val restored = cachedXai("""[
            {"id":"xai-monthly","label":"Monthly included","category":"MONTHLY","usedPercent":100.0}
        ]""")

        assertEquals(com.usagelimits.core.model.UsageScope.GLOBAL, restored.windows.single().scope)
        assertEquals(com.usagelimits.core.model.Severity.EXHAUSTED, restored.severity)
    }

    @Test
    fun `xai cache preserves every explicit monthly and on demand scope`() = runBlocking {
        for (scope in com.usagelimits.core.model.UsageScope.entries) {
            val monthly = cachedXai("""[
                {"id":"xai-monthly","label":"Monthly included","category":"MONTHLY","usedPercent":100.0,"scope":"${scope.name}"},
                {"id":"xai-on-demand","label":"On-demand","category":"MONTHLY","usedPercent":0.0}
            ]""")
            assertEquals(scope, monthly.windows.first().scope)

            val demand = cachedXai("""[
                {"id":"xai-monthly","label":"Monthly included","category":"MONTHLY","usedPercent":100.0},
                {"id":"xai-on-demand","label":"On-demand","category":"MONTHLY","usedPercent":0.0,"scope":"${scope.name}"}
            ]""")
            assertEquals(scope, demand.windows.last().scope)
            // A new on-demand row can have an unknown cap; only the old shape proves cap > 0.
            assertEquals(com.usagelimits.core.model.UsageScope.GLOBAL, demand.windows.first().scope)
        }
    }

    @Test
    fun `partial provider sync persists fresh values and its warning together`() = runBlocking {
        val accounts = mutableMapOf("A" to account("A").copy(provider = "xai"))
        val repository = UsageRepository(FakeAccountDao(accounts), FakeSnapshotDao(accounts))
        repository.saveSnapshot(snapshot("A").copy(fetchedAt = now - 60_000))
        val credentials = object : com.usagelimits.core.auth.CredentialStore {
            override suspend fun load(reference: String) = com.usagelimits.core.auth.OAuthCredentials(
                accessToken = "synthetic-test-token", refreshToken = null, idToken = null, expiresAt = null,
            )
            override suspend fun save(reference: String, credentials: com.usagelimits.core.auth.OAuthCredentials) = Unit
            override suspend fun delete(reference: String) = Unit
            override suspend fun references(): Set<String> = emptySet()
        }
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val credits = chain.request().url.toString() == com.usagelimits.core.network.ProviderEndpoints.Xai.BILLING_CREDITS_URL
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (credits) 200 else 403).message(if (credits) "OK" else "Forbidden")
                .body((if (credits) """{"creditUsagePercent":20.0}""" else "{}")
                    .toResponseBody(com.usagelimits.core.network.HttpClient.JSON_MEDIA_TYPE))
                .build()
        }.build()
        val engine = com.usagelimits.core.sync.SyncEngine(
            repository, credentials,
            com.usagelimits.providers.ProviderRegistry(com.usagelimits.core.network.HttpClient(http)),
            nowMs = { now },
        )
        val outcome = engine.syncAccount("A")
        val saved = repository.snapshot("A")!!
        assertTrue("usable partial data must not trigger a whole-account retry", outcome.success)
        assertEquals(SnapshotStatus.PARTIAL, saved.status)
        assertTrue(saved.errorMessage?.isNotBlank() == true)
        assertEquals(now, saved.fetchedAt)
        assertEquals(now, saved.lastAttemptAt)
        assertEquals(listOf("xai-credits"), saved.windows.map { it.id })
        assertEquals(80.0, saved.windows.single().remainingPercent!!, 0.0)
    }

}
