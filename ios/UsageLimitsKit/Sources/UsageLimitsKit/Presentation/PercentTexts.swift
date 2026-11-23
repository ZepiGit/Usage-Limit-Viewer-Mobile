import Foundation

/// The words a percentage is composed from, for the compositions that live in the kit.
///
/// The kit never looks a word up itself (see `CountdownTexts`): `EnglishPercentTexts` is the
/// default for pure callers and tests, and the app target supplies the catalog-backed one.
public protocol PercentTexts: Sendable {
    /// A known token with its percent sign: "73%", or "73 %" where the language spaces it.
    func withSign(_ token: String) -> String

    /// `figure` already carries its sign: "45% remaining" in Left, "55% used" in Used.
    func spoken(_ figure: String, _ mode: PercentDisplay) -> String
}

public extension PercentTexts {
    /// A localized figure: with its sign, except the unknown dash, which stays
    /// bare rather than "—%".
    func figure(_ token: String) -> String {
        token == PercentToken.unknown ? token : withSign(token)
    }
}

/// Visible widget figures keep the percent sign attached in every language.
public enum WidgetPercentText {
    public static func figure(_ token: String) -> String {
        token == PercentToken.unknown ? token : "\(token)%"
    }
}

/// The English the app's catalog keys are written in.
public struct EnglishPercentTexts: PercentTexts {
    public init() {}

    public func withSign(_ token: String) -> String { "\(token)%" }

    public func spoken(_ figure: String, _ mode: PercentDisplay) -> String {
        mode == .used ? "\(figure) used" : "\(figure) remaining"
    }
}
