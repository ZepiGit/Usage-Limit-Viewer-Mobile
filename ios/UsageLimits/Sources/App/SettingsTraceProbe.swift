#if DEBUG && !os(Linux)
import SwiftUI
import UIKit

/// Reads the trace on demand without publishing any of its counters into the view tree.
struct SettingsTraceProbe: UIViewRepresentable {
    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView(frame: .zero)
        view.isAccessibilityElement = true
        view.accessibilityIdentifier = "settings-trace"
        view.accessibilityLabel = "Settings trace"
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {}

    final class ProbeView: UIView {
        override var accessibilityValue: String? {
            get { MainActor.assumeIsolated { SettingsTrace.snapshot() } }
            set { super.accessibilityValue = newValue }
        }
    }
}
#endif
