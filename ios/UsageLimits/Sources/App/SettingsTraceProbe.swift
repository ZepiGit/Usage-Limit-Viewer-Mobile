#if DEBUG && !os(Linux)
import QuartzCore
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
        private var gapSampler: GapSampler?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window == nil {
                gapSampler?.stop()
                gapSampler = nil
            } else if gapSampler == nil, SettingsTrace.isEnabled, let variant = SettingsTrace.gapVariant {
                gapSampler = GapSampler(variant: variant)
                gapSampler?.start()
            }
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
    private final class GapSampler: NSObject {
        let variant: String
        private var link: CADisplayLink?
        private var previous: TimeInterval?
        private var bucket: Int?
        private var maximumGap: TimeInterval = 0
        private var callbacks = 0

        init(variant: String) { self.variant = variant }

        func start() {
            let link = CADisplayLink(target: self, selector: #selector(callback))
            link.add(to: .main, forMode: .common)
            self.link = link
        }

        func stop() {
            link?.invalidate()
            link = nil
        }

        @objc private func callback() {
            let now = ProcessInfo.processInfo.systemUptime
            let second = Int(now)
            if let bucket, bucket != second {
                SettingsTrace.record("gap bucket variant=\(variant) second=\(bucket) "
                    + "maxMs=\(maximumGap * 1000) callbacks=\(callbacks)")
                maximumGap = 0
                callbacks = 0
            }
            // Charge a gap crossing a bucket boundary to the second in which the callback arrives.
            if let previous { maximumGap = max(maximumGap, now - previous) }
            previous = now
            bucket = second
            callbacks += 1
        }
    }

    @MainActor
    private final class TouchObserver: UIGestureRecognizer {
        private var nextTouchID = 0
        private var activeTouches: [ObjectIdentifier: Int] = [:]

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
            }
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
