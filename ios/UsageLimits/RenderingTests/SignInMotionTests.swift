import SwiftUI
import UIKit
import XCTest
@testable import UsageLimits

@MainActor
private final class NativeRenderingController<Content: View>: UIHostingController<Content> {
    var appeared: (() -> Void)?

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        appeared?()
        appeared = nil
    }
}

extension XCTestCase {
    @MainActor
    static func activateNativeRenderingScene() async throws {
        let application = UIApplication.shared
        func states() -> String {
            let scenes = application.connectedScenes.map {
                "\($0.session.persistentIdentifier)=\($0.activationState.rawValue)"
            }.sorted().joined(separator: ", ")
            return "app=\(application.applicationState.rawValue), scenes=[\(scenes)]"
        }
        let scene = try XCTUnwrap(application.connectedScenes.compactMap { $0 as? UIWindowScene }.first,
            "native test host has no connected window scene; \(states())")
        // Subscribe before checking state so activation cannot fall between the check and wait.
        let activated = XCTestExpectation(description: "native host scene became foreground-active")
        let observer = NotificationCenter.default.addObserver(
            forName: UIScene.didActivateNotification, object: scene, queue: .main
        ) { _ in activated.fulfill() }
        defer { NotificationCenter.default.removeObserver(observer) }
        let initialStates = states()
        print("[NativeRenderingHost] before activation: \(initialStates)")
        if scene.activationState != .foregroundActive {
            if scene.activationState == .background {
                application.requestSceneSessionActivation(scene.session, userActivity: nil, options: nil) { error in
                    XCTFail("native test host scene activation was rejected: \(error); requested from \(initialStates)")
                }
            }
            let result = await XCTWaiter.fulfillment(of: [activated], timeout: 5)
            XCTAssertEqual(result, .completed, "native host scene activation did not complete; \(states())")
        }
        print("[NativeRenderingHost] after activation: \(states())")
        _ = try XCTUnwrap(scene.activationState == .foregroundActive ? scene : nil,
            "native rendering requires actual foreground activation; \(states())")
    }

    /// UIKit cannot render a detached window, even after layout or a display-link callback.
    @MainActor
    func presentNativeRendering<Content: View>(
        _ content: Content
    ) async throws -> (UIWindow, UIHostingController<Content>) {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = try XCTUnwrap(scenes.first { $0.activationState == .foregroundActive },
            "native rendering requires a foreground-active scene; app=\(UIApplication.shared.applicationState.rawValue), scene states=\(scenes.map { $0.activationState.rawValue })")
        let controller = NativeRenderingController(rootView: content)
        let appeared = expectation(description: "scene-attached hosting controller appeared")
        controller.appeared = { appeared.fulfill() }
        let window = UIWindow(windowScene: scene)
        window.frame = scene.coordinateSpace.bounds
        window.rootViewController = controller
        window.makeKeyAndVisible()
        await fulfillment(of: [appeared], timeout: 5)
        controller.view.layoutIfNeeded()
        XCTAssertTrue(window.isKeyWindow)
        XCTAssertTrue(controller.view.window === window)
        XCTAssertEqual(window.windowScene?.activationState, .foregroundActive)
        // The first committed render is a precondition, not a guessed number of display frames.
        _ = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true),
                "the scene-attached host must commit a real initial frame")
        }
        return (window, controller)
    }
}

/// Native hosted sheet, not a timing-vector or modifier-wiring test. Captures are gated by
/// SwiftUI reporting a real intermediate opacity; the timeout only fails a missing gate.
@MainActor
final class SignInMotionTests: XCTestCase {
    nonisolated override func setUp() async throws {
        try await super.setUp()
        try await XCTestCase.activateNativeRenderingScene()
    }

    @MainActor
    private final class Driver: ObservableObject {
        @Published var stage: AddAccountSheet.Stage = .choosing
        let store = UsageStore(container: nil)
    }

    private struct Host: View {
        @ObservedObject var driver: Driver
        let reduced: Bool
        let capture: (Double, Bool) -> Void

        var body: some View {
            AddAccountSheet.rendering(stage: $driver.stage, reduceMotion: reduced, captureStep: capture)
                .environmentObject(driver.store)
                .environment(\.colorScheme, .dark)
        }
    }

    private struct Capture {
        let image: UIImage
        let labels: [String]
    }

    private func present(
        _ driver: Driver, reduced: Bool = false,
        capture: @escaping (Double, Bool) -> Void
    ) async throws -> (UIWindow, UIHostingController<Host>) {
        try await presentNativeRendering(Host(driver: driver, reduced: reduced, capture: capture))
    }

    private func elements(_ object: NSObject) -> [NSObject] {
        guard !object.accessibilityElementsHidden else { return [] }
        if let view = object as? UIView, view.isHidden || view.alpha == 0 { return [] }
        if object.isAccessibilityElement { return [object] }
        if let children = object.accessibilityElements, !children.isEmpty {
            return children.compactMap { $0 as? NSObject }.flatMap(elements)
        }
        let count = object.accessibilityElementCount()
        if count > 0, count != NSNotFound {
            return (0..<count).compactMap { object.accessibilityElement(at: $0) as? NSObject }.flatMap(elements)
        }
        return (object as? UIView)?.subviews.flatMap(elements) ?? []
    }

    private func accessibilityTree(_ view: UIView, depth: Int = 0) -> String {
        let entry = "\(String(repeating: "  ", count: depth))\(type(of: view)) "
            + "hidden=\(view.isHidden) alpha=\(view.alpha) axHidden=\(view.accessibilityElementsHidden) "
            + "leaf=\(view.isAccessibilityElement) array=\(view.accessibilityElements?.count.description ?? "nil") "
            + "indexed=\(view.accessibilityElementCount()) frame=\(view.frame)"
        return ([entry] + view.subviews.map { accessibilityTree($0, depth: depth + 1) }).joined(separator: "\n")
    }

    private func kimiButton(in view: UIView) throws -> NSObject {
        let nodes = elements(view)
        let button = nodes.first {
            $0.accessibilityTraits.contains(.button) && $0.accessibilityLabel?.contains("Kimi") == true
        }
        if button == nil {
            let attachment = XCTAttachment(string: accessibilityTree(view))
            attachment.name = "sign-in-accessibility-hierarchy"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        return try XCTUnwrap(button, "the real chooser exposes the provider action; labels: \(nodes.compactMap(\.accessibilityLabel))")
    }

    private func capture(_ window: UIWindow, in view: UIView, commitUpdates: Bool = false) -> Capture {
        if commitUpdates { window.layoutIfNeeded() }
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: commitUpdates),
                "the native sheet must remain renderable during the captured step")
        }
        return Capture(image: image, labels: elements(view).compactMap(\.accessibilityLabel))
    }

    func testLeavingChooserIsNotAccessibleOrActionableAndNeverDrawsOverTheKeyStep() async throws {
        let driver = Driver()
        let reachedExit = XCTestExpectation(description: "real intermediate exit")
        let reachedEntrance = XCTestExpectation(description: "real intermediate entrance")
        let settled = XCTestExpectation(description: "native entrance completed")
        var exit: Capture?
        var entrance: Capture?
        var staleButton: NSObject?
        var host: (window: UIWindow, view: UIView)?
        var finished = false
        let (window, controller) = try await present(driver) { opacity, leaving in
            guard let host else { return }
            if leaving, opacity > 0.15, opacity < 0.85, exit == nil {
                exit = self.capture(host.window, in: host.view)
                _ = staleButton?.accessibilityActivate()
                reachedExit.fulfill()
            }
            if !leaving, opacity > 0.3, opacity < 0.85, entrance == nil {
                entrance = self.capture(host.window, in: host.view)
                reachedEntrance.fulfill()
            }
            if !leaving, opacity == 1, entrance != nil, !finished {
                finished = true
                settled.fulfill()
            }
        }
        defer { window.isHidden = true; window.rootViewController = nil }
        let view = try XCTUnwrap(controller.view)
        host = (window, view)
        let before = capture(window, in: view, commitUpdates: true)
        let region = try cardRegion(in: window)
        staleButton = try kimiButton(in: view)
        driver.stage = .awaitingKey(.opencode)
        await fulfillment(of: [reachedExit, reachedEntrance, settled], timeout: 5, enforceOrder: true)
        let leaving = try XCTUnwrap(exit)
        let arriving = try XCTUnwrap(entrance)
        XCTAssertFalse(leaving.labels.contains { $0.contains("Kimi") || $0.contains("OpenCode") },
                       "leaving content is absent from VoiceOver; the native toolbar remains")
        let after = capture(window, in: view, commitUpdates: true)
        let beforePixels = try cardPixels(before.image, in: region)
        let afterPixels = try cardPixels(after.image, in: region)
        let leavingPixels = try cardPixels(leaving.image, in: region)
        let arrivingPixels = try cardPixels(arriving.image, in: region)
        // Probe pure-ink centres, not antialiased edges or the sheet's native toolbar.
        var oldOnly: [Int] = []
        var newOnly: [Int] = []
        for index in beforePixels.indices {
            let oldInk = brightness(beforePixels[index])
            let newInk = brightness(afterPixels[index])
            if oldInk > 550, newInk < 150 { oldOnly.append(index) }
            if newInk > 550, oldInk < 150 { newOnly.append(index) }
        }
        XCTAssertFalse(oldOnly.isEmpty, "the chooser has independent ink probes")
        XCTAssertFalse(newOnly.isEmpty, "the key card has independent ink probes")
        XCTAssertTrue(oldOnly.contains { brightness(leavingPixels[$0]) > 150 },
                      "the committed exit still shows chooser ink")
        XCTAssertTrue(newOnly.contains { brightness(arrivingPixels[$0]) > 150 },
                      "the committed entrance already shows key-card ink")
        XCTAssertFalse(newOnly.contains { brightness(leavingPixels[$0]) > 150 },
                       "the key step never draws over the leaving chooser")
        XCTAssertFalse(oldOnly.contains { brightness(arrivingPixels[$0]) > 150 },
                       "the chooser has gone before the key step appears")
        XCTAssertEqual(driver.stage, .awaitingKey(.opencode), "a retained outgoing accessibility action cannot select Kimi")
        XCTAssertTrue(leaving.labels.contains("Cancel"), "the outgoing card is hidden, not the entire accessibility tree")
        XCTAssertFalse(arriving.labels.contains { $0.contains("Kimi") })
        XCTAssertTrue(arriving.labels.contains { $0.contains("Paste your OpenCode Go key") },
                      "the real entering key step is exposed; labels: \(arriving.labels)")
        XCTAssertTrue(after.labels.contains { $0.contains("Paste your OpenCode Go key") })
        XCTAssertFalse(after.labels.contains { $0.contains("Shows a code") })
        attach(leaving.image, name: "sign-in-exit")
        attach(arriving.image, name: "sign-in-enter")
    }

    func testInterruptedExitReversesFromCurrentOpacityWithoutIntroducingAnotherStep() async throws {
        let driver = Driver()
        let reversed = expectation(description: "exit interrupted at a native frame")
        let resumed = expectation(description: "same card resumes at an intermediate opacity")
        let completed = expectation(description: "reversal settles before teardown")
        var didComplete = false
        var exitOpacity: Double?
        var resumedOpacity: Double?
        var reversal: Capture?
        var host: (window: UIWindow, view: UIView)?
        let (window, controller) = try await present(driver) { opacity, leaving in
            if leaving, opacity > 0.2, opacity < 0.8, exitOpacity == nil {
                exitOpacity = opacity
                driver.stage = .choosing
                reversed.fulfill()
            } else if !leaving, opacity < 0.95, opacity > 0, exitOpacity != nil, resumedOpacity == nil {
                resumedOpacity = opacity
                if let host { reversal = self.capture(host.window, in: host.view) }
                resumed.fulfill()
            }
            if !leaving, opacity == 1, resumedOpacity != nil, !didComplete {
                didComplete = true
                completed.fulfill()
            }
        }
        defer { window.isHidden = true; window.rootViewController = nil }
        let view = try XCTUnwrap(controller.view)
        host = (window, view)
        driver.stage = .awaitingKey(.opencode)
        await fulfillment(of: [reversed, resumed, completed], timeout: 5, enforceOrder: true)
        XCTAssertNotNil(exitOpacity)
        XCTAssertLessThan(try XCTUnwrap(resumedOpacity), 0.95, "reversal must not jump to full opacity")
        let returning = try XCTUnwrap(reversal)
        XCTAssertFalse(returning.labels.contains { $0.contains("Paste your") })
        XCTAssertEqual(driver.stage, .choosing)
        _ = capture(window, in: view, commitUpdates: true)
        let restoredButton = try kimiButton(in: view)
        XCTAssertTrue(restoredButton.accessibilityActivate())
        XCTAssertEqual(driver.stage, .choosingRegion(.kimi), "the restored chooser action opens the region step")
        attach(returning.image, name: "sign-in-reverse")
    }

    func testReducedMotionCutsWithoutAnyIntermediateNativeOpacity() async throws {
        let driver = Driver()
        var intermediate: [Double] = []
        let (window, controller) = try await present(driver, reduced: true) { opacity, _ in
            if opacity > 0, opacity < 1 { intermediate.append(opacity) }
        }
        defer { window.isHidden = true; window.rootViewController = nil }
        let view = try XCTUnwrap(controller.view)
        let before = capture(window, in: view, commitUpdates: true)
        let region = try cardRegion(in: window)
        XCTAssertTrue(before.labels.contains { $0.contains("Kimi") })
        driver.stage = .awaitingKey(.opencode)
        // A display link fires before the next update. Ask UIKit for the first committed change.
        let first = capture(window, in: view, commitUpdates: true)
        await FrameGate().next()
        let settled = capture(window, in: view, commitUpdates: true)
        attach(first.image, name: "sign-in-reduced-first-commit")
        attach(settled.image, name: "sign-in-reduced-settled")
        let firstPixels = try cardPixels(first.image, in: region)
        let settledPixels = try cardPixels(settled.image, in: region)
        XCTAssertTrue(firstPixels == settledPixels, "the first updated frame already paints the settled key card")
        let beforePixels = try cardPixels(before.image, in: region)
        XCTAssertFalse(beforePixels == settledPixels, "the key card actually replaces the chooser pixels")
        XCTAssertTrue(first.labels.contains { $0.contains("Paste your OpenCode Go key") },
                      "first-frame accessibility labels: \(first.labels)")
        XCTAssertFalse(first.labels.contains { $0.contains("Kimi") })
        XCTAssertTrue(intermediate.isEmpty, "unexpected interpolated opacity: \(intermediate)")
    }

    private func cardRegion(in window: UIWindow) throws -> CGRect {
        func scrollView(in view: UIView) -> UIScrollView? {
            if let scroll = view as? UIScrollView, !scroll.isHidden, !scroll.bounds.isEmpty { return scroll }
            return view.subviews.lazy.compactMap { scrollView(in: $0) }.first
        }
        let host = try XCTUnwrap(window.rootViewController?.view)
        let scroll = try XCTUnwrap(scrollView(in: host), "the native sign-in card must have a scroll viewport")
        let bounds = scroll.bounds.inset(by: scroll.adjustedContentInset)
        let region = scroll.convert(bounds, to: window).intersection(window.bounds)
        XCTAssertFalse(region.isEmpty)
        return region.offsetBy(dx: -window.bounds.minX, dy: -window.bounds.minY)
    }

    private func cardPixels(_ snapshot: UIImage, in region: CGRect) throws -> [UInt32] {
        let source = try XCTUnwrap(snapshot.cgImage)
        let rect = region.applying(CGAffineTransform(scaleX: snapshot.scale, y: snapshot.scale))
        let image = try XCTUnwrap(source.cropping(to: rect))
        var result = [UInt32](repeating: 0, count: image.width * image.height)
        try result.withUnsafeMutableBytes { bytes in
            let context = try XCTUnwrap(CGContext(
                data: bytes.baseAddress, width: image.width, height: image.height, bitsPerComponent: 8,
                bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue))
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        }
        return result
    }

    private func brightness(_ pixel: UInt32) -> Int {
        Int(pixel >> 16 & 255) + Int(pixel >> 8 & 255) + Int(pixel & 255)
    }

    private func attach(_ image: UIImage, name: String) {
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    private final class FrameGate: NSObject {
        private var continuation: CheckedContinuation<Void, Never>?
        private var link: CADisplayLink?

        func next() async {
            await withCheckedContinuation { continuation in
                self.continuation = continuation
                let link = CADisplayLink(target: self, selector: #selector(fire))
                self.link = link
                link.add(to: .main, forMode: .common)
            }
        }

        @objc private func fire() {
            link?.invalidate()
            link = nil
            continuation?.resume()
            continuation = nil
        }
    }
}
