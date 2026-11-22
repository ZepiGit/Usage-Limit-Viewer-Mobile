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
        var figureBounds = CGRect.zero
    }

    private struct FigureBounds: PreferenceKey {
        static let defaultValue = CGRect.zero
        static func reduce(value: inout CGRect, nextValue: () -> CGRect) { value = nextValue() }
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
                        .background(GeometryReader { geometry in
                            Color.clear.preference(key: FigureBounds.self,
                                value: geometry.frame(in: .named("hooray-stage")))
                        })
                        .frame(width: 64, height: 64)
                }
            }
            .frame(width: 160, height: 160)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black)
            .ignoresSafeArea()
            .coordinateSpace(name: "hooray-stage")
            .onPreferenceChange(FigureBounds.self) { controls.figureBounds = $0 }
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
        XCTAssertEqual(controls.figureBounds.size, CGSize(width: 36, height: 36))
        // Independent ink probes inside the raised and idle arms of the real 36pt figure.
        let raisedArm = CGPoint(x: controls.figureBounds.minX + 1, y: controls.figureBounds.minY + 10)
        let idleArm = CGPoint(x: controls.figureBounds.minX + 1, y: controls.figureBounds.minY + 18)
        XCTAssertGreaterThan(try luminance(hooray, at: raisedArm), 0.9,
            "the real 36pt Hooray left arm is raised above the Idle arm")
        XCTAssertLessThan(try luminance(hooray, at: idleArm), 0.1)
        time = 134
        await expiry.release()
        await playback.expiry?.value
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        XCTAssertNil(playback.startedAt("claude"))
        let steady = capture(window, name: "native-hooray-expired-idle")
        XCTAssertLessThan(try luminance(steady, at: raisedArm), 0.1)
        XCTAssertGreaterThan(try luminance(steady, at: idleArm), 0.9)
        XCTAssertNotEqual(hooray.pngData(), steady.pngData(), "actual visible arms must return from Hooray to Idle")
        controls.showSlot = false
        _ = capture(window, name: "native-hooray-expired-slot-removed")
        controls.showSlot = true
        _ = capture(window, name: "native-hooray-expired-slot-recreated")
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
        XCTAssertLessThan(try luminance(idle,
            at: CGPoint(x: controls.figureBounds.minX + 1, y: controls.figureBounds.minY + 10)), 0.1)
        XCTAssertGreaterThan(try luminance(idle,
            at: CGPoint(x: controls.figureBounds.minX + 1, y: controls.figureBounds.minY + 18)), 0.9)
    }

    private func luminance(_ image: UIImage, at point: CGPoint) throws -> Double {
        let cgImage = try XCTUnwrap(image.cgImage)
        var pixels = [UInt8](repeating: 0, count: cgImage.width * cgImage.height * 4)
        return try pixels.withUnsafeMutableBytes { buffer in
            let context = try XCTUnwrap(CGContext(data: buffer.baseAddress, width: cgImage.width,
                height: cgImage.height, bitsPerComponent: 8, bytesPerRow: cgImage.width * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height))
            let offset = (Int(point.y * image.scale) * cgImage.width + Int(point.x * image.scale)) * 4
            return Double(buffer[offset]) / 255
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
