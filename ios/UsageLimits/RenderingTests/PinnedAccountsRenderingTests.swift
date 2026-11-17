import SwiftUI
import UIKit
import WidgetKit
import XCTest
import UsageLimitsKit

/// Renders the Pinned accounts widget's real views at the three accessory sizes, with synthetic
/// accounts, for a person to look at.
///
/// These are view renderings at accessory sizes, NOT Lock Screen screenshots: no wallpaper, no
/// system vibrancy, no system inline font. The views are drawn white on black with
/// `ImageRenderer`, which is the closest a simulator gets without placing a widget by hand. Real
/// Lock Screen placement and tinting stay a device check.
///
/// Runs hosted in the app so `String(localized:)` reads the app's catalog; the workflow runs it
/// once per language with `-testLanguage`. Output goes to the directory in `PINNED_RENDERINGS`
/// (set as `TEST_RUNNER_PINNED_RENDERINGS`), which the workflow uploads.
@MainActor
final class PinnedAccountsRenderingTests: XCTestCase {

    /// The families at their size on a 393 × 852 pt iPhone (Apple's Human Interface Guidelines,
    /// "Widgets", accessory sizes).
    private static let families: [(name: String, family: WidgetFamily, size: CGSize)] = [
        ("rectangular", .accessoryRectangular, CGSize(width: 160, height: 72)),
        ("circular", .accessoryCircular, CGSize(width: 72, height: 72)),
        ("inline", .accessoryInline, CGSize(width: 234, height: 26)),
    ]

    private let now = Date(timeIntervalSince1970: 1_789_206_060) // 2026-09-12 09:41 UTC
    private let staleAfter: TimeInterval = 3600

    func testRenderEveryFamilyInEveryScenario() throws {
        let environment = ProcessInfo.processInfo.environment
        let directory = try XCTUnwrap(environment["PINNED_RENDERINGS"], "set TEST_RUNNER_PINNED_RENDERINGS")
        let language = try XCTUnwrap(environment["PINNED_LANGUAGE"], "set TEST_RUNNER_PINNED_LANGUAGE")
        // A German run that silently fell back to English would look complete and prove nothing.
        XCTAssertEqual(String(localized: "Locked"), language == "de" ? "Gesperrt" : "Locked")
        XCTAssertNotNil(PinnedAccountGlyphs.shipped, "the shared glyph file is not packaged")

        let output = URL(fileURLWithPath: directory).appendingPathComponent(language)
        try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)

        let scenarios = self.scenarios()
        XCTAssertEqual(scenarios[0].capture.accounts.map(\.content), [
            StatusIconContent(content: .figure(42), outdated: false),
            StatusIconContent(content: .figure(7), outdated: true),
            StatusIconContent(content: .none, outdated: false),
        ])
        XCTAssertEqual(scenarios[1].capture.accounts.map(\.content.content), [.exhausted, .error, .unknown])

        var rows: [SheetRow] = []
        var spoken: [String] = []
        for scenario in scenarios {
            var cells: [UIImage] = []
            for family in Self.families {
                let image = try render(PinnedAccountsFamilyView(family: family.family, capture: scenario.capture),
                    size: family.size, typeSize: scenario.typeSize)
                try write(image, to: output.appendingPathComponent("\(scenario.name)-\(family.name).png"))
                cells.append(image)
            }
            rows.append(SheetRow(title: scenario.title, images: cells))
            spoken.append("\(scenario.name):")
            spoken += scenario.capture.accounts.map {
                "  " + PinnedAccountText.spoken(entry: $0, texts: LocalizedPinnedAccountTexts())
            }
            if scenario.capture.accounts.isEmpty {
                spoken.append("  " + noticeWords(scenario.capture.outcome))
            } else {
                spoken.append("  inline: " + PinnedAccountText.spokenList(scenario.capture.accounts,
                    texts: LocalizedPinnedAccountTexts()))
                spoken.append("  inline text: " + PinnedAccountText.inline(scenario.capture.accounts,
                    texts: LocalizedPinnedAccountTexts()))
            }
        }
        rows.append(SheetRow(title: "every glyph (circular, 42)", images: try glyphRow()))
        try spoken.joined(separator: "\n").appending("\n")
            .write(to: output.appendingPathComponent("voiceover.txt"), atomically: true, encoding: .utf8)

        let sheet = ContactSheet(language: language, rows: rows)
        let renderer = ImageRenderer(content: sheet)
        renderer.scale = 2
        try write(try XCTUnwrap(renderer.uiImage), to: output.appendingPathComponent("contact-sheet-\(language).png"))
    }

    // MARK: - Scenarios

    private struct Scenario {
        let name: String
        let title: String
        let capture: PinnedAccountsCapture
        var typeSize: DynamicTypeSize = .large
    }

    private func scenarios() -> [Scenario] {
        let figure = account("claude", title: "Claude Max", remaining: 42)
        let outdated = account("codex", title: "OpenAI Codex Plus", remaining: 7, fetchedAgo: 2 * 3600)
        let full = account("kimi", title: "Kimi Code", remaining: 100)
        let exhausted = account("devin", title: "Devin", remaining: 0, severity: .exhausted)
        let error = account("zai", title: "Z.ai GLM", remaining: 42, severity: .error)
        let unknown = GlanceAccount(id: "meta", title: "Meta Muse", subtitle: nil, rows: [], severity: .unknown,
            providerID: "meta")
        let first = [figure, outdated, full]
        let second = [exhausted, error, unknown]
        return [
            Scenario(name: "three-figure-outdated-full", title: "three: 42 · 7 outdated · 100 (Left)",
                capture: capture(first, mode: .left)),
            Scenario(name: "three-exhausted-error-unknown", title: "three: exhausted · error · unknown (Used)",
                capture: capture(second, mode: .used)),
            Scenario(name: "one-account", title: "one account: 42 (Left)", capture: capture([figure], mode: .left)),
            Scenario(name: "nothing-pinned", title: "nothing pinned",
                capture: capture([figure], mode: .left, pinned: false)),
            Scenario(name: "locked", title: "locked (before first unlock)",
                capture: .capture(source: .unreadable, at: now)),
            Scenario(name: "locked-largest-text", title: "locked, Dynamic Type accessibility 3",
                capture: .capture(source: .unreadable, at: now), typeSize: .accessibility3),
            Scenario(name: "corrupt", title: "unreadable snapshot", capture: .capture(source: .corrupt, at: now)),
        ]
    }

    private func account(_ provider: String, title: String, remaining: Double, severity: Severity = .medium,
        fetchedAgo: TimeInterval = 60) -> GlanceAccount {
        let row = GlanceRow(label: "5h", category: .fiveHour, remainingPercent: remaining,
            resetAt: now.addingTimeInterval(2 * 3600), severity: severity)
        return GlanceAccount(id: provider, title: title, subtitle: nil, rows: [row], severity: severity,
            fetchedAt: now.addingTimeInterval(-fetchedAgo), providerID: provider)
    }

    private func capture(_ accounts: [GlanceAccount], mode: PercentDisplay, pinned: Bool = true) -> PinnedAccountsCapture {
        let snapshot = GlanceSnapshot(accounts: accounts, accountCount: accounts.count, updatedAt: now,
            nextResetAt: nil, overallSeverity: .medium, summaryLineShort: nil, summaryLineLong: nil,
            staleAfter: staleAfter, percentDisplay: mode, statusIconsEnabled: pinned,
            pinnedAccountIDs: pinned ? accounts.map(\.id) : [])
        return .capture(snapshot: snapshot, at: now)
    }

    /// Every provider's silhouette and the fallback disc, each in the circular family at 42.
    private func glyphRow() throws -> [UIImage] {
        try (ProviderID.allCases.map(\.rawValue) + ["unknown-provider"]).map { provider in
            let capture = capture([account(provider, title: provider, remaining: 42)], mode: .left)
            return try render(PinnedAccountsFamilyView(family: .accessoryCircular, capture: capture),
                size: CGSize(width: 72, height: 72), typeSize: .large)
        }
    }

    /// The words the notice views show and VoiceOver reads for a state without accounts.
    private func noticeWords(_ outcome: ConfiguredSelectionOutcome) -> String {
        switch outcome {
        case .sourceUnavailable:
            return String(localized: "Locked") + ", " + String(localized: "Unlock to see your quota")
                + " | inline: " + String(localized: "Locked")
        case .corruptSource:
            return String(localized: "Can't read usage") + ", " + String(localized: "Open UsageLimits to rebuild the data")
                + " | inline: " + String(localized: "Can't read usage")
        default:
            return String(localized: "Pin an account in Settings") + " | inline: " + String(localized: "Pin in Settings")
        }
    }

    // MARK: - Drawing

    private func render(_ view: some View, size: CGSize, typeSize: DynamicTypeSize) throws -> UIImage {
        let framed = view
            .frame(width: size.width, height: size.height)
            .foregroundStyle(.white)
            .background(Color.black)
            .environment(\.colorScheme, .dark)
            .environment(\.dynamicTypeSize, typeSize)
        let renderer = ImageRenderer(content: framed)
        renderer.scale = 3
        return try XCTUnwrap(renderer.uiImage)
    }

    private func write(_ image: UIImage, to url: URL) throws {
        try XCTUnwrap(image.pngData()).write(to: url)
    }
}

private struct SheetRow {
    let title: String
    let images: [UIImage]
}

/// Labelled rows of renderings, each at twice its point size with its frame outlined.
private struct ContactSheet: View {
    let language: String
    let rows: [SheetRow]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(verbatim: "Pinned accounts widget, language \(language): view renderings at accessory sizes "
                + "(160×72, 72×72, 234×26 pt), white on black. NOT Lock Screen screenshots: "
                + "no wallpaper, vibrancy or system inline font (the system sets inline text in its own size).")
                .font(.system(size: 13, weight: .semibold))
                .frame(width: 900, alignment: .leading)
            ForEach(rows.indices, id: \.self) { index in
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: rows[index].title).font(.system(size: 12))
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(rows[index].images.indices, id: \.self) { cell in
                            let image = rows[index].images[cell]
                            Image(uiImage: image)
                                .resizable()
                                .interpolation(.none)
                                .frame(width: image.size.width * 2, height: image.size.height * 2)
                                .border(Color.gray, width: 1)
                        }
                    }
                }
            }
        }
        .padding(16)
        .foregroundStyle(.black)
        .background(Color.white)
    }
}
