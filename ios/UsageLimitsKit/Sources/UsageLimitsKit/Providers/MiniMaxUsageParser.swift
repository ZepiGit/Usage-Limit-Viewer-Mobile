import Foundation

/// Reads the MiniMax Token Plan's `model_remains[]`, one five-hour and one weekly window per model.
///
/// The ambiguity this parser exists for is in `*_usage_count`: older responses state a count that
/// REMAINS, newer ones state a count that was CONSUMED, and the field is spelled the same either
/// way. MiniMax's own CLI resolves it with `resolveQuotaCounts` (MiniMax-AI/cli, commit
/// ae29aae57b05c2140c7ea6aba0a852763ffe7859, `src/utils/quota.ts`), which is reproduced here
/// rather than approximated: the reported count is read as remaining unless a
/// `*_remaining_percent` is present and agrees better with the *consumed* reading, in which case
/// the count is inverted. A percentage that agrees with neither reading within one point makes the
/// window unknown instead of guessing.
///
/// `status` follows the CLI's own comment in `src/types/api.ts`: 1 limited (the counts decide),
/// 2 exhausted (100 % used), 3 unlimited (no bar). An unlimited plan states no weekly limit either.
public enum MiniMaxUsageParser {

    private static let fiveHourSeconds: Int64 = 5 * 3_600
    private static let weekSeconds: Int64 = 7 * 86_400

    private static let statusExhausted: Int64 = 2
    private static let statusUnlimited: Int64 = 3

    /// The CLI's `PERCENT_MATCH_TOLERANCE`: one percentage point of rounding.
    private static let percentMatchTolerance = 1.0

    public static func parse(_ payload: [String: Any]) -> [UsageWindow] {
        var ids = StableWindowIDs()
        var windows: [UsageWindow] = []
        for element in JSONSupport.array(payload, "model_remains") {
            guard let entry = element as? [String: Any] else { continue }
            // Without a model name there is no stable id to file the window under.
            guard let name = JSONSupport.string(entry, "model_name") else { continue }
            windows += modelWindows(entry, name: name, ids: &ids)
        }
        return windows
    }

    private static func modelWindows(
        _ entry: [String: Any], name: String, ids: inout StableWindowIDs
    ) -> [UsageWindow] {
        let fiveHour = window(
            id: ids.next("\(name):5h"),
            label: "\(name) · 5-hour",
            category: .fiveHour,
            periodSeconds: fiveHourSeconds,
            usedPercent: usedPercent(
                status: JSONSupport.int64(entry, "current_interval_status", "status"),
                reported: JSONSupport.double(entry, "current_interval_usage_count"),
                total: JSONSupport.double(entry, "current_interval_total_count"),
                remainingPercent: JSONSupport.double(entry, "current_interval_remaining_percent")),
            resetAt: JSONSupport.date(epoch: JSONSupport.int64(entry, "end_time")))

        let weeklyStatus = JSONSupport.int64(entry, "current_weekly_status", "status")
        let weeklyTotal = JSONSupport.double(entry, "current_weekly_total_count")
        // A plan without a weekly allowance states a zero total, and an unlimited one says so in
        // the status; neither is a 0 % window.
        let weekly: UsageWindow?
        if weeklyStatus == statusUnlimited || weeklyTotal == nil || (weeklyTotal ?? 0) <= 0 {
            weekly = nil
        } else {
            weekly = window(
                id: ids.next("\(name):weekly"),
                label: "\(name) · Weekly",
                category: .weekly,
                periodSeconds: weekSeconds,
                usedPercent: usedPercent(
                    status: weeklyStatus,
                    reported: JSONSupport.double(entry, "current_weekly_usage_count"),
                    total: weeklyTotal,
                    remainingPercent: JSONSupport.double(entry, "current_weekly_remaining_percent")),
                resetAt: JSONSupport.date(epoch: JSONSupport.int64(entry, "weekly_end_time")))
        }

        return [fiveHour, weekly].compactMap { $0 }
    }

    /// The provider's own verdict on a window, or the counts where it states none.
    private static func usedPercent(
        status: Int64?, reported: Double?, total: Double?, remainingPercent: Double?
    ) -> Double? {
        switch status {
        case statusExhausted: return 100
        case statusUnlimited: return nil
        default: return resolveUsedPercent(reported, total: total, remainingPercent: remainingPercent)
        }
    }

    /// Used share from the CLI's `resolveQuotaCounts`, then `used = total - remaining`.
    ///
    /// A zero or unusable total, a negative count, a count above the total, or a percentage that
    /// matches neither reading leaves the window unknown: the server stated something this parser
    /// cannot reconcile, and a bar drawn from a guess is worse than a dash.
    private static func resolveUsedPercent(
        _ reported: Double?, total: Double?, remainingPercent: Double?
    ) -> Double? {
        guard let reported, let total, reported.isFinite, total.isFinite else { return nil }
        guard total > 0, reported >= 0, reported <= total else { return nil }

        var remaining = reported
        if let remainingPercent, remainingPercent.isFinite {
            let asRemaining = reported / total * 100
            let asUsed = (total - reported) / total * 100
            let remainingDistance = abs(asRemaining - remainingPercent)
            let usedDistance = abs(asUsed - remainingPercent)
            if min(remainingDistance, usedDistance) > percentMatchTolerance { return nil }
            // The percentage is the authority; a tie keeps the legacy remaining reading.
            if usedDistance < remainingDistance { remaining = total - reported }
        }
        return (total - remaining) / total * 100
    }

    private static func window(
        id: String,
        label: String,
        category: WindowCategory,
        periodSeconds: Int64,
        usedPercent: Double?,
        resetAt: Date?
    ) -> UsageWindow {
        UsageWindow(
            id: id,
            label: label,
            category: category,
            usedPercent: usedPercent,
            periodSeconds: periodSeconds,
            resetAt: resetAt,
            exhausted: usedPercent == 100,
            // A token plan grants each model its own allowance, so a dry model is not a dry account.
            scope: .model)
    }
}
