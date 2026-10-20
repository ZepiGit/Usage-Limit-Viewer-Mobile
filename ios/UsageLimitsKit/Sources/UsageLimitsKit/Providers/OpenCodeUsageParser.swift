import Foundation

/// Reads OpenCode Go's `GET /zen/go/v1/usage`.
///
/// `usage.rolling` is the five-hour window, `weekly` the week and `monthly` the month; each has
/// `status`, `percent` (integer, USED) and `resetsAt` (ISO-8601). Only status `ok` is trusted —
/// any other status, or a missing or out-of-range percent, leaves that window unknown rather
/// than guessing. The month is nominally 30 days; the real period follows the subscription date.
public enum OpenCodeUsageParser {

    private static let windows: [(key: String, label: String, seconds: Int64)] = [
        ("rolling", "5h limit", 5 * 3_600),
        ("weekly", "Weekly", 7 * 86_400),
        ("monthly", "Monthly", 30 * 86_400),
    ]

    public static func parse(_ payload: [String: Any]) -> [UsageWindow] {
        let usage = JSONSupport.object(payload, "usage")
        return windows.compactMap { key, label, seconds in
            guard let entry = JSONSupport.object(usage, key) else { return nil }
            let ok = JSONSupport.string(entry, "status")?.lowercased() == "ok"
            let percent = ok ? JSONSupport.double(entry, "percent") : nil
            let valid = percent.flatMap { $0.isFinite && (0...100).contains($0) ? $0 : nil }
            return UsageWindow(
                id: "opencode-\(key)",
                label: label,
                category: WindowCategory.from(periodSeconds: seconds),
                usedPercent: valid,
                periodSeconds: seconds,
                resetAt: JSONSupport.date(JSONSupport.string(entry, "resetsAt")),
                exhausted: valid == 100)
        }
    }
}
