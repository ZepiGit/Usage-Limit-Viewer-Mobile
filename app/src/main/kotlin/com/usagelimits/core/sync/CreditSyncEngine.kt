package com.usagelimits.core.sync

import com.usagelimits.core.auth.CredentialStore
import com.usagelimits.core.database.CreditRepository
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditBalance
import com.usagelimits.providers.credits.CreditError
import com.usagelimits.providers.credits.CreditRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Refreshes every credit account. Runs beside [SyncEngine] in the worker and the foreground
 * refresh, so a background pass updates balances with the usage windows.
 *
 * Each account is isolated: one bad key becomes that account's error line and nothing else's.
 * There is no token refresh, a pasted key either works or it does not. Cancellation is the only
 * error that escapes, as in [SyncEngine].
 */
class CreditSyncEngine(
    private val repository: CreditRepository,
    private val credentialStore: CredentialStore,
    private val registry: CreditRegistry,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * One pass at a time. Two overlapping passes could land out of order and let an older
     * balance overwrite a newer one; the later pass waits and then reads fresh.
     */
    private val runLock = Mutex()

    /** Syncs every credit account concurrently. Never throws but for cancellation. */
    suspend fun syncAll(): List<SyncOutcome> = runLock.withLock {
        coroutineScope { repository.accounts().map { async { sync(it) } }.awaitAll() }
    }

    private suspend fun sync(account: CreditAccount): SyncOutcome {
        val balances: List<CreditBalance>?
        val message: String?
        try {
            balances = fetch(account)
            message = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = messageFor(e)
            record(account.id, null, failure)
            return SyncOutcome(account.id, success = false, message = failure)
        }
        record(account.id, balances, message)
        return SyncOutcome(account.id, success = true)
    }

    /** Best-effort, like the usage sink: an unwritable row must not fail the account. */
    private suspend fun record(accountId: String, balances: List<CreditBalance>?, error: String?) {
        try {
            repository.record(accountId, balances, error, nowMs())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun fetch(account: CreditAccount): List<CreditBalance> {
        val provider = registry.forId(account.provider)
            ?: throw CreditError.Unavailable("no client for ${account.provider.id}")
        val stored = credentialStore.load(account.credentialReference)
            ?: throw CreditError.Unavailable("no key is stored for this account")
        return provider.fetchBalances(stored.accessToken, account.attributes)
    }

    /** Only text the app built: never the key, a header or a payload. */
    private fun messageFor(error: Exception): String =
        if (error is CreditError) error.message.orEmpty() else "The key could not be read from the device."
}
