import Foundation

/// Reads Z.ai's `GET /api/monitor/usage/quota/limit` (GLM Coding Plan).
///
/// Undocumented; the shape comes from other open-source monitors. `data.limits[]` carries one
/// entry per window, typed TOKENS_LIMIT, TIME_LIMIT or CREDIT_LIMIT — all three are read the
/// same way, because plans differ in which they use. The window length is `number` of `unit`,
/// and the usage is `percentage` (percent USED) when present, else `currentValue / usage`
/// (`usage` is the cap, despite its name). Max-plan entries may carry only a percentage.
///
/// An unreadable or out-of-range figure makes that window unknown, never zero and never full.
public enum ZaiUsageParser {

    /// Minutes per `unit`: 1 day, 3 hour, 5 minute, 6 week.
    private static let unitMinutes: [Int64: Int64] = [1: 1440, 3: 60, 5: 1, 6: 10_080]
    private static let monthSeconds: Int64 = 30 * 24 * 60 * 60

    public static func parse(_ payload: [String: Any]) -> [UsageWindow] {
        JSONSupport.array(JSONSupport.object(payload, "data"), "limits").enumerated().compactMap { index, element in
            guard let entry = element as? [String: Any] else { return nil }
            let type = (JSONSupport.string(entry, "type") ?? "").uppercased()
            let seconds = periodSeconds(type: type, entry)
            let category = WindowCategory.from(periodSeconds: seconds)
            let percent = usedPercent(entry)
            return UsageWindow(
                id: "zai-\(type.lowercased())-\(seconds.map(String.init) ?? "\(index)")",
                label: label(category, type: type),
                category: category,
                usedPercent: percent,
                periodSeconds: seconds,
                resetAt: JSONSupport.date(epoch: JSONSupport.int64(entry, "nextResetTime")),
                exhausted: percent == 100)
        }
    }

    /// The plan tier (`lite`, `pro`, `max`), as a label.
    public static func plan(_ payload: [String: Any]) -> String? {
        planLabel(JSONSupport.string(JSONSupport.object(payload, "data"), "level"))
    }

    private static func periodSeconds(type: String, _ entry: [String: Any]) -> Int64? {
        guard let unit = JSONSupport.int64(entry, "unit"), let number = JSONSupport.int64(entry, "number"),
              number > 0 else { return nil }
        // The monthly MCP allowance is sent as unit 5 (minute) number 1; read it as the month.
        if type == "TIME_LIMIT", unit == 5, number == 1 { return monthSeconds }
        guard let perUnit = unitMinutes[unit] else { return nil }
        let (seconds, overflow) = number.multipliedReportingOverflow(by: perUnit * 60)
        return overflow ? nil : seconds
    }

    private static func usedPercent(_ entry: [String: Any]) -> Double? {
        let used: Double
        if let percentage = JSONSupport.double(entry, "percentage") {
            used = percentage
        } else if let cap = JSONSupport.double(entry, "usage"), cap.isFinite, cap > 0,
                  let current = JSONSupport.double(entry, "currentValue") {
            used = current / cap * 100
        } else {
            return nil
        }
        return used.isFinite && (0...100).contains(used) ? used : nil
    }

    private static func label(_ category: WindowCategory, type: String) -> String {
        switch category {
        case .fiveHour: return "5h limit"
        case .weekly: return "Weekly"
        case .monthly: return "Monthly"
        case .other:
            switch type {
            case "TOKENS_LIMIT": return "Token limit"
            case "TIME_LIMIT": return "Time limit"
            case "CREDIT_LIMIT": return "Credit limit"
            default: return "Limit"
            }
        }
    }
}
