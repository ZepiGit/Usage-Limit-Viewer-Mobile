import Foundation

/// Reads Ollama Cloud's `GET /api/balance` as usage windows (docs.ollama.com/api/balance).
///
/// Two shapes share the `included` block. A current plan states `balance_usd` and `allowance_usd`
/// over a `period`; that is one window named after the allowance, and its used share is what has
/// been spent of the allowance. A legacy plan states `session` and `weekly` limits instead, each
/// with a `remaining_percent` (what REMAINS) and a `resets_at`.
///
/// Neither shape's absent block becomes a 0 % window: a block that is missing is omitted. The
/// documentation states no duration for the legacy session limit, and the included period follows
/// the plan's own reset schedule (annual plans included), so both keep their label and reset with
/// no category. `weekly` is a week.
public enum OllamaUsageParser {

    private static let weekSeconds: Int64 = 7 * 86_400

    /// Display order. The label and duration are the app's own, never the payload's field name.
    private static let progressLimits: [(key: String, label: String, seconds: Int64?)] = [
        ("session", "Session", nil),
        ("weekly", "Weekly", weekSeconds),
    ]

    public static func parse(_ payload: [String: Any]) -> [UsageWindow] {
        guard let included = JSONSupport.object(payload, "included") else { return [] }
        var ids = StableWindowIDs()
        var windows: [UsageWindow] = []
        for limit in progressLimits {
            guard let entry = JSONSupport.object(included, limit.key) else { continue }
            let used = remainingShare(JSONSupport.double(entry, "remaining_percent"))
            windows.append(UsageWindow(
                id: ids.next(limit.key),
                label: limit.label,
                category: WindowCategory.from(periodSeconds: limit.seconds),
                usedPercent: used,
                periodSeconds: limit.seconds,
                resetAt: JSONSupport.date(JSONSupport.string(entry, "resets_at")),
                exhausted: used == 100))
        }
        if let allowance = includedAllowance(included, ids: &ids) { windows.append(allowance) }
        return windows
    }

    /// `remaining_percent` is what REMAINS; the bar stores what is used. Out of range is unknown.
    private static func remainingShare(_ remaining: Double?) -> Double? {
        guard let remaining, remaining.isFinite, (0...100).contains(remaining) else { return nil }
        return 100 - remaining
    }

    /// The included credits as one window: used is what the allowance has lost. The two dollar
    /// figures are money, so they are read and subtracted as `Decimal` — a `Double` turns
    /// 0.3 − 0.1 into 0.19999999999999998 and the share into 66.66666666666667 instead of
    /// 66.666667. The division rounds to six places with bankers rounding, the same scale and
    /// rounding Android's parser uses. An allowance of zero (or a missing figure) has no share to
    /// draw and omits the window. The reset is the period's `until`; a null period invents none.
    private static func includedAllowance(
        _ included: [String: Any], ids: inout StableWindowIDs
    ) -> UsageWindow? {
        guard let balance = CreditJSON.decimal(included, "balance_usd"),
              let allowance = CreditJSON.decimal(included, "allowance_usd"),
              allowance > 0 else { return nil }
        var share = (allowance - balance) * 100 / allowance
        var rounded = Decimal()
        NSDecimalRound(&rounded, &share, percentScale, .bankers)
        let used = NSDecimalNumber(decimal: min(max(rounded, 0), 100)).doubleValue
        return UsageWindow(
            id: ids.next("included"),
            label: "Included allowance",
            category: .other,
            usedPercent: used,
            periodSeconds: nil,
            resetAt: JSONSupport.date(
                JSONSupport.string(JSONSupport.object(included, "period"), "until")),
            exhausted: used == 100)
    }

    /// Six places, bankers rounding: the scale and rounding Android's parser uses.
    private static let percentScale = 6
}
