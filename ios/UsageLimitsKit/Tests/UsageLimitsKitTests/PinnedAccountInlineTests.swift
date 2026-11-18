import XCTest
@testable import UsageLimitsKit

/// The inline accessory shows the first pinned account only (ADR 0003, docs/adr/0003-status-icons-and-lock-screen-substitute.md, section
/// "iOS"): its glyph as the family's one image and its figure with the side as text. These pin down
/// that text in English and German, how an outdated figure is marked without an underline, and
/// that the reload interval follows the capture.
final class PinnedAccountInlineTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private struct German: PinnedAccountTexts {
        let error = "Fehler"
        let signInExpired = "Anmeldung abgelaufen"
        let exhausted = "Aufgebraucht"
        let unknown = "Unbekannt"
        let outdated = "Veraltet"
        func withSign(_ token: String) -> String { "\(token) %" }
        func spoken(_ figure: String, _ mode: PercentDisplay) -> String {
            mode == .used ? "\(figure) verbraucht" : "\(figure) übrig"
        }
        func sideWord(_ mode: PercentDisplay) -> String { mode == .used ? "verbraucht" : "übrig" }
    }

    private func entry(_ provider: String?, title: String = "Account", remaining: Double?,
        mode: PercentDisplay = .left, severity: Severity = .healthy, validity: ReadingValidity = .current,
        connectionStatus: ConnectionStatus = .unknown) -> PinnedAccountEntry {
        let p = WindowPresentation(remaining: remaining, severity: severity, validity: validity, mode: mode)
        return PinnedAccountEntry(account: GlanceAccount(id: provider ?? title, title: title, subtitle: nil,
            rows: [], severity: severity, providerID: provider, connectionStatus: connectionStatus), presentation: p,
            content: .of(headline: remaining == nil && severity != .exhausted ? nil : p, validity: validity,
                needsReauthentication: connectionStatus == .reconnectRequired))
    }

    private func english(_ entries: [PinnedAccountEntry]) -> PinnedAccountText.InlineRow? {
        PinnedAccountText.inlineRow(entries, texts: EnglishPinnedAccountTexts())
    }

    private func german(_ entries: [PinnedAccountEntry]) -> PinnedAccountText.InlineRow? {
        PinnedAccountText.inlineRow(entries, texts: German())
    }

    func testNothingPinnedHasNoRow() {
        XCTAssertNil(english([]))
    }

    func testTheRowIsTheFirstAccountsGlyphAndItsFullFigureWithSide() {
        XCTAssertEqual(english([entry("claude", remaining: 42)]), .init(glyphProviderID: "claude", text: "42% left"))
        XCTAssertEqual(german([entry("claude", remaining: 42)]), .init(glyphProviderID: "claude", text: "42 % übrig"))
        XCTAssertEqual(english([entry("claude", remaining: 42, mode: .used)])?.text, "58% used")
        XCTAssertEqual(german([entry("claude", remaining: 42, mode: .used)])?.text, "58 % verbraucht")
        XCTAssertEqual(english([entry("codex", remaining: 0.4)])?.text, "<1% left")
        XCTAssertEqual(english([entry("codex", remaining: 99.6)])?.text, ">99% left")
        XCTAssertEqual(english([entry("kimi", remaining: 100)])?.text, "100% left")
        // A provider this build does not know leads with the fallback disc.
        XCTAssertEqual(english([entry(nil, title: "Mistral", remaining: 42)])?.glyphProviderID, nil)
    }

    func testFurtherAccountsAreLeftOut() {
        let three = [entry("claude", remaining: 42), entry("codex", remaining: 7), entry("kimi", remaining: 100)]
        XCTAssertEqual(english(three), .init(glyphProviderID: "claude", text: "42% left"))
        XCTAssertEqual(german(three), .init(glyphProviderID: "claude", text: "42 % übrig"))
    }

    func testStatesWithoutANumberAreWords() {
        XCTAssertEqual(english([entry("devin", remaining: 0, severity: .exhausted)])?.text, "Exhausted")
        XCTAssertEqual(german([entry("devin", remaining: 0, severity: .exhausted)])?.text, "Aufgebraucht")
        XCTAssertEqual(english([entry("zai", remaining: 42, validity: .error)])?.text, "Error")
        XCTAssertEqual(german([entry("zai", remaining: 42, validity: .error)])?.text, "Fehler")
        XCTAssertEqual(english([entry("zai", remaining: 42, connectionStatus: .reconnectRequired)])?.text,
            "Sign-in expired")
        XCTAssertEqual(german([entry("zai", remaining: 42, connectionStatus: .reconnectRequired)])?.text,
            "Anmeldung abgelaufen")
        XCTAssertEqual(english([entry("meta", remaining: nil)])?.text, "Unknown")
        XCTAssertEqual(german([entry("meta", remaining: nil)])?.text, "Unbekannt")
    }

    func testAnOutdatedFigureSaysSoInWordsBecauseInlineDropsTheUnderline() {
        XCTAssertEqual(english([entry("claude", remaining: 42, validity: .stale)])?.text, "42% left · Stale")
        XCTAssertEqual(german([entry("claude", remaining: 42, validity: .resetDue)])?.text, "42 % übrig · Veraltet")
        XCTAssertEqual(english([entry("kimi", remaining: 100, validity: .stale)])?.text, "100% left · Stale")
    }

    func testOnlyAnUnreadableSnapshotAsksForAnEarlyReload() {
        XCTAssertEqual(PinnedAccountsCapture.capture(source: .unreadable, at: now).reloadInterval, 15 * 60)
        for source: GlanceSnapshotCodec.Load in [.corrupt, .empty, .missing, .loaded(.empty)] {
            XCTAssertEqual(PinnedAccountsCapture.capture(source: source, at: now).reloadInterval, 4 * 60 * 60)
        }
    }
}
