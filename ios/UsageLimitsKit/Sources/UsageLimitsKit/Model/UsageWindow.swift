import Foundation

/// How long a quota window spans.
///
/// Derived from the duration the provider reports, never from the window's position in the
/// payload. That distinction is load-bearing: Codex puts a *monthly* window in the secondary
/// slot on team plans, so trusting position labels a month as a week.
public enum WindowCategory: String, Sendable, Codable {
    case fiveHour
    case weekly
    case monthly
    case other

    private static let fiveHourSeconds: Int64 = 5 * 60 * 60          // 18_000
    private static let weekSeconds: Int64 = 7 * 24 * 60 * 60         // 604_800
    private static let minMonthSeconds: Int64 = 28 * 24 * 60 * 60    // 2_419_200
    private static let maxMonthSeconds: Int64 = 31 * 24 * 60 * 60    // 2_678_400

    /// Months are a range, not a constant: calendar months differ in length and providers
    /// report the real period. Anything unrecognised stays `.other` so an unknown future
    /// window still renders rather than being forced into the wrong bucket.
    public static func from(periodSeconds: Int64?) -> WindowCategory {
        guard let seconds = periodSeconds else { return .other }
        switch seconds {
        case fiveHourSeconds: return .fiveHour
        case weekSeconds: return .weekly
        case minMonthSeconds...maxMonthSeconds: return .monthly
        default: return .other
        }
    }
}

/// Global constraints, independent model/feature pools, or a scope the provider did not identify.
public enum UsageScope: String, Sendable, Codable {
    case global, model, unknown
}

/// One quota bar, normalised from whatever shape the provider used.
///
/// `usedPercent` is 0…100 **consumed**; `remainingPercent` is derived. Every provider reports
/// consumption while the UI reads "82 % remaining", and conflating the two is the single
/// easiest way to render a confidently inverted number.
public struct UsageWindow: Sendable, Codable, Equatable {
    public let id: String
    public let label: String
    public let category: WindowCategory
    public let usedPercent: Double?
    public let periodSeconds: Int64?
    public let resetAt: Date?
    public let exhausted: Bool
    /// Optional grouping key — an Antigravity quota group, or Codex "code review".
    public let group: String?
    public let scope: UsageScope

    public init(
        id: String,
        label: String,
        category: WindowCategory,
        usedPercent: Double?,
        periodSeconds: Int64?,
        resetAt: Date?,
        exhausted: Bool,
        group: String? = nil,
        scope: UsageScope = .global
    ) {
        self.id = id
        self.label = label
        self.category = category
        self.usedPercent = usedPercent
        self.periodSeconds = periodSeconds
        self.resetAt = resetAt
        self.exhausted = exhausted
        self.group = group
        self.scope = scope
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let id = try c.decode(String.self, forKey: .id)
        let group = try c.decodeIfPresent(String.self, forKey: .group)
        // Older caches predate scope. Preserve known model identities until the next fetch.
        let legacyScope: UsageScope
        if id.hasPrefix("seven-day-") || group != nil || id == "xai-on-demand" {
            legacyScope = .model
        } else if id == "five-hour" || id == "seven-day"
            || ["codex-", "kimi-", "xai-", "meta-", "devin-"].contains(where: { id.hasPrefix($0) }) {
            legacyScope = .global
        } else {
            // A legacy codename has no documented global scope. Refresh will identify it.
            legacyScope = .unknown
        }
        self.init(
            id: id, label: try c.decode(String.self, forKey: .label),
            category: try c.decode(WindowCategory.self, forKey: .category),
            usedPercent: try c.decodeIfPresent(Double.self, forKey: .usedPercent),
            periodSeconds: try c.decodeIfPresent(Int64.self, forKey: .periodSeconds),
            resetAt: try c.decodeIfPresent(Date.self, forKey: .resetAt),
            exhausted: try c.decode(Bool.self, forKey: .exhausted), group: group,
            scope: try c.decodeIfPresent(UsageScope.self, forKey: .scope) ?? legacyScope)
    }

    /// Round only for display; boundary labels must not imply exhausted or full.
    public static func percentLabel(_ percent: Double?) -> String {
        guard let percent, percent.isFinite, (0...100).contains(percent) else { return "—" }
        if percent > 0 && percent < 1 { return "<1%" }
        if percent < 100 && percent.rounded() == 100 { return ">99%" }
        return "\(Int(percent.rounded()))%"
    }

    public var remainingPercent: Double? {
        // A non-finite figure is UNKNOWN, not zero and not full. A limit of zero divided into
        // anything yields one, and clamping propagates it rather than catching it: NaN compares
        // false against every bound, so `min`/`max` hand it straight back. Downstream it reaches
        // an `Int(_:)` conversion, which in Swift traps rather than saturating — inside a widget
        // extension that is a dead tile with nothing anywhere saying why.
        guard let used = usedPercent, used.isFinite, (0...100).contains(used) else { return nil }
        return 100.0 - used
    }

    public var hasConflictingAvailability: Bool {
        exhausted && (remainingPercent.map { $0 > 0 } ?? false)
    }

    public var severity: Severity {
        Severity.from(remainingPercent: remainingPercent, exhausted: exhausted)
    }
}
