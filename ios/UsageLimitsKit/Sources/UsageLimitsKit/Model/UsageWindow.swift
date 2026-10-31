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

public struct UsageSegment: Codable, Sendable, Equatable {
    public let label: String
    public let usedPercent: Double

    public init(label: String, usedPercent: Double) {
        self.label = label
        self.usedPercent = usedPercent
    }
}

/// One quota bar, normalised from whatever shape the provider used.
///
/// `usedPercent` is 0…100 **consumed**; `remainingPercent` is derived. Every provider reports
/// consumption while the UI reads "82 % remaining", and conflating the two is the single
/// easiest way to render a confidently inverted number.
public struct UsageWindow: Sendable, Codable, Equatable {
    public private(set) var id: String
    public let label: String
    public let category: WindowCategory
    public let usedPercent: Double?
    public let periodSeconds: Int64?
    public let resetAt: Date?
    public let exhausted: Bool
    /// Optional grouping key — an Antigravity quota group, or Codex "code review".
    public let group: String?
    public let scope: UsageScope
    public let segments: [UsageSegment]

    public init(
        id: String,
        label: String,
        category: WindowCategory,
        usedPercent: Double?,
        periodSeconds: Int64?,
        resetAt: Date?,
        exhausted: Bool,
        group: String? = nil,
        scope: UsageScope = .global,
        segments: [UsageSegment] = []
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
        self.segments = segments
    }

    public func with(id: String) -> UsageWindow {
        var copy = self
        copy.id = id
        return copy
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
            scope: try c.decodeIfPresent(UsageScope.self, forKey: .scope) ?? legacyScope,
            segments: try c.decodeIfPresent([UsageSegment].self, forKey: .segments) ?? [])
    }

    /// Round only for display; boundary labels must not imply exhausted or full.
    ///
    /// A thin wrapper over `percentToken` in the historical LEFT reading, keeping the
    /// `%`-suffixed strings existing call sites render; new surfaces take the token and compose
    /// it with a locale template, so the wording lives in resources rather than here.
    public static func percentLabel(_ percent: Double?) -> String {
        let token = percentToken(percent, exhausted: false, mode: .left)
        return token == PercentToken.unknown ? token : "\(token)%"
    }

    /// The figure token for a window in `mode`: the number only, no `%` — the UI composes it with
    /// a locale template, and tokens (not formatted strings) are the parity contract between this
    /// table and the Android one.
    ///
    /// Exhaustion overrides the number: the provider's verdict is the figure, the same precedence
    /// the LEFT treatment applies when a blocker with a full-looking percentage stays empty. The
    /// check comes first, so an exhausted window with an unusable percentage still reads 100 in
    /// USED mode.
    public static func percentToken(
        _ remaining: Double?, exhausted: Bool, mode: PercentDisplay
    ) -> String {
        if mode == .used && exhausted { return PercentToken.exhausted }
        guard let figure = displayPercent(remaining, mode),
              figure.isFinite, (0...100).contains(figure) else {
            return PercentToken.unknown
        }
        if figure > 0 && figure < 1 { return PercentToken.underOne }
        if figure < 100 && figure.rounded() == 100 { return PercentToken.overNinetyNine }
        return String(Int(figure.rounded()))
    }

    /// The figure this window shows in `mode`, derived from the already-clamped
    /// `remainingPercent`. Nil stays nil: a window with no usable percentage has no figure in
    /// either mode. Exhaustion deliberately does not enter here — it is a labelling concern,
    /// handled by `percentToken`.
    public func displayPercent(_ mode: PercentDisplay) -> Double? {
        Self.displayPercent(remainingPercent, mode)
    }

    /// `displayPercent` for a raw remaining figure, mirroring the Android `displayPercent`.
    public static func displayPercent(_ remaining: Double?, _ mode: PercentDisplay) -> Double? {
        switch mode {
        case .left: return remaining
        case .used: return remaining.map { 100.0 - $0 }
        }
    }

    /// The fraction a bar or ring fills in `mode` (D4).
    ///
    /// USED flips what full means: a window the provider calls exhausted fills completely,
    /// agreeing with its "100" label even when no usable percentage is behind it. A window with
    /// no figure fills nothing in either mode — unknown never becomes "100 − 0".
    public static func fillFraction(
        _ remaining: Double?, exhausted: Bool, mode: PercentDisplay
    ) -> Double? {
        if mode == .used && exhausted { return 1.0 }
        return displayPercent(remaining, mode).map { $0 / 100.0 }
    }

    /// `fillFraction` for this window; the stored `exhausted` flag decides, never an aged severity.
    public func fillFraction(_ mode: PercentDisplay) -> Double? {
        Self.fillFraction(remainingPercent, exhausted: exhausted, mode: mode)
    }

    /// The figure token for this window in `mode`, from the stored `exhausted` flag.
    public func percentToken(_ mode: PercentDisplay) -> String {
        Self.percentToken(remainingPercent, exhausted: exhausted, mode: mode)
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

    /// Reset passed after `fetchedAt`: the figure describes a closed window until a fetch confirms.
    public func resetElapsed(fetchedAt: Date, at now: Date) -> Bool {
        guard let resetAt else { return false }
        return resetAt <= now && fetchedAt < resetAt
    }
}
