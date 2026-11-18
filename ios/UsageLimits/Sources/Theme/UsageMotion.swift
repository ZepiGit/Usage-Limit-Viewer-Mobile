import SwiftUI
import UsageLimitsKit

// The app's motion, in one file.
//
// The Kit's `Motion` names every motion and holds its timing and the reduced-motion rule; this
// file is the only one that turns a `Motion` into a SwiftUI `Animation`, and the only one that
// reads Reduce Motion. A screen therefore says WHAT moves (`.motion(.move, value:)`) and cannot
// invent a duration or forget the accessibility setting. The one exception is `withAnimation`,
// which is a function and has no environment: its caller reads the setting and passes it to
// `animation(reduceMotion:)` below.
//
// Compiled into the widget extension with the rest of `Theme`, so nothing here may use an API
// that is unavailable to extensions.

extension Motion {
    /// The SwiftUI animation for this motion, or nil when the change is to be applied at once.
    ///
    /// One easing for all of them: ease in-out is the standard curve for something that starts
    /// and ends on screen, and every motion in this app does.
    func animation(reduceMotion: Bool) -> Animation? {
        guard let timing = timing(reduceMotion: reduceMotion) else { return nil }
        let animation = Animation.easeInOut(duration: timing.duration)
        return timing.delay > 0 ? animation.delay(timing.delay) : animation
    }
}

private struct MotionModifier<Value: Equatable>: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let motion: Motion
    let value: Value

    func body(content: Content) -> some View {
        content.animation(motion.animation(reduceMotion: reduceMotion), value: value)
    }
}

private struct PressReleaseModifier: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let isPressed: Bool

    func body(content: Content) -> some View {
        // Nil on the way down: feedback for a touch must not lag behind the finger.
        content.animation(
            isPressed ? nil : Motion.stateChange.animation(reduceMotion: reduceMotion), value: isPressed)
    }
}

private struct ListItemMotionModifier: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.transition(transition)
    }

    private var transition: AnyTransition {
        // `.identity`, not an opacity transition without animations: a transition that carries
        // its own animation plays it whatever the surrounding transaction says, so under Reduce
        // Motion there must be no transition at all.
        guard let enter = Motion.enterAfterMove.animation(reduceMotion: reduceMotion),
              let exit = Motion.exit.animation(reduceMotion: reduceMotion)
        else { return AnyTransition.identity }
        return AnyTransition.asymmetric(
            insertion: AnyTransition.opacity.animation(enter),
            removal: AnyTransition.opacity.animation(exit))
    }
}

extension View {
    /// Animates whatever this view draws from `value` as the named motion, whenever `value`
    /// changes, and applies the change at once under Reduce Motion.
    func motion<Value: Equatable>(_ motion: Motion, value: Value) -> some View {
        modifier(MotionModifier(motion: motion, value: value))
    }

    /// A figure fades to its next value, on a mode flip and on a refresh alike.
    ///
    /// A content transition rather than `.id` with a view transition: the text stays one view,
    /// so the fade also runs when the figure changes outside an animation transaction (a
    /// refresh), and a figure that is interrupted fades on from where it is.
    func figureChange(_ figure: String) -> some View {
        contentTransition(ContentTransition.opacity).motion(.contentSwap, value: figure)
    }

    /// A press highlight appears at once and lets go over `stateChange`, the way system controls
    /// fade theirs out.
    func pressRelease(isPressed: Bool) -> some View {
        modifier(PressReleaseModifier(isPressed: isPressed))
    }

    /// One item of a list that fades through: it leaves over `exit`, the list around it then
    /// moves over `moveAfterExit` (`.motion(.moveAfterExit, value:)` on the container), and a new
    /// item arrives over `enterAfterMove`, as that move ends. Nothing new is drawn over something
    /// still fading out or still in the way.
    func listItemMotion() -> some View {
        modifier(ListItemMotionModifier())
    }
}

/// The app's small in-place wait: a spinner, or three dots under Reduce Motion, where a
/// spinning mark is exactly the motion the user asked not to see.
struct ActivityMark: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if reduceMotion {
            Text(verbatim: "...")
        } else {
            ProgressView().tint(UsageColors.muted).controlSize(.small)
        }
    }
}
