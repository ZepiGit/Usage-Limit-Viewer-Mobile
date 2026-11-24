import Foundation

public protocol PinnedAccountTexts: PercentTexts {
    var error: String { get }
    var signInExpired: String { get }
    var exhausted: String { get }
    var unknown: String { get }
    var outdated: String { get }
    /// The short side word written after a figure: "left" or "used".
    func sideWord(_ mode: PercentDisplay) -> String
    func account(number: Int) -> String
}

/// The English the app's catalog keys are written in.
public struct EnglishPinnedAccountTexts: PinnedAccountTexts {
    public init() {}

    public var error: String { "Error" }
    public var signInExpired: String { "Sign-in expired" }
    public var exhausted: String { "Exhausted" }
    public var unknown: String { "Unknown" }
    public var outdated: String { "Stale" }

    public func sideWord(_ mode: PercentDisplay) -> String { mode == .used ? "used" : "left" }
    public func account(number: Int) -> String { "Account \(number)" }
    public func withSign(_ token: String) -> String { EnglishPercentTexts().withSign(token) }
    public func spoken(_ figure: String, _ mode: PercentDisplay) -> String {
        EnglishPercentTexts().spoken(figure, mode)
    }
}
