import Combine
import SwiftUI
import UIKit
import XCTest
import UsageLimitsKit

@MainActor
final class HoorayRenderingTests: XCTestCase {
    nonisolated override func setUp() async throws {
        try await super.setUp()
        try await XCTestCase.activateNativeRenderingScene()
    }

    @MainActor
    private final class Controls: ObservableObject {
        @Published var ready = false
        @Published var showSlot = true
        weak var figureView: UIView?
    }

    private struct FigureBounds: UIViewRepresentable {
        let controls: Controls

        func makeUIView(context: Context) -> UIView {
            let view = UIView()
            view.isUserInteractionEnabled = false
            controls.figureView = view
            return view
        }

        func updateUIView(_ uiView: UIView, context: Context) {}
    }

    private actor Gate {
        private var continuation: CheckedContinuation<Void, Never>?
        private var released = false
        func hold() async {
            guard !released else { return }
            await withCheckedContinuation { continuation = $0 }
        }
        func release() { released = true; continuation?.resume(); continuation = nil }
    }

    private struct Stage: View {
        @ObservedObject var playback: HoorayPlayback
        @ObservedObject var controls: Controls
        var body: some View {
            VStack {
                if controls.showSlot {
                    ClaudeCodeFigure(mood: .idle, size: 36, ink: .white, animate: false,
                        accountID: "claude")
                        .background(FigureBounds(controls: controls))
                        .frame(width: 64, height: 64)
                }
            }
            .frame(width: 160, height: 160)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black)
            .ignoresSafeArea()
            .environment(\.scenePhase, .active)
            .environment(\.claudeAnimationVisible, true)
            .environment(\.hoorayPlayback, playback)
            .hoorayOpening(playback: playback, ready: controls.ready)
        }
    }

    func testHeldNativePlacementStartsActualStillHoorayThenExpiresAtFourElapsedSeconds() async throws {
        var time: TimeInterval = 100
        let expiry = Gate()
        let playback = HoorayPlayback(now: { time }, waitForExpiry: { await expiry.hold() })
        let controls = Controls()
        playback.open(["claude"])
        let (window, controller) = try await presentNativeRendering(Stage(playback: playback, controls: controls))
        defer { window.isHidden = true; window.rootViewController = nil }
        XCTAssertNil(playback.startedAt("claude"), "holding readiness must hold the real placed consumer")
        time = 130
        let started = expectation(description: "real eligible slot placement")
        let subscription = playback.$presentation.sink { value in
            if value.startedAt["claude"] != nil { started.fulfill() }
        }
        controls.ready = true
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        await fulfillment(of: [started], timeout: 5)
        subscription.cancel()
        XCTAssertEqual(playback.startedAt("claude"), 130, "opening time did not spend the four seconds")
        let hooray = capture(window, name: "native-hooray-still")
        let arms = try armProbes(in: window, controls: controls)
        XCTAssertGreaterThan(try luminance(hooray, at: arms.raised), 0.9,
            "the real 36pt Hooray left arm is raised above the Idle arm")
        XCTAssertLessThan(try luminance(hooray, at: arms.idle), 0.1)
        time = 134
        await expiry.release()
        await playback.expiry?.value
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        XCTAssertNil(playback.startedAt("claude"))
        let steady = capture(window, name: "native-hooray-expired-idle")
        XCTAssertLessThan(try luminance(steady, at: arms.raised), 0.1)
        XCTAssertGreaterThan(try luminance(steady, at: arms.idle), 0.9)
        controls.showSlot = false
        let removed = capture(window, name: "native-hooray-expired-slot-removed")
        XCTAssertLessThan(try luminance(removed, at: arms.idle), 0.1)
        controls.showSlot = true
        let recreated = capture(window, name: "native-hooray-expired-slot-recreated")
        let recreatedArms = try armProbes(in: window, controls: controls)
        XCTAssertLessThan(try luminance(recreated, at: recreatedArms.raised), 0.1)
        XCTAssertGreaterThan(try luminance(recreated, at: recreatedArms.idle), 0.9)
        XCTAssertNil(playback.startedAt("claude"), "a recreated native slot cannot replay")
    }

    func testNativeInterruptionClearsSharedPlaybackWithoutLaterSlotReplay() async throws {
        let expiry = Gate()
        let playback = HoorayPlayback(now: { 10 }, waitForExpiry: { await expiry.hold() })
        let controls = Controls()
        controls.ready = true
        let started = expectation(description: "eligible native layout")
        playback.open(["claude"])
        let subscription = playback.$presentation.sink { if $0.startedAt["claude"] != nil { started.fulfill() } }
        let (window, _) = try await presentNativeRendering(Stage(playback: playback, controls: controls))
        defer { window.isHidden = true; window.rootViewController = nil }
        await fulfillment(of: [started], timeout: 5)
        subscription.cancel()
        let expiryTask = playback.expiry
        playback.stop()
        controls.showSlot = false
        _ = capture(window, name: "native-hooray-interrupted-slot-removed")
        controls.showSlot = true
        await expiry.release()
        await expiryTask?.value
        XCTAssertNil(playback.startedAt("claude"))
        let idle = capture(window, name: "native-hooray-interrupted-idle")
        let arms = try armProbes(in: window, controls: controls)
        XCTAssertLessThan(try luminance(idle, at: arms.raised), 0.1)
        XCTAssertGreaterThan(try luminance(idle, at: arms.idle), 0.9)
    }

    private func armProbes(in window: UIWindow, controls: Controls) throws -> (raised: CGPoint, idle: CGPoint) {
        let marker = try XCTUnwrap(controls.figureView)
        XCTAssertTrue(marker.window === window)
        // The screenshot starts at window.bounds, not at the SwiftUI layout origin.
        let bounds = marker.convert(marker.bounds, to: window)
            .offsetBy(dx: -window.bounds.minX, dy: -window.bounds.minY)
        XCTAssertEqual(bounds.size, CGSize(width: 36, height: 36))
        return (CGPoint(x: bounds.minX + 1, y: bounds.minY + 10),
                CGPoint(x: bounds.minX + 1, y: bounds.minY + 18))
    }

    private func luminance(_ image: UIImage, at point: CGPoint) throws -> Double {
        let cgImage = try XCTUnwrap(image.cgImage)
        let rect = CGRect(x: floor(point.x * image.scale), y: floor(point.y * image.scale), width: 1, height: 1)
        let sample = try XCTUnwrap(cgImage.cropping(to: rect), "arm probe is outside the window: \(rect)")
        var pixel = [UInt8](repeating: 0, count: 4)
        return try pixel.withUnsafeMutableBytes { buffer in
            let context = try XCTUnwrap(CGContext(data: buffer.baseAddress, width: 1,
                height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(sample, in: CGRect(x: 0, y: 0, width: 1, height: 1))
            return Double(buffer[0]) / 255
        }
    }

    private func capture(_ window: UIWindow, name: String) -> UIImage {
        window.layoutIfNeeded()
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        return image
    }
}
