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

    /// Samples a segmented control every frame for half a second after the touch began, so a
    /// tracking sequence that ends without a selection is visible without a line per frame.
    @MainActor
    private final class SegmentSampler: NSObject {
        private weak var control: UISegmentedControl?
        private let touchID: Int
        private var link: CADisplayLink?
        private var startUptime: TimeInterval = 0
        private var frames = 0
        private var previous: (index: Int, tracking: Bool, highlighted: Bool)?
        private var changes = 0
        private var truncated = false
        private static let maximumChanges = 6

        init(touchID: Int, control: UISegmentedControl) {
            self.touchID = touchID
            self.control = control
        }

        func start() {
            startUptime = ProcessInfo.processInfo.systemUptime
            let link = CADisplayLink(target: self, selector: #selector(callback))
            link.add(to: .main, forMode: .common)
            self.link = link
        }

        func stop() {
            link?.invalidate()
            link = nil
        }

        @objc private func callback() {
            guard link != nil else { return }
            frames += 1
            let milliseconds = Int(((ProcessInfo.processInfo.systemUptime - startUptime) * 1000).rounded())
            guard let control else {
                SettingsTrace.record("native segment sample touchID=\(touchID) frame=\(frames) "
                    + "ms=\(milliseconds) released=true")
                stop()
                return
            }
            let state = (index: control.selectedSegmentIndex,
                         tracking: control.isTracking,
                         highlighted: control.isHighlighted)
            let isLast = milliseconds >= 500
            let text = "selectedSegmentIndex=\(state.index) tracking=\(state.tracking) "
                + "highlighted=\(state.highlighted)"
            func record(_ marker: String) {
                SettingsTrace.record("native segment sample touchID=\(touchID) frame=\(frames) "
                    + "ms=\(milliseconds) \(marker) \(text)")
            }
            if previous == nil {
                record("first")
            } else if previous! != state, !isLast {
                if changes < Self.maximumChanges {
                    changes += 1
                    record("change")
                } else {
                    truncated = true
                }
            }
            previous = state
            if isLast {
                record(truncated ? "last truncated=true" : "last")
                stop()
            }
        }
    }

    @MainActor
    private final class TouchObserver: UIGestureRecognizer {
        private struct WeakRecognizer {
            weak var value: UIGestureRecognizer?
        }

        private static let switchEvents: [(event: UIControl.Event, action: Selector)] = [
            (.touchDown, #selector(TouchObserver.switchTouchDown(_:))),
            (.touchUpInside, #selector(TouchObserver.switchTouchUpInside(_:))),
            (.touchUpOutside, #selector(TouchObserver.switchTouchUpOutside(_:))),
            (.touchCancel, #selector(TouchObserver.switchTouchCancel(_:)))
        ]

        private static let segmentEvents: [(event: UIControl.Event, action: Selector)] = [
            (.touchDown, #selector(TouchObserver.segmentTouchDown(_:forEvent:))),
            (.touchUpInside, #selector(TouchObserver.segmentTouchUpInside(_:forEvent:))),
            (.touchUpOutside, #selector(TouchObserver.segmentTouchUpOutside(_:forEvent:))),
            (.touchCancel, #selector(TouchObserver.segmentTouchCancel(_:forEvent:)))
        ]

        private var nextTouchID = 0
        private var activeTouches: [ObjectIdentifier: Int] = [:]
        private let tracesNativeControls: Bool
        private let tracesSwitchEvents: Bool
        private var switchRecognizers: [WeakRecognizer] = []
        private weak var nativeView: UIView?
        private var nativeKind = "unavailable"
        private var nativeIdentifier = "unavailable"
        private var nativeTouchID: Int?
        private var nativeDeliveryTouchID: Int?
        private var nativeTouchPoint: CGPoint?
        private var nativeValueChangedCount = 0
        private var nativePrimaryActionCount = 0
        private var segmentSampler: SegmentSampler?

        override init(target: Any?, action: Selector?) {
            let arguments = ProcessInfo.processInfo.arguments
            let tracesNativeControls = arguments.contains("-settings-native-control-trace")
            self.tracesNativeControls = tracesNativeControls
            // The two diagnostic scenarios opt in already; their UIKit switch may have no AX ID.
            tracesSwitchEvents = tracesNativeControls && (arguments.contains("-claude-mood-demo")
                || arguments.contains("-overview-credits-demo"))
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

        private func stopControlEvents() {
            segmentSampler?.stop()
            segmentSampler = nil
            if tracesSwitchEvents, let toggle = nativeView as? UISwitch {
                for (event, action) in Self.switchEvents {
                    toggle.removeTarget(self, action: action, for: event)
                }
            }
            if let segment = nativeView as? UISegmentedControl {
                for (event, action) in Self.segmentEvents {
                    segment.removeTarget(self, action: action, for: event)
                }
            }
            for recognizer in switchRecognizers {
                recognizer.value?.removeTarget(self, action: #selector(switchGestureChanged(_:)))
            }
            switchRecognizers.removeAll(keepingCapacity: true)
        }

        func stopNativeControlTrace() {
            stopControlEvents()
            if let control = nativeView as? UIControl {
                control.removeTarget(self, action: #selector(nativeValueChanged(_:)), for: .valueChanged)
                control.removeTarget(self, action: #selector(nativePrimaryAction(_:)), for: .primaryActionTriggered)
            }
            nativeView = nil
            nativeTouchID = nil
            nativeDeliveryTouchID = nil
            nativeTouchPoint = nil
            nativeValueChangedCount = 0
            nativePrimaryActionCount = 0
            SettingsTrace.nativeControlBoundary = nil
        }

        private func observeNativeControl(for touch: UITouch, id: Int) {
            // A new touch ends the previous control's tracking trace before any early return.
            stopControlEvents()
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
                if tracesSwitchEvents {
                    observeSwitchEvents(on: toggle, from: touch.view)
                }
            } else if let segment = candidate as? UISegmentedControl {
                nativeKind = "segment"
                segment.addTarget(self, action: #selector(nativeValueChanged(_:)), for: .valueChanged)
                for (event, action) in Self.segmentEvents {
                    segment.addTarget(self, action: action, for: event)
                }
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
            if let segment = hit as? UISegmentedControl {
                nativeTouchPoint = touch.location(in: segment)
                segmentSampler?.stop()
                let sampler = SegmentSampler(touchID: id, control: segment)
                segmentSampler = sampler
                sampler.start()
            }
        }

        private func observeSwitchEvents(on toggle: UISwitch, from hit: UIView?) {
            for (event, action) in Self.switchEvents {
                toggle.addTarget(self, action: action, for: event)
            }
            var ancestor = hit
            while let current = ancestor {
                for recognizer in current.gestureRecognizers ?? []
                    where !switchRecognizers.contains(where: { $0.value === recognizer }) {
                    switchRecognizers.append(WeakRecognizer(value: recognizer))
                    recognizer.addTarget(self, action: #selector(switchGestureChanged(_:)))
                }
                if current === toggle { break }
                ancestor = current.superview
            }
        }

        @objc private func switchTouchDown(_ sender: UISwitch) { recordSwitchEvent("touchDown", sender) }
        @objc private func switchTouchUpInside(_ sender: UISwitch) { recordSwitchEvent("touchUpInside", sender) }
        @objc private func switchTouchUpOutside(_ sender: UISwitch) { recordSwitchEvent("touchUpOutside", sender) }
        @objc private func switchTouchCancel(_ sender: UISwitch) { recordSwitchEvent("touchCancel", sender) }

        private func recordSwitchEvent(_ event: String, _ sender: UISwitch) {
            guard sender === nativeView, let id = nativeTouchID else { return }
            SettingsTrace.record("native switch control-event touchID=\(id) control=\(ObjectIdentifier(sender)) "
                + "event=\(event) switchOn=\(sender.isOn) tracking=\(sender.isTracking) inside=\(sender.isTouchInside)")
        }

        @objc private func segmentTouchDown(_ sender: UISegmentedControl, forEvent event: UIEvent) {
            recordSegmentEvent("touchDown", sender, event: event)
        }
        @objc private func segmentTouchUpInside(_ sender: UISegmentedControl, forEvent event: UIEvent) {
            recordSegmentEvent("touchUpInside", sender, event: event)
        }
        @objc private func segmentTouchUpOutside(_ sender: UISegmentedControl, forEvent event: UIEvent) {
            recordSegmentEvent("touchUpOutside", sender, event: event)
        }
        @objc private func segmentTouchCancel(_ sender: UISegmentedControl, forEvent event: UIEvent) {
            recordSegmentEvent("touchCancel", sender, event: event)
        }

        private func recordSegmentEvent(_ name: String, _ sender: UISegmentedControl, event: UIEvent) {
            guard sender === nativeView, let id = nativeTouchID else { return }
            // The event's own touch is where the control saw it; the stored point is the recognizer's.
            let point: String
            if let touch = event.allTouches?.first {
                point = "point=\(touch.location(in: sender)) pointSource=control-event"
            } else if let stored = nativeTouchPoint {
                point = "point=\(stored) pointSource=recognizer"
            } else {
                point = "point=unavailable pointSource=none"
            }
            SettingsTrace.record("native segment control-event touchID=\(id) control=\(ObjectIdentifier(sender)) "
                + "event=\(name) selectedSegmentIndex=\(sender.selectedSegmentIndex) "
                + "tracking=\(sender.isTracking) highlighted=\(sender.isHighlighted) \(point)")
        }

        @objc private func switchGestureChanged(_ sender: UIGestureRecognizer) {
            guard let control = nativeView, let id = nativeTouchID,
                  switchRecognizers.contains(where: { $0.value === sender }) else { return }
            SettingsTrace.record("native switch recognizer-action touchID=\(id) control=\(ObjectIdentifier(control)) "
                + "recognizer=\(ObjectIdentifier(sender)) state=\(sender.state.rawValue)")
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
            // The control's own actions run after the recognizer, so the latest point is the one they see.
            if let control = nativeView, id == nativeTouchID {
                nativeTouchPoint = touch.location(in: control)
            }
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
