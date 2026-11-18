import Foundation

/// How long a motion runs, and how long it waits before it starts.
public struct MotionTiming: Equatable, Sendable {
    public let duration: TimeInterval
    public let delay: TimeInterval
}

/// The app's motion vocabulary: every animation on a screen is one of these, named by what it
/// says rather than by how long it takes.
///
/// The timings live here and not beside the views so they are covered by the Linux tests, and so
/// both platforms move alike: Android's motion specs carry the same names, and
/// `shared/test-vectors/motion.json` holds both to the same numbers.
public enum Motion: String, CaseIterable, Sendable {
    /// A state changing in place: a selection, a press highlight letting go.
    case stateChange
    /// One content replacing another in the same place: a sign-in stage, a figure, the mode flip,
    /// reorder mode.
    case contentSwap
    /// A quantity or a position changing: a bar's fill, a list row's place.
    case move
    /// The first part of a fade-through in a list: what leaves goes first, so nothing is ever
    /// drawn over something still fading out.
    case exit
    /// A fade-through where the new arrives in the place of the old: straight after the exit.
    case enterAfterExit
    /// The middle part of a fade-through: what stays moves to its new place once the exit is over.
    case moveAfterExit
    /// A fade-through in a list, where neighbours have to make room first: the new row waits for
    /// most of their move and ends with it, so it does not fade in underneath them.
    case enterAfterMove

    // Each duration is named once, so a fade-through's parts cannot drift apart: the delay of the
    // two "after exit" motions IS the exit's duration.
    private static let brief: TimeInterval = 0.09
    private static let quick: TimeInterval = 0.12
    private static let short: TimeInterval = 0.18
    private static let medium: TimeInterval = 0.24

    /// The one reduced-motion rule: with Reduce Motion on every motion is nil, which means "apply
    /// the change at once". Nothing is shortened or swapped for a gentler motion, so no caller has
    /// a second case to get wrong.
    public func timing(reduceMotion: Bool) -> MotionTiming? {
        if reduceMotion { return nil }
        switch self {
        case .stateChange: return MotionTiming(duration: Self.quick, delay: 0)
        case .contentSwap: return MotionTiming(duration: Self.short, delay: 0)
        case .move: return MotionTiming(duration: Self.medium, delay: 0)
        case .exit: return MotionTiming(duration: Self.brief, delay: 0)
        case .enterAfterExit: return MotionTiming(duration: Self.medium - Self.brief, delay: Self.brief)
        case .moveAfterExit: return MotionTiming(duration: Self.medium, delay: Self.brief)
        // Ends together with `moveAfterExit`, so the new row settles as its neighbours do.
        case .enterAfterMove: return MotionTiming(duration: Self.medium - Self.brief, delay: 2 * Self.brief)
        }
    }
}
