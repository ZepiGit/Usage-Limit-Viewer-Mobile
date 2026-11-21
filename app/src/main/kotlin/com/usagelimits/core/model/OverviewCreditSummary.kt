package com.usagelimits.core.model

import com.usagelimits.core.sync.RATE_LIMITED_PAUSE_MESSAGE
import java.math.BigDecimal

data class OverviewCreditSummary(
    val count: Int,
    val winner: OverviewCreditWinner?,
)

data class OverviewCreditWinner(
    val account: CreditAccount,
    val balanceIndex: Int,
    val balance: CreditBalance,
)

internal fun List<CreditEntry>.overviewCreditSummary(nowMs: Long, staleAfterMs: Long): OverviewCreditSummary {
    var winner: OverviewCreditWinner? = null
    var winnerThreshold: BigDecimal? = null

    for (entry in this) {
        val snapshot = entry.snapshot ?: continue
        if (snapshot.balances.isEmpty()) continue
        if (snapshot.status != CreditStatus.OK && snapshot.error != RATE_LIMITED_PAUSE_MESSAGE) continue
        if (CreditSeverity.aged(Severity.HEALTHY, snapshot.fetchedAt, nowMs, staleAfterMs) == Severity.STALE) continue

        for ((index, balance) in snapshot.balances.withIndex()) {
            if (balance.unlimited) continue
            val threshold = entry.account.lowThreshold(balance.currency)
            if (balance.amount >= threshold) continue

            val currentThreshold = winnerThreshold
            val currentWinner = winner
            if (currentThreshold == null || currentWinner == null ||
                balance.amount.multiply(currentThreshold) < currentWinner.balance.amount.multiply(threshold)
            ) {
                winner = OverviewCreditWinner(entry.account, index, balance)
                winnerThreshold = threshold
            }
        }
    }

    return OverviewCreditSummary(count = size, winner = winner)
}
