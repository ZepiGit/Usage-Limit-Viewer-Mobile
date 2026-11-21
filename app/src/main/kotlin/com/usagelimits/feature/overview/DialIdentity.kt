package com.usagelimits.feature.overview

import com.usagelimits.core.model.ProviderAccount
import com.usagelimits.core.model.compareUnicodeScalars

internal sealed interface DialIdentity {
    data object ProviderOnly : DialIdentity
    data class Email(val address: String) : DialIdentity
    data class Number(val position: Int) : DialIdentity
}

/** The shared same-provider identity rule; reading rank never changes an account's number. */
internal fun dialIdentities(accounts: List<ProviderAccount>): Map<String, DialIdentity> {
    val identities = HashMap<String, DialIdentity>(accounts.size)
    for (siblings in accounts.groupBy { it.provider }.values) {
        if (siblings.size == 1) {
            identities[siblings.single().localId] = DialIdentity.ProviderOnly
            continue
        }
        val emails = siblings.associate { it.localId to it.maskedEmail }
        val counts = emails.values.filterNotNull().groupingBy { it }.eachCount()
        val creationOrder = siblings.sortedWith(
            compareBy<ProviderAccount> { it.createdAt }
                .thenComparator { a, b -> compareUnicodeScalars(a.localId, b.localId) },
        )
        creationOrder.forEachIndexed { index, account ->
            val email = emails[account.localId]
            identities[account.localId] = if (email != null && counts[email] == 1) {
                DialIdentity.Email(email)
            } else {
                DialIdentity.Number(index + 1)
            }
        }
    }
    return identities
}
