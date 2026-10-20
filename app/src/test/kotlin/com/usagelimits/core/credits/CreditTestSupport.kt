package com.usagelimits.core.credits

import com.usagelimits.core.auth.CredentialStore
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.database.CreditAccountEntity
import com.usagelimits.core.database.CreditDao
import com.usagelimits.core.database.CreditSnapshotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Stands in for SQLite: rejects a snapshot whose parent account is gone, as the foreign key does. */
class FakeCreditDao : CreditDao {
    val accounts = linkedMapOf<String, CreditAccountEntity>()
    val snapshots = linkedMapOf<String, CreditSnapshotEntity>()

    private fun ordered() = accounts.values.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }, { it.id }))

    override fun observeAccounts(): Flow<List<CreditAccountEntity>> = flowOf(ordered())
    override suspend fun getAccounts() = ordered()
    override suspend fun getAccount(id: String) = accounts[id]
    override fun observeSnapshots(): Flow<List<CreditSnapshotEntity>> = flowOf(snapshots.values.toList())
    override suspend fun getSnapshots() = snapshots.values.toList()
    override suspend fun getSnapshot(accountId: String) = snapshots[accountId]
    override suspend fun upsertAccount(account: CreditAccountEntity) { accounts[account.id] = account }
    override suspend fun upsertSnapshot(snapshot: CreditSnapshotEntity) {
        if (snapshot.accountId !in accounts) throw IllegalStateException("FOREIGN KEY constraint failed (code 787)")
        snapshots[snapshot.accountId] = snapshot
    }
    override suspend fun deleteSnapshot(id: String) { snapshots.remove(id) }
    override suspend fun deleteAccount(id: String) { accounts.remove(id); snapshots.remove(id) }
    override suspend fun nextSortOrder() = (accounts.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
}

class MemoryCredentialStore : CredentialStore {
    val items = linkedMapOf<String, OAuthCredentials>()
    override suspend fun load(reference: String) = items[reference]
    override suspend fun save(reference: String, credentials: OAuthCredentials) { items[reference] = credentials }
    override suspend fun delete(reference: String) { items.remove(reference) }
    override suspend fun references() = items.keys.toSet()
}
