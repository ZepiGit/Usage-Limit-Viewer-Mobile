import Foundation

/// Which side of a quota the figures count: what is left, or what has been used.
///
/// `left` is the historical reading and stays the default everywhere, so an upgrade changes
/// nothing until the user asks for it. `used` is 100 − left, derived from the same clamped
/// remaining figure rather than from the raw `usedPercent`, so both modes describe the same
/// window and sum to 100 before rounding.
public enum PercentDisplay: String, Codable, Sendable {
    case left
    case used

    /// The override→app resolution for configured widgets: a nil override follows the app mode.
    public func resolved(override: PercentDisplay?) -> PercentDisplay {
        override ?? self
    }

    /// Lenient read: an unknown or missing stored raw value falls back to the historical default.
    ///
    /// Decoding through `String` rather than failable enum decoding keeps one unknown key from
    /// failing a whole `AppSettings` or snapshot decode and resetting everything the user chose.
    public static func fromLoose(raw: String?) -> PercentDisplay {
        raw.flatMap(PercentDisplay.init(rawValue:)) ?? .left
    }
}

/// Token values the localized templates key on (no `%` sign — the UI composes "73%" / "73 %").
public enum PercentToken {
    public static let unknown = "—"
    public static let underOne = "<1"
    public static let overNinetyNine = ">99"

    /// The token an exhausted window labels in USED mode, where the provider's verdict is the figure.
    public static let exhausted = "100"
}
