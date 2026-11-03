import Foundation

/// The sentences `NotificationEvaluator` builds, handed in so the kit stays free of any bundle.
///
/// The evaluator decides WHAT is true and when; this decides how it is said. Injecting it,
/// rather than returning tokens for the caller to assemble, keeps one code path for every
/// language and lets one set of inputs assert the wording in each.
///
/// Every function takes whole pieces (an account name, a window label, a count) instead of
/// returning a fragment, because word order and plural forms differ between languages. Names and
/// labels are data — the user's own and the provider's — and are inserted untranslated; the one
/// exception is a window label the app chose itself, which `windowLabel` puts into the language first.
///
/// Not here: the sentence a rejected credential stores (`NotificationEvaluator.signInExpiredMessage`).
/// It is matched as data and written into snapshots, so it stays English; `StoredMessage` maps it
/// to a case at the display.
public protocol NotificationTexts: Sendable {
    /// Formats the deadline inside `creditExpiring`, in the same language as the sentence.
    var countdown: any CountdownTexts { get }

    /// The label to put into a sentence for a window: the app's own horizon names ("5h limit",
    /// "Weekly", "Monthly" on a global window) in this language, anything else as the provider
    /// reported it. The default is the label unchanged, so the screens and the notifications
    /// name a window with the same words.
    func windowLabel(_ label: String, category: WindowCategory, scope: UsageScope) -> String

    /// The first tier. The threshold itself stays on remaining quota; only the wording follows
    /// `mode`, so "less than 20% remaining" and "more than 80% used" are one crossing.
    func warning(name: String, label: String, mode: PercentDisplay) -> String

    func critical(name: String, label: String, mode: PercentDisplay) -> String

    func exhausted(name: String, label: String) -> String

    func resetApproaching(name: String, label: String, minutes: Int) -> String

    /// `duration` is already formatted with `countdown`.
    func creditExpiring(name: String, duration: String) -> String

    func creditsAvailable(name: String, count: Int) -> String

    func needsReconnect(name: String) -> String
}

extension NotificationTexts {
    public func windowLabel(_ label: String, category: WindowCategory, scope: UsageScope) -> String {
        label
    }
}

/// The wording the app shipped with, kept as the default so context-free callers are unchanged.
public struct EnglishNotificationTexts: NotificationTexts {
    public init() {}

    public var countdown: any CountdownTexts { EnglishCountdownTexts() }

    public func warning(name: String, label: String, mode: PercentDisplay) -> String {
        switch mode {
        case .left: return "\(name) · \(label): less than 20% remaining"
        case .used: return "\(name) · \(label): more than 80% used"
        }
    }

    public func critical(name: String, label: String, mode: PercentDisplay) -> String {
        switch mode {
        case .left: return "\(name) · \(label): less than 10% remaining"
        case .used: return "\(name) · \(label): more than 90% used"
        }
    }

    public func exhausted(name: String, label: String) -> String {
        "\(name) · \(label) exhausted"
    }

    public func resetApproaching(name: String, label: String, minutes: Int) -> String {
        // "1 minute": the evaluator rounds up, so a reset under a minute away reads "about 1".
        let unit = minutes == 1 ? "minute" : "minutes"
        return "\(name) · \(label) resets in about \(minutes) \(unit)"
    }

    public func creditExpiring(name: String, duration: String) -> String {
        "\(name) · a reset credit expires in \(duration)"
    }

    public func creditsAvailable(name: String, count: Int) -> String {
        let noun = count == 1 ? "reset credit" : "reset credits"
        return "\(name) · \(count) \(noun) available"
    }

    public func needsReconnect(name: String) -> String {
        "\(name) needs to be reconnected"
    }
}
