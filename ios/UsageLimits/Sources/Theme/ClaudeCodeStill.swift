import SwiftUI
import UsageLimitsKit

/// A widget-safe rest pose: no clock, lifecycle or environment dependencies.
struct ClaudeCodeStill: View {
    let mood: ClaudeCodeMood
    let size: CGFloat
    let ink: Color

    var body: some View {
        ClaudeCodeCanvas(mood: mood, size: size, ink: ink, elapsed: 0, amplitude: 0, still: true)
    }
}
