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
    var accountID: String? = nil
    @Environment(\.hoorayPlayback) private var playback

    var body: some View {
        if let playback, let accountID {
            ClaudeCodePlaybackFigure(playback: playback, accountID: accountID,
                mood: mood, size: size, ink: ink, animate: animate, amplitude: amplitude)
        } else {
            ClaudeCodeAnimatedFigure(mood: mood, size: size, ink: ink, animate: animate, amplitude: amplitude)
        }
    }
}

private struct ClaudeCodePlaybackFigure: View {
    @ObservedObject var playback: HoorayPlayback
    let accountID: String
    let mood: ClaudeCodeMood
    let size: CGFloat
    let ink: Color
    let animate: Bool
    let amplitude: Double
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.claudeAnimationVisible) private var visible
    @Environment(\.hoorayViewport) private var viewport

    var body: some View {
        let start = playback.startedAt(accountID)
        ClaudeCodeAnimatedFigure(mood: start == nil ? mood : .hooray, size: size, ink: ink,
            animate: animate, amplitude: amplitude, presentationStart: start)
            .hooraySlot(accountID: accountID, viewport: viewport, visible: visible && scenePhase == .active)
    }
}

private struct ClaudeCodeAnimatedFigure: View {
    let mood: ClaudeCodeMood
    let size: CGFloat
    let ink: Color
    let animate: Bool
    var amplitude: Double = 0.7
    var presentationStart: TimeInterval? = nil

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
                                     elapsed: elapsed,
                                     amplitude: amplitude, still: false)
                }
            } else {
                ClaudeCodeCanvas(mood: mood, size: size, ink: ink,
                                 elapsed: elapsed,
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

    private var elapsed: TimeInterval {
        let now = ProcessInfo.processInfo.systemUptime
        return presentationStart.map { max(0, now - $0) } ?? clock.elapsed(at: now)
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
