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

    private func present(
        _ driver: Driver, reduced: Bool = false,
        capture: @escaping (Double, Bool) -> Void
    ) async throws -> (UIWindow, UIHostingController<Host>) {
        try await presentNativeRendering(Host(driver: driver, reduced: reduced, capture: capture))
    }

    private func capture(_ window: UIWindow, commitUpdates: Bool = false) -> UIImage {
        if commitUpdates { window.layoutIfNeeded() }
        return UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: commitUpdates),
                "the native sheet must remain renderable during the captured step")
        }
    }

    func testLeavingChooserNeverDrawsOverTheKeyStep() async throws {
        let driver = Driver()
        let reachedExit = XCTestExpectation(description: "real intermediate exit")
        let reachedEntrance = XCTestExpectation(description: "real intermediate entrance")
        let settled = XCTestExpectation(description: "native entrance completed")
        var exit: UIImage?
        var entrance: UIImage?
        weak var host: UIWindow?
        var finished = false
        let (window, _) = try await present(driver) { opacity, leaving in
            guard let host else { return }
            if leaving, opacity > 0.15, opacity < 0.85, exit == nil {
                exit = self.capture(host)
                reachedExit.fulfill()
            }
            if !leaving, opacity > 0.3, opacity < 0.85, entrance == nil {
                entrance = self.capture(host)
                reachedEntrance.fulfill()
            }
            if !leaving, opacity == 1, entrance != nil, !finished {
                finished = true
                settled.fulfill()
            }
        }
        defer {
            host = nil
            window.isHidden = true
            window.rootViewController = nil
        }
        host = window
        let before = capture(window, commitUpdates: true)
        let region = try cardRegion(in: window)
        driver.stage = .awaitingKey(.opencode)
        await fulfillment(of: [reachedExit, reachedEntrance, settled], timeout: 5, enforceOrder: true)
        let leaving = try XCTUnwrap(exit)
        let arriving = try XCTUnwrap(entrance)
        let after = capture(window, commitUpdates: true)
        let beforePixels = try cardPixels(before, in: region)
        let afterPixels = try cardPixels(after, in: region)
        let leavingPixels = try cardPixels(leaving, in: region)
        let arrivingPixels = try cardPixels(arriving, in: region)
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
        attach(leaving, name: "sign-in-exit")
        attach(arriving, name: "sign-in-enter")
    }

    func testInterruptedExitReversesFromCurrentOpacityWithoutIntroducingAnotherStep() async throws {
        let driver = Driver()
        let reversed = expectation(description: "exit interrupted at a native frame")
        let resumed = expectation(description: "same card resumes at an intermediate opacity")
        let completed = expectation(description: "reversal settles before teardown")
        var didComplete = false
        var exitOpacity: Double?
        var resumedOpacity: Double?
        var reversal: UIImage?
        weak var host: UIWindow?
        let (window, _) = try await present(driver) { opacity, leaving in
            guard let host else { return }
            if leaving, opacity > 0.2, opacity < 0.8, exitOpacity == nil {
                exitOpacity = opacity
                driver.stage = .choosing
                reversed.fulfill()
            } else if !leaving, opacity < 0.95, opacity > 0, exitOpacity != nil, resumedOpacity == nil {
                resumedOpacity = opacity
                reversal = self.capture(host)
                resumed.fulfill()
            }
            if !leaving, opacity == 1, resumedOpacity != nil, !didComplete {
                didComplete = true
                completed.fulfill()
            }
        }
        defer {
            host = nil
            window.isHidden = true
            window.rootViewController = nil
        }
        host = window
        let before = capture(window, commitUpdates: true)
        let region = try cardRegion(in: window)
        let beforeBounds = window.bounds
        let beforeInsets = window.safeAreaInsets
        driver.stage = .awaitingKey(.opencode)
        await fulfillment(of: [reversed, resumed, completed], timeout: 5, enforceOrder: true)
        XCTAssertNotNil(exitOpacity)
        XCTAssertLessThan(try XCTUnwrap(resumedOpacity), 0.95, "reversal must not jump to full opacity")
        XCTAssertEqual(driver.stage, .choosing)
        let restored = capture(window, commitUpdates: true)
        let returning = try XCTUnwrap(reversal)
        attach(returning, name: "sign-in-reverse")
        let chooserCard = try cardImage(before, in: region)
        let restoredCard = try cardImage(restored, in: region)
        let chooserPixels = try cardPixels(chooserCard)
        let restoredPixels = try cardPixels(restoredCard)
        let matches = chooserPixels == restoredPixels
        if !matches {
            attach(before, name: "sign-in-reverse-before")
            attach(restored, name: "sign-in-reverse-restored")
            let width = chooserCard.width, height = chooserCard.height
            let sameSize = width == restoredCard.width && height == restoredCard.height
            var mismatch = "not comparable: crop dimensions differ"
            if sameSize {
                var count = 0, minX = width, minY = height, maxX = -1, maxY = -1
                for index in chooserPixels.indices where chooserPixels[index] != restoredPixels[index] {
                    let x = index % width, y = index / width
                    count += 1
                    minX = min(minX, x); minY = min(minY, y)
                    maxX = max(maxX, x); maxY = max(maxY, y)
                }
                mismatch = "\(count) pixels; inclusive crop-buffer bounds (column,row)=(\(minX),\(minY))–(\(maxX),\(maxY))"
            }
            print("""
                [SignInReversal] \(mismatch); crop pt=\(region), before=\(width)x\(height) px @\(before.scale), restored=\(restoredCard.width)x\(restoredCard.height) px @\(restored.scale)
                [SignInReversal] window bounds \(beforeBounds) → \(window.bounds), safe area \(beforeInsets) → \(window.safeAreaInsets)
                [SignInReversal] driver=\(driver.stage), exit=\(String(describing: exitOpacity)), resumed=\(String(describing: resumedOpacity)), completion opacity callback at 1=\(didComplete)
                """)
        }
        XCTAssertTrue(matches, "the original chooser pixels return after reversal")

        let keyDriver = Driver()
        keyDriver.stage = .awaitingKey(.opencode)
        let (keyWindow, _) = try await present(keyDriver) { _, _ in }
        defer { keyWindow.isHidden = true; keyWindow.rootViewController = nil }
        let keyPixels = try cardPixels(capture(keyWindow, commitUpdates: true), in: region)
        let returningPixels = try cardPixels(returning, in: region)
        let keyOnly = chooserPixels.indices.filter { brightness(chooserPixels[$0]) < 150 && brightness(keyPixels[$0]) > 550 }
        XCTAssertFalse(keyOnly.isEmpty, "the real key card has ink independent of the chooser")
        XCTAssertTrue(chooserPixels.indices.contains {
            brightness(chooserPixels[$0]) > 550 && brightness(keyPixels[$0]) < 150 && brightness(returningPixels[$0]) > 150
        }, "the reversing frame shows independent chooser ink")
        XCTAssertFalse(keyOnly.contains { brightness(returningPixels[$0]) > 150 },
                       "the key card never flashes during the interrupted reversal")
    }

    func testReducedMotionCutsWithoutAnyIntermediateNativeOpacity() async throws {
        let driver = Driver()
        var intermediate: [Double] = []
        let (window, _) = try await present(driver, reduced: true) { opacity, _ in
            if opacity > 0, opacity < 1 { intermediate.append(opacity) }
        }
        defer { window.isHidden = true; window.rootViewController = nil }
        let before = capture(window, commitUpdates: true)
        let region = try cardRegion(in: window)
        driver.stage = .awaitingKey(.opencode)
        // A display link fires before the next update. Ask UIKit for the first committed change.
        let first = capture(window, commitUpdates: true)
        await FrameGate().next()
        let settled = capture(window, commitUpdates: true)
        attach(first, name: "sign-in-reduced-first-commit")
        attach(settled, name: "sign-in-reduced-settled")
        let firstPixels = try cardPixels(first, in: region)
        let settledPixels = try cardPixels(settled, in: region)
        XCTAssertTrue(firstPixels == settledPixels, "the first updated frame already paints the settled key card")
        let beforePixels = try cardPixels(before, in: region)
        XCTAssertFalse(beforePixels == settledPixels, "the key card actually replaces the chooser pixels")
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

    private func cardImage(_ snapshot: UIImage, in region: CGRect) throws -> CGImage {
        let source = try XCTUnwrap(snapshot.cgImage)
        let rect = region.applying(CGAffineTransform(scaleX: snapshot.scale, y: snapshot.scale))
        return try XCTUnwrap(source.cropping(to: rect))
    }

    private func cardPixels(_ snapshot: UIImage, in region: CGRect) throws -> [UInt32] {
        try cardPixels(cardImage(snapshot, in: region))
    }

    private func cardPixels(_ image: CGImage) throws -> [UInt32] {
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
