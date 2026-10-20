package com.usagelimits.core.database

import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.CreditNumbers
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import com.usagelimits.core.model.CreditStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.math.BigDecimal

/**
 * The credit accounts and the last balance read for each.
 *
 * Same rules as [UsageRepository]: no key material (accounts name a credential-store entry),
 * read-then-write pairs in one transaction, no write for an account that is gone, and a failed
 * sync keeps the previous balances and their original fetch time.
 */
class CreditRepository(
    private val dao: CreditDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val transactions: TransactionRunner = DirectTransactionRunner,
) {

    /** The persisted shape: amounts are decimal strings, `unlimited` is written only when true. */
    @Serializable
    private data class StoredBalance(
        val currency: String,
        val amount: String,
        val total: String? = null,
        val label: String? = null,
        val unlimited: Boolean = false,
    )

    fun observeEntries(): Flow<List<CreditEntry>> =
        combine(dao.observeAccounts(), dao.observeSnapshots()) { accounts, snapshots ->
            val byAccount = snapshots.associateBy { it.accountId }
            accounts.mapNotNull { entity ->
                CreditEntry(entity.toDomain() ?: return@mapNotNull null, byAccount[entity.id]?.toDomain())
            }
        }

    suspend fun entriesOnce(): List<CreditEntry> {
        val snapshots = dao.getSnapshots().associateBy { it.accountId }
        return dao.getAccounts().mapNotNull { entity ->
            CreditEntry(entity.toDomain() ?: return@mapNotNull null, snapshots[entity.id]?.toDomain())
        }
    }

    suspend fun accounts(): List<CreditAccount> = dao.getAccounts().mapNotNull { it.toDomain() }

    suspend fun account(id: String): CreditAccount? = dao.getAccount(id)?.toDomain()

    suspend fun snapshot(accountId: String): CreditSnapshot? = dao.getSnapshot(accountId)?.toDomain()

    /**
     * Adds or replaces an account. A re-save keeps its place, creation time and snapshot; a new
     * account joins the end of the list. [snapshot] replaces the stored one when given.
     */
    suspend fun upsert(account: CreditAccount, snapshot: CreditSnapshot? = null) = transactions.inTransaction {
        val existing = dao.getAccount(account.id)
        dao.upsertAccount(
            CreditAccountEntity(
                id = account.id,
                provider = account.provider.id,
                label = account.label,
                credentialReference = account.credentialReference,
                attributesJson = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), account.attributes),
                sortOrder = existing?.sortOrder ?: dao.nextSortOrder(),
                createdAt = existing?.createdAt ?: account.createdAt,
            ),
        )
        if (snapshot != null) dao.upsertSnapshot(snapshot.toEntity())
    }

    /** Renames an account and sets or clears its own low-balance figure. No-op if it is gone. */
    suspend fun update(id: String, label: String, lowThreshold: BigDecimal?) = transactions.inTransaction {
        val entity = dao.getAccount(id) ?: return@inTransaction
        val updated = entity.toDomain()?.with(label, lowThreshold) ?: return@inTransaction
        dao.upsertAccount(
            entity.copy(
                label = updated.label,
                attributesJson = json.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()), updated.attributes,
                ),
            ),
        )
    }

    suspend fun remove(id: String) = transactions.inTransaction {
        dao.deleteSnapshot(id)
        dao.deleteAccount(id)
    }

    /**
     * Records one sync. A failure ([balances] null) keeps the previous balances and their
     * original `fetchedAt` and only adds the error: yesterday's number marked stale beats an
     * empty card. No-op for an account removed while the fetch was in flight (the foreign key
     * would otherwise raise).
     */
    suspend fun record(accountId: String, balances: List<CreditBalance>?, error: String?, at: Long) =
        transactions.inTransaction {
            if (dao.getAccount(accountId) == null) return@inTransaction
            if (balances != null) {
                dao.upsertSnapshot(CreditSnapshot(accountId, balances, fetchedAt = at).toEntity())
            } else {
                val previous = dao.getSnapshot(accountId)
                dao.upsertSnapshot(
                    CreditSnapshotEntity(
                        accountId = accountId,
                        balancesJson = previous?.balancesJson ?: "[]",
                        fetchedAt = previous?.fetchedAt ?: at,
                        lastAttemptAt = at,
                        status = CreditStatus.FAILED.name,
                        error = error,
                    ),
                )
            }
        }

    private fun CreditSnapshot.toEntity() = CreditSnapshotEntity(
        accountId = accountId,
        balancesJson = json.encodeToString(
            ListSerializer(StoredBalance.serializer()),
            balances.map {
                StoredBalance(it.currency, it.amount.toPlainString(), it.total?.toPlainString(), it.label, it.unlimited)
            },
        ),
        fetchedAt = fetchedAt,
        lastAttemptAt = lastAttemptAt,
        status = status.name,
        error = error,
    )

    private fun CreditSnapshotEntity.toDomain() = CreditSnapshot(
        accountId = accountId,
        balances = runCatching {
            json.decodeFromString(ListSerializer(StoredBalance.serializer()), balancesJson).mapNotNull {
                CreditBalance(
                    currency = it.currency,
                    amount = CreditNumbers.parse(it.amount) ?: return@mapNotNull null,
                    total = it.total?.let(CreditNumbers::parse),
                    label = it.label,
                    unlimited = it.unlimited,
                )
            }
        }.getOrDefault(emptyList()),
        fetchedAt = fetchedAt,
        lastAttemptAt = lastAttemptAt,
        status = runCatching { CreditStatus.valueOf(status) }.getOrDefault(CreditStatus.FAILED),
        error = error,
    )

    /** Null for a provider this build does not know: left out rather than relabelled. */
    private fun CreditAccountEntity.toDomain(): CreditAccount? {
        val providerId = CreditProviderId.fromId(provider) ?: return null
        return CreditAccount(
            id = id,
            provider = providerId,
            label = label,
            credentialReference = credentialReference,
            attributes = runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), attributesJson)
            }.getOrDefault(emptyMap()),
            sortOrder = sortOrder,
            createdAt = createdAt,
        )
    }
}
