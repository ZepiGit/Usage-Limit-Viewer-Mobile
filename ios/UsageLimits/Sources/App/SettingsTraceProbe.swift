#if DEBUG && !os(Linux)
import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

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
        private let touchObserver = TouchObserver(target: nil, action: nil)

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if touchObserver.view !== window {
                touchObserver.view?.removeGestureRecognizer(touchObserver)
            }
            guard let window, touchObserver.view == nil,
                  SettingsTrace.isEnabled,
                  ProcessInfo.processInfo.arguments.contains("-settings-touch-trace") else { return }
            window.addGestureRecognizer(touchObserver)
            SettingsTrace.record("touch observer attached windowBounds=\(window.bounds)")
        }

        override var accessibilityValue: String? {
            get {
                guard Thread.isMainThread else { return nil }
                return MainActor.assumeIsolated { SettingsTrace.snapshot() }
            }
            set { super.accessibilityValue = newValue }
        }
    }

    @MainActor
    private final class TouchObserver: UIGestureRecognizer {
        private var nextTouchID = 0
        private var activeTouches: [ObjectIdentifier: Int] = [:]
        private var nextStall = 0
        private let stallDurations: [Int] = {
            let arguments = ProcessInfo.processInfo.arguments
            guard arguments.contains("-ui-testing"),
                  let option = arguments.firstIndex(of: "-settings-stall-ms"),
                  option + 1 < arguments.count else { return [] }
            return arguments[option + 1].split(separator: ",").compactMap(Int.init).filter { (0...120).contains($0) }
        }()

        override init(target: Any?, action: Selector?) {
            super.init(target: target, action: action)
            cancelsTouchesInView = false
            delaysTouchesBegan = false
            delaysTouchesEnded = false
        }

        required init?(coder: NSCoder) {
            fatalError("TouchObserver is installed programmatically")
        }

        override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool { false }
        override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool { false }

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
            for touch in touches {
                nextTouchID += 1
                activeTouches[ObjectIdentifier(touch)] = nextTouchID
                record(touch, phase: "began", event: event)
                stallIfArmed(touch)
            }
        }

        private func stallIfArmed(_ touch: UITouch) {
            guard nextStall < stallDurations.count else { return }
            var target = touch.view
            while let current = target, !(current is UISwitch) { target = current.superview }
            guard target is UISwitch, let id = activeTouches[ObjectIdentifier(touch)] else { return }
            let milliseconds = stallDurations[nextStall]
            nextStall += 1
            let start = ProcessInfo.processInfo.systemUptime
            // Block before the window recognizer returns the down event to the native control.
            if milliseconds > 0 { Thread.sleep(forTimeInterval: Double(milliseconds) / 1000) }
            let end = ProcessInfo.processInfo.systemUptime
            SettingsTrace.record("stall attempt=\(nextStall) touchID=\(id) requestedMs=\(milliseconds) "
                + "started=\(start) ended=\(end) actualMs=\((end - start) * 1000)")
        }

        override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
            finish(touches, phase: "ended", event: event)
        }

        override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
            finish(touches, phase: "cancelled", event: event)
        }

        override func reset() {
            super.reset()
            activeTouches.removeAll(keepingCapacity: true)
        }

        private func finish(_ touches: Set<UITouch>, phase: String, event: UIEvent) {
            for touch in touches {
                record(touch, phase: phase, event: event)
                activeTouches.removeValue(forKey: ObjectIdentifier(touch))
            }
            // Never recognize: controls and other recognizers keep their normal delivery.
            if activeTouches.isEmpty { state = .failed }
        }

        private func record(_ touch: UITouch, phase: String, event: UIEvent) {
            guard let window = view as? UIWindow,
                  let id = activeTouches[ObjectIdentifier(touch)] else { return }
            let point = touch.location(in: window)
            SettingsTrace.record(
                "touch id=\(id) phase=\(phase) touchTime=\(touch.timestamp) eventTime=\(event.timestamp) "
                    + "windowPoint=\(point) windowBounds=\(window.bounds) taps=\(touch.tapCount) "
                    + "type=\(touch.type.rawValue)")
            SettingsTrace.record(
                "touch id=\(id) phase=\(phase) deliveredHierarchy=\(hierarchy(touch.view, window: window))")
        }

        private func hierarchy(_ hit: UIView?, window: UIWindow) -> String {
            var ancestor = hit
            var entries: [String] = []
            while let current = ancestor {
                var details = "\(String(describing: type(of: current))) "
                    + "frame=\(current.convert(current.bounds, to: window))"
                // Only fixed diagnostic IDs, never labels, text or account IDs.
                if let identifier = current.accessibilityIdentifier,
                   identifier == "settings-overview-show-credits" || identifier == "settings-form" {
                    details += " axID=\(identifier)"
                }
                if let control = current as? UIControl {
                    details += " tracking=\(control.isTracking) inside=\(control.isTouchInside) "
                        + "enabled=\(control.isEnabled)"
                }
                if let toggle = current as? UISwitch {
                    details += " switchOn=\(toggle.isOn)"
                }
                if let scroll = current as? UIScrollView {
                    details += " scrollTracking=\(scroll.isTracking) dragging=\(scroll.isDragging) "
                        + "decelerating=\(scroll.isDecelerating) contentOffset=\(scroll.contentOffset)"
                }
                entries.append(details)
                if current === window { break }
                ancestor = current.superview
            }
            return entries.isEmpty ? "nil" : entries.joined(separator: " -> ")
        }
    }
}
#endif
