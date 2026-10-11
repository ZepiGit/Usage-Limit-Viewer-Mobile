import Foundation

/// The single scale the whole app colours by.
///
/// Order runs best to worst so `max()` picks the worst state across an account's windows.
/// Mirrors the Android `Severity` enum deliberately: both platforms must classify identically,
/// or the same account reads differently on a phone and a tablet.
public enum Severity: Int, Comparable, Sendable, Codable {
    case healthy = 0
    case medium = 1
    case low = 2
    case exhausted = 3
    case stale = 4
    case error = 5

    // Existing raw values are persisted in the app-group cache.
    case limited = 6
    case unknown = 7

    private var order: Int {
        switch self {
        case .healthy: return 0
        case .medium: return 1
        case .low: return 2
        case .limited: return 3
        case .exhausted: return 4
        case .unknown: return 5
        case .stale: return 6
        case .error: return 7
        }
    }

    public static func < (lhs: Severity, rhs: Severity) -> Bool { lhs.order < rhs.order }

    /// Central thresholds — the one place these numbers are defined.
    public static let healthyAbove = 50.0
    public static let mediumAbove = 20.0

    /// Default age beyond which a snapshot reads as stale.
    ///
    /// Only a default. Prefer `staleAfter(syncIntervalMinutes:)`, which derives the threshold
    /// from the interval the user actually chose.
    public static let staleAfter: TimeInterval = 60 * 60

    /// Never call a snapshot stale before it has had a fair chance to refresh.
    ///
    /// A floor as well as a multiple: two fifteen-minute periods is half an hour, and a phone
    /// in a pocket routinely defers background work by more than that.
    private static let minimumStaleAfter: TimeInterval = 45 * 60

    /// How old a snapshot may be before it stops being trustworthy, given the refresh interval.
    ///
    /// A fixed hour was a bug at a setting the app itself offers: the interval can be set to
    /// three hours, and every snapshot would then be older than an hour by the time the next
    /// arrived — so every account read stale permanently, greyed out and ranked as "cannot tell
    /// you" no matter how healthy it was. Two missed refreshes is the signal worth acting on,
    /// so the threshold follows the interval rather than the clock.
    public static func staleAfter(syncIntervalMinutes: Int) -> TimeInterval {
        // Converted before multiplying. `2 * minutes * 60` is Int arithmetic and traps past
        // Int.max / 120; the interval comes off a settings file with a floor and no ceiling.
        max(TimeInterval(syncIntervalMinutes) * 120, minimumStaleAfter)
    }

    public static func from(remainingPercent: Double?, exhausted: Bool = false) -> Severity {
        if exhausted { return .exhausted }
        guard let remaining = remainingPercent, remaining.isFinite, (0...100).contains(remaining) else { return .unknown }
        if remaining <= 0 { return .exhausted }
        if remaining <= mediumAbove { return .low }
        if remaining <= healthyAbove { return .medium }
        return .healthy
    }
}
