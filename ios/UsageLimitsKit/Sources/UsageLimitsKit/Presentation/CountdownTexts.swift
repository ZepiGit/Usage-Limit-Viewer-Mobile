import Foundation

/// The words `Countdown` composes its labels from.
///
/// The kit cannot reach a bundle or a String Catalog, and must stay buildable and testable on
/// Linux, so it never looks a word up itself. The arithmetic stays here in one code path and the
/// words are handed in: `EnglishCountdownTexts` is the default every pure caller and test gets,
/// and the app target supplies a catalog-backed implementation.
///
/// Each function takes the pieces (a count, an already formatted duration or clock time) rather
/// than returning a fragment to concatenate, because word order is not the same in every
/// language. Clock times arrive already formatted by `Countdown.absolute`, which asks the system
/// locale for them; only the sentence around them is the texts' business.
public protocol CountdownTexts: Sendable {
    /// A duration that has run out or is not positive.
    func now() -> String
    func daysHours(days: Int, hours: Int) -> String
    func days(_ days: Int) -> String
    func hoursMinutes(hours: Int, minutes: Int) -> String
    func hours(_ hours: Int) -> String
    func minutes(_ minutes: Int) -> String
    func underOneMinute() -> String

    /// `duration` is already formatted, e.g. "2d 4h".
    func resetsIn(duration: String) -> String

    /// The reading predates a reset that has since passed and fresh data is pending.
    func resetPending() -> String

    /// A reset still ahead: `stamp` is a localised time, or date and time when not today.
    func resetsAt(stamp: String) -> String

    /// `stamp` is a localised time, or date and time when it is not from today.
    func asOf(stamp: String) -> String

    /// The aggregate form of `asOf`: the newest fetch across several accounts, not one account's.
    func latestUpdate(stamp: String) -> String

    func updatedJustNow() -> String

    /// `duration` is already formatted, e.g. "37m".
    func updatedAgo(duration: String) -> String

    /// `label` is the window's own label, which is data and is inserted untranslated.
    func renews(label: String, duration: String) -> String
}

/// The wording the app shipped with, kept as the default so context-free callers are unchanged.
public struct EnglishCountdownTexts: CountdownTexts {
    public init() {}

    public func now() -> String { "now" }
    public func daysHours(days: Int, hours: Int) -> String { "\(days)d \(hours)h" }
    public func days(_ days: Int) -> String { "\(days)d" }
    public func hoursMinutes(hours: Int, minutes: Int) -> String { "\(hours)h \(minutes)m" }
    public func hours(_ hours: Int) -> String { "\(hours)h" }
    public func minutes(_ minutes: Int) -> String { "\(minutes)m" }
    public func underOneMinute() -> String { "<1m" }
    public func resetsIn(duration: String) -> String { "Resets in \(duration)" }
    public func resetPending() -> String { "Reset · refresh pending" }
    public func resetsAt(stamp: String) -> String { "Resets \(stamp)" }
    public func asOf(stamp: String) -> String { "As of \(stamp)" }
    public func latestUpdate(stamp: String) -> String { "Latest update \(stamp)" }
    public func updatedJustNow() -> String { "Updated just now" }
    public func updatedAgo(duration: String) -> String { "Updated \(duration) ago" }
    public func renews(label: String, duration: String) -> String { "\(label) renews \(duration)" }
}
