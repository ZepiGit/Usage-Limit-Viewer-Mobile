import Foundation

/// What a ring writes in its centre: the figure with its sign, the token alone, or nothing.
///
/// A ring surface decision, not a presentation one: `WindowPresentation` keeps its "100" token,
/// so the spoken description and every other surface still say the full figure. Kept in the Kit
/// rather than beside the ring views so the Linux tests cover it; the app composes it into text in
/// `PercentText.ringCentre`, and a ring adopts it by calling that instead of `PercentText.figure`.
public enum RingCentre: Equatable, Sendable {
    /// The token with its sign, as every figure is written ("42%", or the unknown dash alone).
    case figure(String)
    /// The token without its sign: an exhausted ring's "100", which fits where "100%" does not.
    case bare(String)
    /// Nothing: the closed ring says it by itself.
    case empty

    /// The centre for `p` (#38). In USED a 100 is the exhausted window and is written bare. In LEFT
    /// a 100 is the full allowance, which the closed arc shows alone, also for stale or reset-due
    /// readings, whose arc is still drawn in grey. A failed reading draws no arc
    /// (`FillState.noFill`), so nothing would show its full allowance and the figure stays.
    public static func of(_ p: WindowPresentation) -> RingCentre {
        guard p.token == fullRingToken else { return .figure(p.token) }
        if p.side == .used { return .bare(p.token) }
        return p.fillState == .noFill ? .figure(p.token) : .empty
    }

    /// The token of a closed ring. ">99" is its own token, so this never swallows a rounded 99.6.
    private static let fullRingToken = "100"
}
