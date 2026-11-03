import Foundation

/// The words of the page `LoopbackListener` leaves the browser showing once a request has been
/// answered.
///
/// The listener is a socket in the kit and cannot reach a bundle or a String Catalog, so the
/// caller that can hands the words in. `EnglishLoopbackPageTexts` is the default every
/// context-free caller and test gets, the same arrangement as `CountdownTexts`. The page escapes
/// whatever it is given, so an implementation returns plain text.
///
/// The three sentences that explain a refused sign-in take their piece (the provider's error
/// code) rather than a finished sentence to splice into, because word order is not the same in
/// every language. The Android twin is `LoopbackPageTexts`; its fourth member, "no code
/// returned", is `noAuthorisationCode` here.
public protocol LoopbackPageTexts: Sendable {
    /// Title and heading of the page after a redirect that carried a code.
    func signedIn() -> String

    /// Title and heading of every page that is not a success.
    func signInFailed() -> String

    /// The line under the heading when the sign-in worked.
    func closeThisPage() -> String

    /// The line under the heading for a request that was not the redirect: a favicon fetch, a
    /// probe, a stray tab. Nobody is meant to read it, but a browser shows it.
    func nothingToSee() -> String

    /// The provider refused and said so on this attempt. `reason` is its own error code, which
    /// is data and is inserted untranslated.
    func providerRefused(reason: String) -> String

    /// The redirect named no error and carried no code either.
    func noAuthorisationCode() -> String

    /// The redirect could not be read at all.
    func signInUnreadable() -> String
}

/// The wording the page shipped with, kept as the default so context-free callers are unchanged.
///
/// The three refusal sentences are the ones `LoopbackRedirect.interpret` writes into
/// `Outcome.rejected`, which the listener also throws and the app shows; a test holds the two
/// in step, so the page cannot drift from the sentence the screen shows.
public struct EnglishLoopbackPageTexts: LoopbackPageTexts {
    public init() {}

    public func signedIn() -> String { "Signed in" }
    public func signInFailed() -> String { "Sign-in failed" }
    public func closeThisPage() -> String { "You can close this page and return to Usage Limits." }
    public func nothingToSee() -> String { "Nothing to see here." }
    public func providerRefused(reason: String) -> String { "The provider refused the sign-in (\(reason))." }
    public func noAuthorisationCode() -> String { "The sign-in came back without an authorisation code." }
    public func signInUnreadable() -> String { "The sign-in could not be read." }
}
