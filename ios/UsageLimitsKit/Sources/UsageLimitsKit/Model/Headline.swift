import Foundation

/// What the headline rule needs to know about one usage window.
///
/// Two shapes carry a window: `UsageWindow` in the cache and `GlanceRow` in the widget export.
/// Both conform, so the account headline is defined once here and the widget process, which
/// only ever sees rows, cannot drift into a rule of its own.
public protocol HeadlineCandidate {
    var scope: UsageScope { get }
    var remainingPercent: Double? { get }
    var resetAt: Date? { get }
    /// Exhausted means the provider said so or nothing is left; both rank the same.
    var severity: Severity { get }
}

extension UsageWindow: HeadlineCandidate {}

public extension HeadlineCandidate {
    /// Whether this window's reset has come since it was read, so its figure describes a window
    /// that already ended. Without a fetch instant there is no proof the reading is newer than
    /// the reset, so any passed reset counts as due.
    func resetDue(fetchedAt: Date?, at now: Date) -> Bool {
        guard let resetAt, resetAt <= now else { return false }
        guard let fetchedAt else { return true }
        return fetchedAt < resetAt
    }
}

public extension Array where Element: HeadlineCandidate {
    /// The account headline: the one window that speaks for the account at `now`.
    ///
    /// Windows whose reset is due drop out first — their figure belongs to a window that has
    /// ended — unless that leaves none, when all stay so the account still says something. Among
    /// what remains, global windows alone compete if there are any: a scoped pool running dry
    /// does not block the account. An exhausted window wins, then the lowest remaining
    /// percentage; an unknown percentage loses to any known one, because leading with a blank
    /// would hide the figure that is known. A tie goes to the first window in provider order.
    ///
    /// `HeadlineVectorsTests` proves this against the vector file Android's `headlineAt` runs too.
    func headline(fetchedAt: Date?, at now: Date) -> Element? {
        let open = filter { !$0.resetDue(fetchedAt: fetchedAt, at: now) }
        let pool = open.isEmpty ? self : open
        let global = pool.filter { $0.scope == .global }
        // `min(by:)` keeps the earlier element on a tie, which is the documented tie-break.
        return (global.isEmpty ? pool : global).min { headlineRank($0) < headlineRank($1) }
    }
}

/// The rank a candidate competes with: exhausted first, unknown last.
func headlineRank(_ candidate: some HeadlineCandidate) -> Double {
    candidate.severity == .exhausted ? -1 : (candidate.remainingPercent ?? .greatestFiniteMagnitude)
}

public extension UsageSnapshot {
    /// The account headline at `now`, judged against this snapshot's own fetch instant.
    func headline(at now: Date) -> UsageWindow? {
        windows.headline(fetchedAt: fetchedAt, at: now)
    }
}
