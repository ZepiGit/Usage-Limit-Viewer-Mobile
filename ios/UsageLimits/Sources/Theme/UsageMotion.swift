import SwiftUI
import UsageLimitsKit

// The app's motion, in one file.
//
// The Kit's `Motion` names every motion and holds its timing and the reduced-motion rule; this
// file is the only one that turns a `Motion` into a SwiftUI `Animation`, and the only one that
// reads Reduce Motion. A screen therefore says WHAT moves (`.motion(.move, value:)`) and cannot
// invent a duration or forget the accessibility setting. The one exception is `withAnimation`,
// which is a function and has no environment: its caller declares `@ReducesMotion` and passes
// the value to `animation(reduceMotion:)` below.
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

    /// Derselbe Eintritt ohne Wartezeit, wenn der Austritt bereits abgeschlossen ist.
    static func enterFromHidden(reduceMotion: Bool) -> Animation? {
        guard let timing = Motion.enterAfterExit.timing(reduceMotion: reduceMotion) else { return nil }
        return .easeInOut(duration: timing.duration)
    }

    /// The two animations of a fade-through that ends with this motion: what leaves goes over
    /// `exit`, what arrives comes over this one, which starts when the exit is over. Nil when
    /// the change is to be a cut.
    fileprivate func fadeThrough(reduceMotion: Bool) -> (exit: Animation, enter: Animation)? {
        guard let exit = Motion.exit.animation(reduceMotion: reduceMotion),
              let enter = animation(reduceMotion: reduceMotion)
        else { return nil }
        return (exit, enter)
    }
}

// MARK: - Reduce Motion

#if DEBUG
private struct ReduceMotionOverrideKey: EnvironmentKey {
    static let defaultValue: Bool? = nil
}

extension EnvironmentValues {
    /// Stands in for the system's Reduce Motion setting below the view that sets it. Nil, the
    /// default, means "ask the system".
    ///
    /// It exists so a UI test can run the real views with motion reduced: a test cannot switch
    /// the simulator's setting, but the app can set an environment value at its root.
    var reduceMotionOverride: Bool? {
        get { self[ReduceMotionOverrideKey.self] }
        set { self[ReduceMotionOverrideKey.self] = newValue }
    }
}

#endif

/// The system preference, with a Debug-only override for UI tests.
@propertyWrapper
struct ReducesMotion: DynamicProperty {
    @Environment(\.accessibilityReduceMotion) private var system
    #if DEBUG
    @Environment(\.reduceMotionOverride) private var forced
    var wrappedValue: Bool { forced ?? system }
    #else
    var wrappedValue: Bool { system }
    #endif
}

// MARK: - Modifiers

private struct MotionModifier<Value: Equatable>: ViewModifier {
    @ReducesMotion private var reduceMotion
    let motion: Motion
    let value: Value
    /// False while a change of `value` is not one to animate: see `listMotion`.
    var armed = true

    func body(content: Content) -> some View {
        let animation = armed ? motion.animation(reduceMotion: reduceMotion) : nil
        return content
            #if DEBUG
            .loggingMotion(motion.rawValue, value: value, animated: animation != nil)
            #endif
            // Nil is not "no opinion": for the update in which `value` changes it clears
            // whatever animation the surrounding transaction carries, so an unarmed change is a
            // cut even inside someone else's `withAnimation`.
            .animation(animation, value: value)
    }
}

private struct PressReleaseModifier: ViewModifier {
    @ReducesMotion private var reduceMotion
    let isPressed: Bool

    func body(content: Content) -> some View {
        // Nil on the way down: feedback for a touch must not lag behind the finger.
        let animation = isPressed ? nil : Motion.stateChange.animation(reduceMotion: reduceMotion)
        return content
            #if DEBUG
            .loggingMotion("pressRelease", value: isPressed, animated: animation != nil)
            #endif
            .animation(animation, value: isPressed)
    }
}

private struct ListItemMotionModifier: ViewModifier {
    @ReducesMotion private var reduceMotion
    let armed: Bool

    func body(content: Content) -> some View {
        content.transition(transition)
    }

    private var transition: AnyTransition {
        let fades = armed ? Motion.enterAfterMove.fadeThrough(reduceMotion: reduceMotion) : nil
        #if DEBUG
        noteTransition("item", fades: fades != nil)
        #endif
        // `.identity`, not an opacity transition without animations: a transition that carries
        // its own animation plays it whatever the surrounding transaction says, so when nothing
        // is to move there must be no transition at all.
        guard let fades else { return AnyTransition.identity }
        return AnyTransition.asymmetric(
            insertion: AnyTransition.opacity.animation(fades.enter),
            removal: AnyTransition.opacity.animation(fades.exit))
    }
}

private struct ListMotionArming: ViewModifier {
    @Binding var armed: Bool
    let loaded: Bool

    func body(content: Content) -> some View {
        // Both run after an update, never in one: the body that first draws the loaded content
        // has already been evaluated unarmed when either of them sets the flag.
        content
            .onAppear { if loaded { armed = true } }
            .onChange(of: loaded) { loaded in if loaded { armed = true } }
    }
}

private struct FigureUnitModifier<Key: Hashable>: ViewModifier {
    @ReducesMotion private var reduceMotion
    let key: Key
    let anchor: HorizontalAlignment

    func body(content: Content) -> some View {
        // While the old unit leaves, both units exist. A stack of their own keeps them in one
        // place, on one baseline and against the same edge; as direct children of the row or the
        // column around them they would be laid out as two items, side by side or one above the
        // other, and push everything else for as long as the fade lasts.
        ZStack(alignment: Alignment(horizontal: anchor, vertical: .firstTextBaseline)) {
            content.id(key).transition(transition)
        }
        .motion(.enterAfterExit, value: key)
    }

    private var transition: AnyTransition {
        let fades = Motion.enterAfterExit.fadeThrough(reduceMotion: reduceMotion)
        #if DEBUG
        noteTransition("figure", fades: fades != nil)
        #endif
        guard let fades else { return AnyTransition.identity }
        return AnyTransition.asymmetric(
            insertion: AnyTransition.opacity.animation(fades.enter),
            removal: AnyTransition.opacity.animation(fades.exit)
                .combined(with: AnyTransition.modifier(active: Leaving(isLeaving: true), identity: Leaving(isLeaving: false))))
    }
}

/// Takes a view out of the accessibility tree the moment it starts to leave, not when its fade
/// ends: what is read out and what a test finds is the current state only, never the outgoing
/// figure beside the new one.
private struct Leaving: ViewModifier {
    let isLeaving: Bool

    func body(content: Content) -> some View {
        content.accessibilityHidden(isLeaving)
    }
}

extension View {
    /// Animates whatever this view draws from `value` as the named motion, whenever `value`
    /// changes, and applies the change at once under Reduce Motion.
    func motion<Value: Equatable>(_ motion: Motion, value: Value) -> some View {
        modifier(MotionModifier(motion: motion, value: value))
    }

    /// The container of a list whose items come and go: when `value`, the identities of what is
    /// shown, changes, what stays moves to its new place over `motion`.
    ///
    /// Only a change a person makes while looking at a settled list is such a motion: a filter,
    /// an item added or removed. While `armed` is false a change is applied in one cut, which is
    /// what the first content replacing a skeleton and a jump to a target are. `armingListMotion`
    /// sets the flag for the first of those.
    func listMotion<Value: Equatable>(
        _ motion: Motion = .moveAfterExit, value: Value, armed: Bool
    ) -> some View {
        modifier(MotionModifier(motion: motion, value: value, armed: armed))
    }

    /// One item of a list that fades through: it leaves over `exit`, the list around it then
    /// moves over `moveAfterExit` (`listMotion` on the container), and a new item arrives over
    /// `enterAfterMove`, as that move ends. Nothing new is drawn over something still fading out
    /// or still in the way. Unarmed, like its container, it comes and goes in one cut.
    func listItemMotion(armed: Bool) -> some View {
        modifier(ListItemMotionModifier(armed: armed))
    }

    /// Arms a list's motion once its first content is on screen: on appearing with the content
    /// loaded, or when it loads later. The update that brings the first content is over by then,
    /// so the content replaces the skeleton in one cut and only later changes move.
    func armingListMotion(_ armed: Binding<Bool>, loaded: Bool) -> some View {
        modifier(ListMotionArming(armed: armed, loaded: loaded))
    }

    /// A figure and the word that says which side it counts, as one unit that fades through
    /// whenever `key`, what the unit reads, changes: the old unit goes over `exit`, then the new
    /// one arrives over `enterAfterExit`, on a mode flip and on a refresh alike.
    ///
    /// A view transition on the pair rather than a content transition on the figure: a figure
    /// that cross-fades beside a word that has already changed shows "60%" next to "used" for
    /// as long as the fade runs. As one unit, the old figure only ever stands beside its own
    /// word, and the new one is not drawn until the old has gone.
    ///
    /// `anchor` is the edge the unit keeps while the old and the new one overlap: the side it
    /// is aligned to where it stands.
    func figureUnit<Key: Hashable>(key: Key, anchor: HorizontalAlignment) -> some View {
        modifier(FigureUnitModifier(key: key, anchor: anchor))
    }

    /// What this view shows is another subject now (another account, another window), not the
    /// same one with new values: the old is replaced in one cut, whatever animation runs around
    /// it, and nothing inside animates from the old subject's values.
    func subject<ID: Hashable>(_ id: ID) -> some View {
        self.id(id).transition(AnyTransition.identity)
    }

    /// A press highlight appears at once and lets go over `stateChange`, the way system controls
    /// fade theirs out.
    func pressRelease(isPressed: Bool) -> some View {
        modifier(PressReleaseModifier(isPressed: isPressed))
    }

    #if DEBUG
    /// Logs the real modifier's decision, not the animation applied or rendered by SwiftUI.
    @ViewBuilder
    fileprivate func loggingMotion<Value: Equatable>(_ name: String, value: Value, animated: Bool) -> some View {
        if MotionLog.isEnabled {
            modifier(MotionLogProbe(name: name, value: value, animated: animated))
        } else {
            self
        }
    }
    #endif
}

// MARK: - Waiting

/// The app's small in-place wait: a spinner, or a still mark under Reduce Motion, where a
/// spinning mark is exactly the motion the user asked not to see.
struct ActivityMark: View {

    /// What stands in for the spinner under Reduce Motion.
    enum Still {
        /// Three dots as text: for a mark beside a sentence that already says what is awaited.
        case dots
        /// Three dots drawn in the spinner's own box, which assistive technology still meets as
        /// a progress indicator: for a mark that is all there is to read, such as a button's
        /// label, where text would resize the button and be read out as "...".
        case progressIndicator
    }

    @ReducesMotion private var reduceMotion
    var still: Still = .dots

    var body: some View {
        if !reduceMotion {
            spinner
        } else {
            switch still {
            case .dots:
                Text(verbatim: "...")
            case .progressIndicator:
                // The hidden spinner reserves its own size, so whatever holds the mark keeps its
                // size whichever way the setting is. The representation is what accessibility
                // gets in place of the drawing: the system's progress indicator, in the
                // system's own words.
                spinner.hidden()
                    .overlay {
                        HStack(spacing: 2) {
                            ForEach(0..<3, id: \.self) { _ in
                                Circle().fill(UsageColors.muted).frame(width: 3, height: 3)
                            }
                        }
                    }
                    .accessibilityRepresentation { ProgressView().controlSize(.small) }
            }
        }
    }

    private var spinner: some View {
        ProgressView().tint(UsageColors.muted).controlSize(.small)
    }
}

// MARK: - Motion log

#if DEBUG
@MainActor
private func noteTransition(_ name: String, fades: Bool) {
    if MotionLog.isEnabled { MotionLog.shared.noteTransition(name, fades: fades) }
}

/// Decisions of the real modifiers with their environment, not applied transactions or frames.
@MainActor
final class MotionLog: ObservableObject {
    static let shared = MotionLog()

    /// Decided once: launch arguments do not change while the process lives.
    nonisolated static let isEnabled = ProcessInfo.processInfo.arguments.contains("-motion-log")

    /// `#<count> | <n> <motion> <outcome> | …`: how many notes there have been, then each motion
    /// and outcome once with the number of its latest note, newest first. An animated motion
    /// reads `animated` or `none`, a transition `fade` or `identity`.
    @Published private(set) var text = "#0"

    private var count = 0
    private var latest: [String: Int] = [:]
    private var publishIsPending = false
    private var transitions: [String: Bool] = [:]

    func noteTransition(_ name: String, fades: Bool) {
        guard transitions[name] != fades else { return }
        transitions[name] = fades
        note(name, fades ? "fade" : "identity")
    }

    func note(_ name: String, _ outcome: String) {
        count += 1
        latest["\(name) \(outcome)"] = count
        guard !publishIsPending else { return }
        publishIsPending = true
        // Notes arrive while SwiftUI is updating views. Publishing there would change view
        // state inside the very update being reported, so the text follows in a later turn.
        Task { @MainActor in self.publish() }
    }

    private func publish() {
        publishIsPending = false
        let entries = latest.sorted { $0.value > $1.value }.map { "\($0.value) \($0.key)" }
        text = (["#\(count)"] + entries).joined(separator: " | ")
    }
}

/// Notes each change of `value` with the decision the modifier made for it.
///
/// A change, not a transaction: a `transaction` closure was the first design, and on the
/// simulator it never ran for changes inside `List` rows (a mode flip on Overview logged
/// nothing while its figures and bars changed), so it could not say what a row did.
///
/// The decision travels with the value: the action of `onChange(of:perform:)` is the closure of
/// the evaluation before the change, so a flag it captured would be the old one (seen on CI: a
/// list disarmed in the same update as its change was logged as animated).
private struct MotionLogProbe<Value: Equatable>: ViewModifier {
    @State private var last = LastValue<Value>()
    let name: String
    let value: Value
    let animated: Bool

    func body(content: Content) -> some View {
        // The value a view starts with is where it begins, not a change.
        let _ = start()
        content.onChange(of: Change(value: value, animated: animated)) { change in
            // Arming or disarming alone is not a change of the value.
            guard last.value != change.value else { return }
            last.value = change.value
            MotionLog.shared.note(name, change.animated ? "animated" : "none")
        }
    }

    private func start() {
        if last.value == nil { last.value = value }
    }
}

/// A value and its modifier's animation decision, compared as one.
private struct Change<Value: Equatable>: Equatable {
    let value: Value
    let animated: Bool
}

/// The value a motion was last noted for, held by reference so that noting it changes no view state.
private final class LastValue<Value: Equatable> {
    var value: Value?
}
#endif
