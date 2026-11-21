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
            let tracesTouches = ProcessInfo.processInfo.arguments.contains("-settings-touch-trace")
            if window == nil {
                gapSampler?.stop()
                gapSampler = nil
            } else if gapSampler == nil, SettingsTrace.isEnabled,
                      let variant = SettingsTrace.gapVariant ?? (tracesTouches ? "native-controls" : nil) {
                gapSampler = GapSampler(variant: variant)
                gapSampler?.start()
            }
            if touchObserver.view !== window {
                touchObserver.stopNativeControlTrace()
                touchObserver.view?.removeGestureRecognizer(touchObserver)
            }
            guard let window, touchObserver.view == nil, SettingsTrace.isEnabled, tracesTouches else { return }
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
        private let tracesNativeControls = ProcessInfo.processInfo.arguments.contains("-settings-native-control-trace")
        private weak var nativeView: UIView?
        private var nativeKind = "unavailable"
        private var nativeIdentifier = "unavailable"
        private var nativeTouchID: Int?
        private var nativeDeliveryTouchID: Int?
        private var nativeValueChangedCount = 0
        private var nativePrimaryActionCount = 0

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
                observeNativeControl(for: touch, id: nextTouchID)
                record(touch, phase: "began", event: event)
            }
        }

        func stopNativeControlTrace() {
            if let control = nativeView as? UIControl {
                control.removeTarget(self, action: #selector(nativeValueChanged(_:)), for: .valueChanged)
                control.removeTarget(self, action: #selector(nativePrimaryAction(_:)), for: .primaryActionTriggered)
            }
            nativeView = nil
            nativeTouchID = nil
            nativeDeliveryTouchID = nil
            nativeValueChangedCount = 0
            nativePrimaryActionCount = 0
            SettingsTrace.nativeControlBoundary = nil
        }

        private func observeNativeControl(for touch: UITouch, id: Int) {
            guard tracesNativeControls else { return }
            var ancestor = touch.view
            var candidate: UIControl?
            var identifier = "unavailable"
            while let current = ancestor {
                if candidate == nil, current is UISwitch || current is UISegmentedControl {
                    candidate = current as? UIControl
                } else if candidate == nil, let button = current as? UIButton, button.menu != nil {
                    candidate = button
                }
                if let axID = current.accessibilityIdentifier,
                   ["settings-overview-credit-layout", "settings-overview-show-first", "settings-overview-layout",
                    "settings-animate-claude-code-icon", "settings-overview-show-credits"].contains(axID) {
                    identifier = axID
                }
                ancestor = current.superview
            }
            // Menu items can be private views in another hierarchy, not UIControls with an action API.
            if candidate == nil, nativeIdentifier == "settings-overview-show-first", let menu = nativeView {
                nativeDeliveryTouchID = id
                SettingsTrace.record("native menu delivered touchID=\(id) menuTouchID=\(nativeTouchID ?? id) "
                    + "control=\(ObjectIdentifier(menu)) axID=\(nativeIdentifier) "
                    + "itemUIControl=unavailable association=preceding-menu-touch \(nativeState(menu))")
                return
            }
            stopNativeControlTrace()
            let candidateView: UIView? = candidate
            guard let hit = candidateView ?? touch.view else { return }
            nativeView = hit
            nativeIdentifier = identifier
            nativeTouchID = id
            nativeDeliveryTouchID = id
            if let toggle = candidate as? UISwitch {
                nativeKind = "switch"
                toggle.addTarget(self, action: #selector(nativeValueChanged(_:)), for: .valueChanged)
            } else if let segment = candidate as? UISegmentedControl {
                nativeKind = "segment"
                segment.addTarget(self, action: #selector(nativeValueChanged(_:)), for: .valueChanged)
            } else if let button = candidate as? UIButton {
                nativeKind = "menu"
                button.addTarget(self, action: #selector(nativePrimaryAction(_:)), for: .primaryActionTriggered)
            } else {
                nativeKind = "unavailable"
            }
            let field: String?
            switch identifier {
            case "settings-overview-credit-layout": field = "overviewCreditLayout"
            case "settings-overview-show-first": field = "overviewShowFirst"
            case "settings-overview-layout": field = "overviewLayout"
            case "settings-animate-claude-code-icon": field = "animateClaudeCodeIcon"
            case "settings-overview-show-credits": field = "overviewShowCredits"
            default: field = nil
            }
            if let field {
                SettingsTrace.nativeControlBoundary = (
                    "kind=\(nativeKind) touchID=\(id) control=\(ObjectIdentifier(hit)) axID=\(identifier)", field)
            }
            SettingsTrace.record("native \(nativeKind) observed touchID=\(id) control=\(ObjectIdentifier(hit)) "
                + "axID=\(identifier) class=\(String(describing: type(of: hit))) \(nativeState(hit))")
        }

        private func nativeState(_ view: UIView) -> String {
            if let toggle = view as? UISwitch { return "switchOn=\(toggle.isOn)" }
            if let segment = view as? UISegmentedControl {
                return "selectedSegmentIndex=\(segment.selectedSegmentIndex)"
            }
            if let button = view as? UIButton, let menu = button.menu {
                return "menu=\(ObjectIdentifier(menu)) selection=unobserved"
            }
            return "UIControl=unavailable"
        }

        @objc private func nativeValueChanged(_ sender: UIControl) {
            guard sender === nativeView, let id = nativeTouchID else { return }
            nativeValueChangedCount += 1
            SettingsTrace.record("native \(nativeKind) valueChanged touchID=\(id) control=\(ObjectIdentifier(sender)) "
                + "axID=\(nativeIdentifier) count=\(nativeValueChangedCount) \(nativeState(sender))")
        }

        @objc private func nativePrimaryAction(_ sender: UIButton) {
            guard sender === nativeView, let id = nativeTouchID else { return }
            nativePrimaryActionCount += 1
            // This action can open the menu; only the existing settings edit proves an item selection.
            SettingsTrace.record("native menu primaryActionTriggered touchID=\(id) control=\(ObjectIdentifier(sender)) "
                + "axID=\(nativeIdentifier) count=\(nativePrimaryActionCount) \(nativeState(sender))")
        }

        private func recordAfterNativeDispatch(_ touch: UITouch, id: Int, phase: String) {
            guard nativeDeliveryTouchID == id, let control = nativeView else { return }
            let identity = ObjectIdentifier(control)
            let kind = nativeKind
            let identifier = nativeIdentifier
            // Read next main-loop turn without retaining the touch or extending the control's lifetime.
            DispatchQueue.main.async { [weak self, weak control, weak hit = touch.view] in
                guard let self, self.nativeDeliveryTouchID == id else { return }
                let boundary = "native \(kind) post-dispatch touchID=\(id) phase=\(phase) "
                    + "control=\(identity) axID=\(identifier) valueChangedCount=\(self.nativeValueChangedCount) "
                    + "primaryActionCount=\(self.nativePrimaryActionCount)"
                guard let control else {
                    SettingsTrace.record("\(boundary) alive=false")
                    return
                }
                SettingsTrace.record("\(boundary) alive=true attached=\(control.window != nil) \(self.nativeState(control)) "
                    + "recognizers=\(self.recognizers(from: hit ?? control))")
            }
        }

        private func recognizers(from hit: UIView) -> String {
            var ancestor: UIView? = hit
            var entries: [String] = []
            while let current = ancestor {
                for recognizer in current.gestureRecognizers ?? [] where recognizer !== self {
                    entries.append("\(String(describing: type(of: recognizer))):\(recognizer.state.rawValue) "
                        + "cancels=\(recognizer.cancelsTouchesInView) "
                        + "delaysBegan=\(recognizer.delaysTouchesBegan) delaysEnded=\(recognizer.delaysTouchesEnded)")
                }
                if current is UIScrollView { break }
                ancestor = current.superview
            }
            return entries.joined(separator: "; ")
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
                if let id = activeTouches[ObjectIdentifier(touch)] {
                    recordAfterNativeDispatch(touch, id: id, phase: phase)
                }
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
                    + "type=\(touch.type.rawValue) activeFigures=\(SettingsTrace.activeFigures)")
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
                    if tracesNativeControls { details += " control=\(ObjectIdentifier(toggle))" }
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
