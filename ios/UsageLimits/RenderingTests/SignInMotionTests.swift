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
            AddAccountSheet(stage: $driver.stage, captureStep: capture)
                .environmentObject(driver.store)
                .environment(\.colorScheme, .dark)
                .environment(\.reduceMotionOverride, reduced)
        }
    }

    private struct Capture {
        let image: UIImage
        let labels: [String]
        let opacity: Double
        let leaving: Bool
    }

    private func present(
        _ driver: Driver, reduced: Bool = false,
        capture: @escaping (Double, Bool) -> Void
    ) async throws -> (UIWindow, UIHostingController<Host>) {
        try await presentNativeRendering(Host(driver: driver, reduced: reduced, capture: capture))
    }

    private func elements(_ object: NSObject) -> [NSObject] {
        if object.isAccessibilityElement { return [object] }
        let count = object.accessibilityElementCount()
        if count > 0, count < 1_000 {
            return (0..<count).flatMap { index in
                (object.accessibilityElement(at: index) as? NSObject).map(elements) ?? []
            }
        }
        return (object as? UIView)?.subviews.flatMap(elements) ?? []
    }

    private func labels(_ view: UIView) -> [String] {
        elements(view).compactMap(\.accessibilityLabel)
    }

    private func capture(_ view: UIView, opacity: Double, leaving: Bool) -> Capture {
        let image = UIGraphicsImageRenderer(bounds: view.bounds).image { _ in
            XCTAssertTrue(view.drawHierarchy(in: view.bounds, afterScreenUpdates: false),
                "the committed native sheet must remain renderable during the captured step")
        }
        return Capture(image: image, labels: labels(view), opacity: opacity, leaving: leaving)
    }

    func testLeavingChooserIsNotAccessibleOrActionableAndNeverDrawsOverTheKeyStep() async throws {
        let driver = Driver()
        let reachedExit = expectation(description: "real intermediate exit")
        let reachedEntrance = expectation(description: "real intermediate entrance")
        let settled = expectation(description: "native entrance completed")
        var exit: Capture?
        var entrance: Capture?
        var staleButton: NSObject?
        var hostView: UIView?
        var finished = false
        let (window, host) = try await present(driver) { opacity, leaving in
            guard let hostView else { return }
            if leaving, opacity > 0.15, opacity < 0.85, exit == nil {
                exit = self.capture(hostView, opacity: opacity, leaving: leaving)
                _ = staleButton?.accessibilityActivate()
                reachedExit.fulfill()
            }
            if !leaving, opacity > 0.15, opacity < 0.85, entrance == nil {
                entrance = self.capture(hostView, opacity: opacity, leaving: leaving)
                reachedEntrance.fulfill()
            }
            if !leaving, opacity == 1, entrance != nil, !finished {
                finished = true
                settled.fulfill()
            }
        }
        defer { window.isHidden = true; window.rootViewController = nil }
        hostView = host.view
        // Layout alone need not have published the hosting view's accessibility nodes yet.
        await nextFrame(in: host.view)
        staleButton = elements(host.view).first { $0.accessibilityLabel?.contains("Kimi") == true }
        XCTAssertNotNil(staleButton, "the real chooser exposes the provider action")
        let before = capture(host.view, opacity: 1, leaving: false)
        driver.stage = .awaitingKey(.opencode)
        await fulfillment(of: [reachedExit, reachedEntrance, settled], timeout: 5, enforceOrder: true)
        let leaving = try XCTUnwrap(exit)
        let arriving = try XCTUnwrap(entrance)
        XCTAssertFalse(leaving.labels.contains { $0.contains("Kimi") || $0.contains("OpenCode") },
                       "leaving content is absent from VoiceOver; the native toolbar remains")
        let after = capture(host.view, opacity: 1, leaving: false)
        let beforePixels = try pixels(before.image)
        let afterPixels = try pixels(after.image)
        let leavingPixels = try pixels(leaving.image)
        let arrivingPixels = try pixels(arriving.image)
        // Probe pure-ink centres, not antialiased edges or the sheet's native toolbar.
        var oldOnly: [Int] = []
        var newOnly: [Int] = []
        let imageWidth = try XCTUnwrap(before.image.cgImage).width
        let imageScale = before.image.scale
        for index in beforePixels.indices where CGFloat(index / imageWidth) / imageScale > 120 {
            let oldInk = brightness(beforePixels[index])
            let newInk = brightness(afterPixels[index])
            if oldInk > 550, newInk < 150 { oldOnly.append(index) }
            if newInk > 550, oldInk < 150 { newOnly.append(index) }
        }
        XCTAssertFalse(oldOnly.isEmpty, "the chooser has independent ink probes")
        XCTAssertFalse(newOnly.isEmpty, "the key card has independent ink probes")
        XCTAssertFalse(newOnly.contains { brightness(leavingPixels[$0]) > 300 },
                       "the key step never draws over the leaving chooser")
        XCTAssertFalse(oldOnly.contains { brightness(arrivingPixels[$0]) > 300 },
                       "the chooser has gone before the key step appears")
        XCTAssertEqual(driver.stage, .awaitingKey(.opencode), "a retained outgoing accessibility action cannot select Kimi")
        XCTAssertFalse(arriving.labels.contains { $0.contains("Kimi") })
        XCTAssertTrue(arriving.labels.contains { $0.contains("Paste your OpenCode Go key") })
        // In the native sheet there is one card slot: the leaving chooser is taller than the
        // key card. No provider rows may remain in the final frame, even outside the new card.
        XCTAssertFalse(labels(host.view).contains { $0.contains("Shows a code") })
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
        var view: UIView?
        let (window, host) = try await present(driver) { opacity, leaving in
            if leaving, opacity > 0.2, opacity < 0.8, exitOpacity == nil {
                exitOpacity = opacity
                driver.stage = .choosing
                reversed.fulfill()
            } else if !leaving, opacity < 0.95, opacity > 0, exitOpacity != nil, resumedOpacity == nil {
                resumedOpacity = opacity
                if let view {
                    XCTAssertFalse(self.labels(view).contains { $0.contains("Paste your") })
                    self.attach(self.capture(view, opacity: opacity, leaving: false).image, name: "sign-in-reverse")
                }
                resumed.fulfill()
            }
            if !leaving, opacity == 1, resumedOpacity != nil, !didComplete {
                didComplete = true
                completed.fulfill()
            }
        }
        defer { window.isHidden = true; window.rootViewController = nil }
        view = host.view
        await nextFrame(in: host.view)
        driver.stage = .awaitingKey(.opencode)
        await fulfillment(of: [reversed, resumed, completed], timeout: 5, enforceOrder: true)
        XCTAssertNotNil(exitOpacity)
        XCTAssertLessThan(try XCTUnwrap(resumedOpacity), 0.95, "reversal must not jump to full opacity")
        XCTAssertEqual(driver.stage, .choosing)
    }

    func testReducedMotionCutsWithoutAnyIntermediateNativeOpacity() async throws {
        let driver = Driver()
        var intermediate: [Double] = []
        let (window, host) = try await present(driver, reduced: true) { opacity, _ in
            if opacity > 0, opacity < 1 { intermediate.append(opacity) }
        }
        defer { window.isHidden = true; window.rootViewController = nil }
        await nextFrame(in: host.view)
        driver.stage = .awaitingKey(.opencode)
        await nextFrame(in: host.view)
        let first = capture(host.view, opacity: 1, leaving: false)
        await nextFrame(in: host.view)
        let settled = capture(host.view, opacity: 1, leaving: false)
        XCTAssertEqual(first.image.pngData(), settled.image.pngData(), "the next frame is already settled")
        XCTAssertTrue(first.labels.contains { $0.contains("Paste your OpenCode Go key") })
        XCTAssertFalse(first.labels.contains { $0.contains("Kimi") })
        XCTAssertTrue(intermediate.isEmpty)
    }

    private func pixels(_ image: UIImage) throws -> [UInt32] {
        let image = try XCTUnwrap(image.cgImage)
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

    /// A display callback is a capture gate, not an elapsed-time assumption about an animation.
    private func nextFrame(in view: UIView) async {
        let gate = FrameGate()
        await gate.next()
        view.layoutIfNeeded()
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
