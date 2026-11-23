package com.usagelimits.core.database

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Each subscription waits for an explicit read or failure; writes still use the real DAO. */
class ControlledAccountReads(private val real: AccountDao) : AccountDao by real {
    private val reads = Channel<Result<List<AccountEntity>>>(Channel.UNLIMITED)

    override fun observeAll(): Flow<List<AccountEntity>> = flow {
        for (read in reads) emit(read.getOrThrow())
    }

    suspend fun succeed() { reads.send(Result.success(real.getAll())) }
    suspend fun fail() { reads.send(Result.failure(IllegalStateException("Synthetic account read failure"))) }
}

/** Snapshots can fail independently of the account rows. */
class ControlledSnapshotReads(private val real: UsageSnapshotDao) : UsageSnapshotDao by real {
    private val reads = Channel<Result<List<UsageSnapshotEntity>>>(Channel.UNLIMITED)

    override fun observeAll(): Flow<List<UsageSnapshotEntity>> = flow {
        for (read in reads) emit(read.getOrThrow())
    }

    suspend fun succeed() { reads.send(Result.success(real.getAll())) }
    suspend fun fail() { reads.send(Result.failure(IllegalStateException("Synthetic snapshot read failure"))) }
}
