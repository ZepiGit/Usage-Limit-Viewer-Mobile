import Foundation

/// What a ring writes in its centre: the figure with its sign, the figure alone, or nothing.
///
/// A ring surface decision, not a presentation one: `WindowPresentation` keeps its "100" token,
/// so the spoken description and every other surface still say the full figure. The same rule,
/// on the same inputs, is Android's `ringCentre`; `shared/test-vectors/ring-centre.json` holds
/// both to it.
public enum RingCentre: Equatable, Sendable {
    /// The token with its percent sign ("42%"; the unknown dash stays bare).
    case signed
    /// The token without its sign: a 100 the ring itself does not show, which fits where "100%"
    /// does not.
    case bare
    /// Nothing: the closed ring says it.
    case nothing

    /// The centre for a presentation's `token` and `side` on a ring that does or does not draw
    /// its arc (#38). In Used a 100 is the exhausted window and is written bare. In Left a 100 is
    /// the full allowance: under its closed arc nothing is written, and where no arc is drawn it
    /// is written bare, never with the sign. Every other token keeps its sign. ">99" is its own
    /// token, so a rounded 99.6 never counts as 100.
    public static func of(token: String, side: PercentDisplay, arcDrawn: Bool) -> RingCentre {
        guard token == fullRingToken else { return .signed }
        return side == .left && arcDrawn ? .nothing : .bare
    }

    private static let fullRingToken = "100"
}
