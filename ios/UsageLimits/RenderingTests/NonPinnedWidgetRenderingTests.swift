import SwiftUI
import UIKit
import Vision
import WidgetKit
import XCTest
import UsageLimitsKit

/// Production views at native family sizes, not installed WidgetKit or Lock Screen captures.
@available(iOS 17.0, *)
@MainActor
final class NonPinnedWidgetRenderingTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_789_206_060)
    private let staleAfter: TimeInterval = 3600
    private let families: [(String, WidgetFamily, CGSize)] = [
        ("small", .systemSmall, CGSize(width: 158, height: 158)),
        ("medium", .systemMedium, CGSize(width: 338, height: 158)),
        ("large", .systemLarge, CGSize(width: 338, height: 354)),
    ]

    func testRenderNonPinnedFamiliesInBothModesAndTextSizes() throws {
        let environment = ProcessInfo.processInfo.environment
        let directory = try XCTUnwrap(environment["NON_PINNED_RENDERINGS"], "set TEST_RUNNER_NON_PINNED_RENDERINGS")
        let language = try XCTUnwrap(environment["NON_PINNED_LANGUAGE"], "set TEST_RUNNER_NON_PINNED_LANGUAGE")
        XCTAssertEqual(String(localized: "Locked"), language == "de" ? "Gesperrt" : "Locked")
        let output = URL(fileURLWithPath: directory).appendingPathComponent(language)
        try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
        var manifest = ["Native SwiftUI view renderings. NOT installed widgets; no host tint, vibrancy or delivery proof."]
        for mode in [PercentDisplay.left, .used] {
            let snapshot = snapshot(mode: mode)
            let entry = UsageEntry(date: now, snapshot: snapshot)
            let configured = ConfiguredUsageEntry(date: now, snapshot: snapshot, transparent: false, outcome: .ready)
            for (typeName, typeSize) in [("regular", DynamicTypeSize.large), ("ax5", .accessibility5)] {
                for (name, family, size) in families {
                    let prefix = "\(mode.rawValue)-\(typeName)-\(name)"
                    let bars = try render(UsageWidgetFamilyView(family: family, entry: entry, showsProviderMark: true),
                        size: size, typeSize: typeSize)
                    try write(bars, name: "\(prefix)-bars", to: output)
                    let summary = try render(UsageWidgetFamilyView(family: family, entry: entry), size: size, typeSize: typeSize)
                    try write(summary, name: "\(prefix)-summary", to: output)
                    XCTAssertNotEqual(bars.pngData(), summary.pngData(), "configured Bars must add the provider slot, including small")
                    if typeName == "regular" && family == .systemSmall {
                        let visible = try words(bars).map(\.text).joined(separator: " ")
                        XCTAssertTrue(visible.localizedCaseInsensitiveContains(PercentText.sideWord(mode)),
                            "Bars must retain its visible side")
                    }
                    for mini in [false, true] {
                        let image = try render(ConfiguredRingGrid(entry: configured, mini: mini, ink: .light),
                            size: size, typeSize: typeSize)
                        let filename = "\(prefix)-\(mini ? "mini-rings" : "account-rings")"
                        try write(image, name: filename, to: output)
                        let recognized = try words(image)
                        assertNoSideCaption(in: recognized, context: filename)
                        if !mini && typeName == "regular" {
                            XCTAssertTrue(recognized.contains { $0.text.contains("Claude") }, "ring account label must survive")
                        }
                    }
                    let credits = try render(creditView(family: family), size: size, typeSize: typeSize)
                    try write(credits, name: "\(prefix)-credits", to: output)
                    manifest.append("\(prefix): Bars, logo-free Summary, Account Rings, Mini Rings, Credits")
                }
                for (name, size) in [("home-ring", CGSize(width: 158, height: 158)),
                                      ("circular", CGSize(width: 72, height: 72)),
                                      ("rectangular", CGSize(width: 160, height: 72))] {
                    let view: AnyView = name == "home-ring" ? AnyView(UsageRingWidgetView(entry: entry))
                        : name == "circular" ? AnyView(UsageAccessoryCircularView(entry: entry))
                        : AnyView(UsageAccessoryRectangularView(entry: entry))
                    let image = try render(view, size: size, typeSize: typeSize)
                    try write(image, name: "\(mode.rawValue)-\(typeName)-\(name)", to: output)
                    let recognized = try words(image)
                    if name != "rectangular" { assertNoSideCaption(in: recognized, context: name) }
                    else if typeName == "regular" {
                        XCTAssertTrue(recognized.map(\.text).joined(separator: " ")
                            .localizedCaseInsensitiveContains(PercentText.sideWord(mode)))
                    }
                }
            }
            let darkEntry = ConfiguredUsageEntry(date: now, snapshot: snapshot, transparent: true, tone: .dark, outcome: .ready)
            try write(try render(ConfiguredRingGrid(entry: darkEntry, mini: true, ink: .dark),
                size: CGSize(width: 158, height: 158), background: .white),
                name: "\(mode.rawValue)-transparent-dark-ink", to: output)
        }
        let single = UsageEntry(date: now, snapshot: snapshot(mode: .left).selecting(scope: .account,
            now: now, accountID: "claude"))
        let singleBars = try render(UsageWidgetFamilyView(family: .systemSmall, entry: single, showsProviderMark: true),
            size: CGSize(width: 158, height: 158))
        let singleSummary = try render(UsageWidgetFamilyView(family: .systemSmall, entry: single),
            size: CGSize(width: 158, height: 158))
        XCTAssertNotEqual(singleBars.pngData(), singleSummary.pngData())
        try write(singleBars, name: "single-account-bars", to: output)
        for outcome in [ConfiguredSelectionOutcome.noAccounts, .missingAccount, .missingPreset, .sourceUnavailable, .corruptSource] {
            let entry = ConfiguredUsageEntry(date: now, snapshot: .empty, transparent: false, outcome: outcome)
            try write(try render(ConfiguredRingGrid(entry: entry, mini: false, ink: .light),
                size: CGSize(width: 158, height: 158)), name: "empty-\(outcome)", to: output)
        }
        for failed in [false, true] {
            let row = GlanceRow(label: "5h", category: .fiveHour, remainingPercent: 42,
                resetAt: now.addingTimeInterval(-60), severity: .medium)
            let retained = GlanceAccount(id: "retained", title: "Claude Max", subtitle: "retained@example.invalid",
                rows: [row], severity: failed ? .error : .stale, baseSeverity: failed ? .error : .medium,
                fetchedAt: now.addingTimeInterval(-7200), providerID: "claude",
                connectionStatus: failed ? .reconnectRequired : .connected, iconAssetName: "ProviderIconClaudecode",
                statusMessage: failed ? NotificationEvaluator.signInExpiredMessage : nil)
            let snapshot = GlanceSnapshot(accounts: [retained], accountCount: 1, updatedAt: retained.fetchedAt,
                nextResetAt: row.resetAt, overallSeverity: retained.severity, summaryLineShort: row,
                summaryLineLong: nil, staleAfter: staleAfter, percentDisplay: .left,
                statusIconsEnabled: false, pinnedAccountIDs: [])
            let entry = UsageEntry(date: now, snapshot: snapshot)
            try write(try render(UsageRingWidgetView(entry: entry), size: CGSize(width: 158, height: 158)),
                name: failed ? "legacy-ring-failed-retained" : "legacy-ring-stale-reset-due", to: output)
            let credits = GlanceCredit(id: "retained", title: "DeepSeek", providerID: "deepseek",
                balances: [CreditBalance(currency: "USD", amount: Decimal(string: "3.18")!)],
                severity: failed ? .error : .stale, baseSeverity: failed ? .error : .low,
                fetchedAt: now.addingTimeInterval(-7200), statusMessage: failed ? "Connection lost" : nil)
            try write(try render(CreditsSmallView(entry: CreditsEntry(date: now, credits: [credits],
                staleAfter: staleAfter, updatedAt: credits.fetchedAt, state: .ready)),
                size: CGSize(width: 158, height: 158)), name: failed ? "credits-failed-retained" : "credits-stale-retained", to: output)
        }
        for state in [CreditsEntry.State.noCredits, .locked, .unreadable] {
            try write(try render(CreditsEmptyView(state: state), size: CGSize(width: 158, height: 158)),
                name: "credits-empty-\(state)", to: output)
        }
        try manifest.joined(separator: "\n").write(to: output.appendingPathComponent("MANIFEST.txt"),
            atomically: true, encoding: .utf8)
    }

    func testSteadyClaudeUsesSharedDrawingWithTransparentEyesAndWidgetInk() throws {
        let size: CGFloat = 72
        for remaining in [0.0, 15, 40, 70, 95] {
            let account = account("claude", remaining: remaining, icon: "ProviderIconClaudecode")
            let actual = try render(WidgetProviderMark(account: account, date: now, size: size, ink: .dark),
                size: CGSize(width: size, height: size), background: .clear)
            let expected = try render(ClaudeCodeStill(mood: account.claudeCodeMood(at: now), size: size,
                ink: WidgetInk.dark.primary), size: CGSize(width: size, height: size), background: .clear)
            XCTAssertEqual(actual.pngData(), expected.pngData(), "widget and app must use one drawing for \(remaining)")
            XCTAssertNotEqual(account.claudeCodeMood(at: now), .hooray)
        }
        let tired = account("claude", remaining: 15, icon: "ProviderIconClaudecode")
        let image = try render(WidgetProviderMark(account: tired, date: now, size: size, ink: .dark),
            size: CGSize(width: size, height: size), background: .clear)
        let raster = try Raster(image)
        XCTAssertLessThan(raster.pixel(x: 6.7 / 24, y: 10.2 / 24).alpha, 10, "tired eye must be an alpha cut-out")
        let body = raster.pixel(x: 12.0 / 24, y: 12.0 / 24)
        XCTAssertGreaterThan(body.alpha, 245)
        XCTAssertLessThan(body.red, 40, "monochrome uses dark widget ink, not white app artwork")
        let color = account("claude", remaining: 15, icon: "ProviderIconClaudecodeColor")
        let colored = try Raster(render(WidgetProviderMark(account: color, date: now, size: size, ink: .dark),
            size: CGSize(width: size, height: size), background: .clear))
        let terracotta = colored.pixel(x: 12.0 / 24, y: 12.0 / 24)
        XCTAssertEqual(Double(terracotta.red), 217, accuracy: 2)
        XCTAssertEqual(Double(terracotta.green), 119, accuracy: 2)
        XCTAssertEqual(Double(terracotta.blue), 87, accuracy: 2)
    }

    func testNextPresentationDateReevaluatesTheSteadyPoseWithoutACheer() throws {
        let boundary = now.addingTimeInterval(60)
        let short = GlanceRow(label: "5h", category: .fiveHour, remainingPercent: 15,
            resetAt: boundary, severity: .low)
        let weekly = GlanceRow(label: "Weekly", category: .weekly, remainingPercent: 95,
            resetAt: now.addingTimeInterval(7200), severity: .healthy)
        let account = GlanceAccount(id: "claude", title: "Claude Max", subtitle: "claude@example.invalid",
            rows: [short, weekly], severity: .low, fetchedAt: now.addingTimeInterval(-60),
            providerID: "claude", iconAssetName: "ProviderIconClaudecodeColor")
        XCTAssertEqual(account.claudeCodeMood(at: now), .tired)
        XCTAssertEqual(account.claudeCodeMood(at: boundary), .dancing)
        let before = try render(WidgetProviderMark(account: account, date: now, size: 72),
            size: CGSize(width: 72, height: 72), background: .clear)
        let after = try render(WidgetProviderMark(account: account, date: boundary, size: 72),
            size: CGSize(width: 72, height: 72), background: .clear)
        XCTAssertNotEqual(before.pngData(), after.pngData(), "a new presentation date must not freeze the old mood")
        let expected = try render(ClaudeCodeStill(mood: .dancing, size: 72, ink: UsageColors.data),
            size: CGSize(width: 72, height: 72), background: .clear)
        XCTAssertEqual(after.pngData(), expected.pngData(), "a dated reset changes the steady pose, never exports Hooray")
    }

    func testRingSpeechRetainsTheActualWindowSideStatusResetAndIdentity() throws {
        for mode in [PercentDisplay.left, .used] {
            let account = account("claude", remaining: 42, fetchedAgo: 7200, reconnect: true)
            let row = try XCTUnwrap(account.representative(at: now))
            let p = account.presentation(of: row, at: now, staleAfter: staleAfter, mode: mode)
            let speech = WidgetRingSpeech.label(account: account, row: row, presentation: p, date: now, staleAfter: staleAfter)
            XCTAssertTrue(speech.contains("claude@example.invalid"))
            XCTAssertTrue(speech.contains(row.displayLabel))
            XCTAssertTrue(speech.contains(PercentText.spoken(PercentText.figure(p), mode)))
            XCTAssertTrue(speech.contains(String(localized: "Reconnect")))
            XCTAssertTrue(speech.contains(SeverityPalette.label(.stale)))
            XCTAssertTrue(speech.contains(try XCTUnwrap(GlanceText.resetLine(resetAt: row.resetAt, now: now))))
            XCTAssertTrue(speech.contains(try XCTUnwrap(GlanceText.asOfLine(updatedAt: account.fetchedAt, now: now))))
        }
    }

    func testSmallCreditsKeepAmountAndStatusWithinNativeBoundsAtLargeType() throws {
        for typeSize in [DynamicTypeSize.large, .accessibility5] {
            let image = try render(CreditsSmallView(entry: creditEntry()), size: CGSize(width: 158, height: 158), typeSize: typeSize)
            let recognized = try words(image)
            XCTAssertTrue(recognized.contains { $0.text.contains("3") && $0.text.contains("18") }, "balance must render")
            let status = String(localized: "Stale")
            XCTAssertTrue(recognized.contains { $0.text.localizedCaseInsensitiveContains(status) }, "retained stale message must render")
            for word in recognized {
                XCTAssertGreaterThan(word.bounds.minY, 0)
                XCTAssertLessThan(word.bounds.maxY, 1)
            }
        }
    }

    private func account(_ provider: String, remaining: Double?, fetchedAgo: TimeInterval = 60,
                         reconnect: Bool = false, icon: String? = nil) -> GlanceAccount {
        let severity = remaining.map { Severity.from(remainingPercent: $0) } ?? .unknown
        let row = GlanceRow(label: "5h", category: .fiveHour, remainingPercent: remaining,
            resetAt: now.addingTimeInterval(7200), severity: severity)
        return GlanceAccount(id: provider, title: provider == "claude" ? "Claude Max" : "OpenAI Codex",
            subtitle: "\(provider)@example.invalid", rows: [row], severity: severity,
            fetchedAt: now.addingTimeInterval(-fetchedAgo), providerID: provider,
            connectionStatus: reconnect ? .reconnectRequired : .connected, iconAssetName: icon)
    }

    private func snapshot(mode: PercentDisplay) -> GlanceSnapshot {
        let accounts = [account("claude", remaining: 18, icon: "ProviderIconClaudecodeColor"),
                        account("codex", remaining: 66), account("claude-stale", remaining: nil)]
        return GlanceSnapshot(accounts: accounts, accountCount: accounts.count, updatedAt: now,
            nextResetAt: now.addingTimeInterval(7200), overallSeverity: .low,
            summaryLineShort: accounts[0].rows[0], summaryLineLong: nil, staleAfter: staleAfter, percentDisplay: mode,
            statusIconsEnabled: false, pinnedAccountIDs: [])
    }

    private func creditEntry() -> CreditsEntry {
        let credits = (0..<7).map { index in
            GlanceCredit(id: "credit-\(index)", title: index == 0 ? "DeepSeek" : "Balance \(index)", providerID: "deepseek",
                balances: [CreditBalance(currency: "USD", amount: Decimal(string: "3.18")!, total: 20)],
                severity: .stale, baseSeverity: .low, fetchedAt: now.addingTimeInterval(-7200), statusMessage: nil)
        }
        return CreditsEntry(date: now, credits: credits, staleAfter: staleAfter, updatedAt: now.addingTimeInterval(-7200), state: .ready)
    }

    @ViewBuilder
    private func creditView(family: WidgetFamily) -> some View {
        if family == .systemSmall { CreditsSmallView(entry: creditEntry()) }
        else { CreditsListView(entry: creditEntry(), limit: family == .systemLarge ? 6 : 3) }
    }

    private func render(_ view: some View, size: CGSize, typeSize: DynamicTypeSize = .large,
                        background: Color = .black) throws -> UIImage {
        let renderer = ImageRenderer(content: view.frame(width: size.width, height: size.height)
            .foregroundStyle(.white).background(background)
            .environment(\.colorScheme, .dark).environment(\.dynamicTypeSize, typeSize)
            .environment(\.reduceMotionOverride, true))
        renderer.scale = 3
        let image = try XCTUnwrap(renderer.uiImage)
        XCTAssertEqual(image.size.width, size.width, accuracy: 0.5)
        XCTAssertEqual(image.size.height, size.height, accuracy: 0.5)
        return image
    }

    private func write(_ image: UIImage, name: String, to directory: URL) throws {
        try XCTUnwrap(image.pngData()).write(to: directory.appendingPathComponent(name + ".png"))
    }

    private func words(_ image: UIImage) throws -> [(text: String, bounds: CGRect)] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["en-US", "de-DE"]
        request.usesLanguageCorrection = false
        try VNImageRequestHandler(cgImage: XCTUnwrap(image.cgImage)).perform([request])
        return (request.results ?? []).compactMap { observation in
            observation.topCandidates(1).first.map { ($0.string, observation.boundingBox) }
        }
    }

    private func assertNoSideCaption(in words: [(text: String, bounds: CGRect)], context: String) {
        let captions = ["left", "used", "übrig", "verbraucht"]
        for word in words {
            XCTAssertFalse(captions.contains(word.text.lowercased()), "visible ring side caption: \(context)")
        }
    }

    private struct Raster {
        let width: Int
        let height: Int
        let bytes: [UInt8]

        init(_ image: UIImage) throws {
            let image = try XCTUnwrap(image.cgImage)
            let pixelWidth = image.width
            let pixelHeight = image.height
            width = pixelWidth
            height = pixelHeight
            var bytes = [UInt8](repeating: 0, count: pixelWidth * pixelHeight * 4)
            try bytes.withUnsafeMutableBytes { buffer in
                let context = try XCTUnwrap(CGContext(data: buffer.baseAddress, width: pixelWidth, height: pixelHeight,
                    bitsPerComponent: 8, bytesPerRow: pixelWidth * 4, space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
                context.translateBy(x: 0, y: CGFloat(pixelHeight))
                context.scaleBy(x: 1, y: -1)
                context.draw(image, in: CGRect(x: 0, y: 0, width: CGFloat(pixelWidth), height: CGFloat(pixelHeight)))
            }
            self.bytes = bytes
        }

        func pixel(x: Double, y: Double) -> (red: UInt8, green: UInt8, blue: UInt8, alpha: UInt8) {
            let offset = (Int(y * Double(height)) * width + Int(x * Double(width))) * 4
            return (bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3])
        }
    }
}
