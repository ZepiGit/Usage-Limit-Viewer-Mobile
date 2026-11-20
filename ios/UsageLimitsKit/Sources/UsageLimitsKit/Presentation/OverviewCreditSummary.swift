import Foundation

public struct OverviewCreditSummary: Sendable {
    public struct Winner: Sendable {
        public let accountID: String
        public let title: String
        public let balanceIndex: Int
        public let balance: CreditBalance
    }

    public let count: Int
    public let winner: Winner?

    public init(entries: [CreditEntry], now: Date, staleAfter: TimeInterval) {
        count = entries.count
        var best: (winner: Winner, threshold: Decimal)?
        for entry in entries {
            guard let snapshot = entry.snapshot,
                  snapshot.status == .ok || snapshot.error == StoredMessage.rateLimitedPauseMessage,
                  CreditSeverity.aged(base: .healthy, fetchedAt: snapshot.fetchedAt,
                    now: now, staleAfter: staleAfter) != .stale else { continue }
            for (index, balance) in snapshot.balances.enumerated() where !balance.unlimited {
                let threshold = entry.account.lowThreshold(for: balance.currency)
                guard !balance.amount.isNaN, !threshold.isNaN, threshold > 0,
                      balance.amount < threshold else { continue }
                // Positive thresholds allow exact cross-comparison without dividing or rounding the ratios.
                if let best, balance.amount * best.threshold >= best.winner.balance.amount * threshold { continue }
                best = (Winner(accountID: entry.id, title: entry.account.label, balanceIndex: index, balance: balance),
                    threshold)
            }
        }
        winner = best?.winner
    }
}
