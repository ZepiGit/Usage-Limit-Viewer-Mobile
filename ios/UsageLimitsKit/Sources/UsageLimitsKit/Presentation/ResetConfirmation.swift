import Foundation

public extension UsageSnapshot {
    /// Dated evidence only; the repository supplies readings from the same account instance.
    func confirmedResetWindowIDs(previous: UsageSnapshot?) -> [String] {
        guard let previous, previous.accountID == accountID, status != .failed else { return [] }
        return windows.compactMap { window in
            guard window.measured, window.remainingPercent != nil,
                  let old = previous.windows.first(where: { $0.id == window.id }),
                  old.measured, old.remainingPercent != nil,
                  let oldReset = old.resetAt, let nextReset = window.resetAt,
                  previous.fetchedAt < oldReset, fetchedAt >= oldReset,
                  nextReset > oldReset, nextReset > fetchedAt else { return nil }
            return window.id
        }
    }
}
