package com.usagelimits.widget

import com.usagelimits.core.model.CreditBalance
import com.usagelimits.core.model.CreditEntry
import com.usagelimits.core.model.CreditSeverity
import com.usagelimits.core.model.Severity

/** One credit account as the widget draws it. Holds no key and no provider payload. */
data class CreditWidgetRow(
    val accountId: String,
    val title: String,
    val providerName: String,
    /** The worst balance first, so the figure shown is the one the colour is about. */
    val balances: List<CreditBalance>,
    val severity: Severity,
    /** Null for an account that has never been read. */
    val fetchedAt: Long?,
    val statusMessage: String?,
)

data class CreditWidgetSnapshot(val rows: List<CreditWidgetRow>) {
    companion object {
        val Empty = CreditWidgetSnapshot(emptyList())
    }
}

/**
 * Reduces the credit cache to what the Credits widget draws: every account, worst first.
 * Pure over already-loaded data, so widget rendering never touches a provider or the credential
 * store.
 */
object CreditWidgetBuilder {

    const val SMALL_ACCOUNTS = 1
    const val MEDIUM_ACCOUNTS = 3
    const val LARGE_ACCOUNTS = 6

    /**
     * Worst first by the same urgency ranking the overview and the usage widgets use. Ties keep
     * the user's order (the sort is stable), so two healthy accounts never swap on a refresh.
     */
    fun build(entries: List<CreditEntry>, nowMs: Long, staleAfterMs: Long): CreditWidgetSnapshot =
        CreditWidgetSnapshot(
            entries.map { entry ->
                CreditWidgetRow(
                    accountId = entry.id,
                    title = entry.account.label,
                    providerName = entry.account.provider.displayName,
                    balances = CreditSeverity.worstFirst(entry),
                    severity = CreditSeverity.of(entry, nowMs, staleAfterMs),
                    fetchedAt = entry.snapshot?.takeIf { it.balances.isNotEmpty() }?.fetchedAt,
                    statusMessage = entry.snapshot?.error,
                )
            }.sortedBy { it.severity.urgency },
        )

    /** How many accounts a widget of this size shows: 1, 3 or 6. */
    fun capacity(widthDp: Float, heightDp: Float): Int = when {
        heightDp >= 230f -> LARGE_ACCOUNTS
        widthDp >= 230f -> MEDIUM_ACCOUNTS
        else -> SMALL_ACCOUNTS
    }
}
