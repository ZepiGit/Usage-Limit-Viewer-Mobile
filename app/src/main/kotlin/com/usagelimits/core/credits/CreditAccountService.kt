package com.usagelimits.core.credits

import com.usagelimits.core.auth.CredentialStore
import com.usagelimits.core.auth.OAuthCredentials
import com.usagelimits.core.database.CreditRepository
import com.usagelimits.core.model.CreditAccount
import com.usagelimits.core.model.CreditProviderId
import com.usagelimits.core.model.CreditSnapshot
import com.usagelimits.core.model.ProviderRegion
import com.usagelimits.providers.credits.CreditError
import com.usagelimits.providers.credits.CreditRegistry
import com.usagelimits.providers.keyIdentity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * Adding, editing and removing credit accounts. The key lives only in the credential store; the
 * Room row names it by reference.
 */
class CreditAccountService(
    private val repository: CreditRepository,
    private val credentials: CredentialStore,
    private val registry: CreditRegistry,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * Proves the key against the provider, then stores it and the account. A rejected key leaves
     * no account and no stored key. The same key pasted again updates the same account.
     */
    suspend fun add(
        provider: CreditProviderId,
        key: String,
        label: String? = null,
        region: ProviderRegion = ProviderRegion.INTL,
        teamId: String? = null,
        lowThreshold: BigDecimal? = null,
    ): CreditAccount {
        val secret = key.trim().ifEmpty { throw CreditError.NoKey() }
        val client = registry.forId(provider) ?: throw CreditError.Unavailable("no client for ${provider.id}")

        val attributes = buildMap {
            if (provider.hasRegion) put(CreditAccount.REGION_KEY, region.id)
            lowThreshold?.takeIf { it.signum() > 0 }?.let { put(CreditAccount.LOW_THRESHOLD_KEY, it.toPlainString()) }
            // Resolved now, not at the first sync: a key that names no team and no typed id
            // should fail while the user is looking at the form.
            if (provider.acceptsTeamId) {
                put(
                    CreditAccount.TEAM_ID_KEY,
                    teamId?.trim()?.takeIf { it.isNotEmpty() }
                        ?: registry.xai.resolveTeamId(secret)
                        ?: throw CreditError.TeamIdRequired(),
                )
            }
        }
        val balances = client.fetchBalances(secret, attributes)

        val id = CreditAccount.idFor(provider, keyIdentity(secret).removePrefix("key-"))
        val reference = CreditAccount.referenceFor(id)
        val existed = repository.account(id) != null
        val now = nowMs()
        val account = CreditAccount(
            id = id,
            provider = provider,
            label = label?.trim()?.takeIf { it.isNotEmpty() } ?: provider.displayName,
            credentialReference = reference,
            attributes = attributes,
            createdAt = now,
        )
        credentials.save(reference, OAuthCredentials(accessToken = secret, refreshToken = null, idToken = null, expiresAt = null))
        try {
            repository.upsert(account, CreditSnapshot(id, balances, fetchedAt = now))
        } catch (e: Exception) {
            // No account names this key now; do not leave it behind. An existing account keeps it.
            if (!existed) withContext(NonCancellable) { credentials.delete(reference) }
            throw e
        }
        return repository.account(id) ?: account
    }

    suspend fun update(id: String, label: String, lowThreshold: BigDecimal?) {
        val current = repository.account(id) ?: return
        repository.update(id, label.trim().ifEmpty { current.label }, lowThreshold?.takeIf { it.signum() > 0 })
    }

    /** The key goes first: an orphaned row is recoverable, an orphaned key is invisible. */
    suspend fun remove(id: String) {
        val account = repository.account(id) ?: return
        credentials.delete(account.credentialReference)
        repository.remove(id)
    }
}
