import SwiftUI
import UsageLimitsKit

private struct ClaudeAnimationVisibleKey: EnvironmentKey {
    static let defaultValue = true
}

extension EnvironmentValues {
    var claudeAnimationVisible: Bool {
        get { self[ClaudeAnimationVisibleKey.self] }
        set { self[ClaudeAnimationVisibleKey.self] = newValue }
    }
}

#if DEBUG
private struct ClaudeAnimationDiagnosticIDKey: EnvironmentKey {
    static let defaultValue: String? = nil
}

extension EnvironmentValues {
    var claudeAnimationDiagnosticID: String? {
        get { self[ClaudeAnimationDiagnosticIDKey.self] }
        set { self[ClaudeAnimationDiagnosticIDKey.self] = newValue }
    }
}
#endif

struct ClaudeCodeFigure: View {
    let mood: ClaudeCodeMood
    let size: CGFloat
    let ink: Color
    let animate: Bool
    var amplitude: Double = 0.7

    @ReducesMotion private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.claudeAnimationVisible) private var visible
    #if DEBUG
    @Environment(\.claudeAnimationDiagnosticID) private var diagnosticID
    #endif
    @State private var appeared = false
    @State private var clock = ClaudeCodeActiveClock()

    private struct Gate: Equatable {
        let still: Bool
        let foregroundVisible: Bool
        var active: Bool { !still && foregroundVisible }
    }

    private var gate: Gate {
        Gate(still: !animate || reduceMotion, foregroundVisible: visible && scenePhase == .active)
    }

    var body: some View {
        Group {
            if appeared && gate.active {
                TimelineView(.animation(minimumInterval: 1.0 / 30)) { _ in
                    #if DEBUG
                    let _ = SettingsTrace.figureFrame(diagnosticID)
                    #endif
                    ClaudeCodeCanvas(mood: mood, size: size, ink: ink,
                                     elapsed: clock.elapsed(at: ProcessInfo.processInfo.systemUptime),
                                     amplitude: amplitude, still: false, rendersAsynchronously: true)
                }
            } else {
                ClaudeCodeCanvas(mood: mood, size: size, ink: ink,
                                 elapsed: clock.elapsed(at: ProcessInfo.processInfo.systemUptime),
                                 amplitude: amplitude, still: gate.still)
            }
        }
        .onAppear {
            appeared = true
            apply(gate)
        }
        .onDisappear {
            let wasActive = clock.isActive
            appeared = false
            clock.setActive(false, at: ProcessInfo.processInfo.systemUptime)
            #if DEBUG
            if wasActive { SettingsTrace.figureActivityChanged(to: false) }
            #endif
            if wasActive { note("paused") }
        }
        .onChange(of: gate) { apply($0) }
        .onChange(of: mood) { newMood in
            clock.restart(at: ProcessInfo.processInfo.systemUptime)
            note(gate.still ? "still" : clock.isActive ? "animated" : "paused", mood: newMood)
        }
    }

    private func apply(_ gate: Gate) {
        let active = appeared && gate.active
        #if DEBUG
        if active != clock.isActive { SettingsTrace.figureActivityChanged(to: active) }
        #endif
        clock.setActive(active, at: ProcessInfo.processInfo.systemUptime)
        note(gate.still ? "still" : clock.isActive ? "animated" : "paused")
    }

    private func note(_ outcome: String, mood: ClaudeCodeMood? = nil) {
        #if DEBUG
        if MotionLog.isEnabled, ProcessInfo.processInfo.arguments.contains("-ui-testing"), let diagnosticID {
            MotionLog.shared.note("claude:\(diagnosticID):\((mood ?? self.mood).rawValue)", outcome)
        }
        #endif
    }
}
