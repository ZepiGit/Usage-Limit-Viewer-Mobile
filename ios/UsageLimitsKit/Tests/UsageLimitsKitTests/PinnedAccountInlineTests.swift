import XCTest
@testable import UsageLimitsKit

/// The inline accessory is one text row. These pin down what it says for one, two and three
/// accounts, how it shortens names, and that the reload interval follows the capture.
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
        mode: PercentDisplay = .left, severity: Severity = .healthy,
        validity: ReadingValidity = .current) -> PinnedAccountEntry {
        let p = WindowPresentation(remaining: remaining, severity: severity, validity: validity, mode: mode)
        return PinnedAccountEntry(account: GlanceAccount(id: provider ?? title, title: title, subtitle: nil,
            rows: [], severity: severity, providerID: provider), presentation: p,
            content: .of(headline: remaining == nil && severity != .exhausted ? nil : p, validity: validity,
                needsReauthentication: false))
    }

    private func english(_ entries: [PinnedAccountEntry]) -> String {
        PinnedAccountText.inline(entries, texts: EnglishPinnedAccountTexts())
    }

    func testOneAccountSaysItsFullFigureAndSide() {
        XCTAssertEqual(english([entry("claude", remaining: 42)]), "Claude 42% left")
        XCTAssertEqual(english([entry("claude", remaining: 42, mode: .used)]), "Claude 58% used")
        XCTAssertEqual(english([entry("codex", remaining: 0.4)]), "Codex <1% left")
        XCTAssertEqual(english([entry("kimi", remaining: 100)]), "Kimi 100% left")
        XCTAssertEqual(PinnedAccountText.inline([entry("claude", remaining: 42)], texts: German()),
            "Claude 42 % übrig")
    }

    func testSeveralAccountsShowBareFiguresAndWordsForStatesWithoutNumbers() {
        XCTAssertEqual(english([entry("claude", remaining: 42), entry("codex", remaining: 87)]),
            "Claude 42 · Codex 87")
        XCTAssertEqual(english([entry("devin", remaining: 0, severity: .exhausted), entry("codex", remaining: 87)]),
            "Devin Exhausted · Codex 87")
        XCTAssertEqual(PinnedAccountText.inline([entry("zai", remaining: 42, validity: .error),
            entry("codex", remaining: 87)], texts: German()), "GLM Fehler · Codex 87")
        XCTAssertEqual(english([entry("meta", remaining: nil), entry("kimi", remaining: 100)]),
            "Muse Unknown · Kimi 100")
    }

    func testARowLongerThanThirtyTwoCharactersUsesTheIconSignsInsteadOfWords() {
        let three = [entry("xai", remaining: 99.6), entry("devin", remaining: 0, severity: .exhausted),
            entry("zai", remaining: 42, validity: .error)]
        // "Grok >99 · Devin Exhausted · GLM Error" would be 38 characters.
        XCTAssertEqual(english(three), "Grok >99 · Devin ⊘ · GLM !")
        XCTAssertEqual(PinnedAccountText.inline(three, texts: German()), "Grok >99 · Devin ⊘ · GLM !")
        XCTAssertEqual(english([entry("meta", remaining: nil), entry("kimi", remaining: 5),
            entry("claude", remaining: 42)]), "Muse — · Kimi 5 · Claude 42")
        // Exactly thirty-two characters keep their words.
        XCTAssertEqual(english([entry("meta", remaining: nil), entry("kimi", remaining: 5),
            entry("claude", remaining: 7)]), "Muse Unknown · Kimi 5 · Claude 7")
    }

    func testNamesAreCutToTheBudgetOfTheirCount() {
        // One account: up to 16 characters, two: 10, three: 6. A longer name keeps one character
        // fewer than its budget and ends in an ellipsis, so it is exactly the budget long.
        XCTAssertEqual(english([entry("antigravity", remaining: 42)]), "Antigravity 42% left")
        XCTAssertEqual(english([entry("antigravity", remaining: 42), entry("opencode", remaining: 5)]),
            "Antigravi… 42 · OpenCode 5")
        XCTAssertEqual(english([entry("antigravity", remaining: 42), entry("opencode", remaining: 5),
            entry("claude", remaining: 7)]), "Antig… 42 · OpenC… 5 · Claude 7")
        // A provider this build does not know falls back to the first word of the account title.
        XCTAssertEqual(english([entry(nil, title: "Mistral Le Chat Pro", remaining: 42)]), "Mistral 42% left")
        XCTAssertEqual(english([entry(nil, title: "Extraordinarily long", remaining: 42)]),
            "Extraordinarily 42% left")
        XCTAssertEqual(english([entry(nil, title: "Extraordinarilylong", remaining: 42)]),
            "Extraordinarily… 42% left")
    }

    func testOutdatedPartsAreMarkedForTheUnderline() {
        let parts = PinnedAccountText.inlineParts([entry("claude", remaining: 42, validity: .stale),
            entry("codex", remaining: 87)], texts: EnglishPinnedAccountTexts())
        XCTAssertEqual(parts.map(\.outdated), [true, false])
        XCTAssertEqual(parts.map(\.name), ["Claude", "Codex"])
        XCTAssertEqual(parts.map(\.value), ["42", "87"])
    }

    func testSpokenListReadsEveryAccountWithItsFullFigure() {
        let entries = [entry("claude", title: "Claude Pro", remaining: 0.4),
            entry("codex", title: "OpenAI Codex Plus", remaining: 42, validity: .stale)]
        XCTAssertEqual(PinnedAccountText.spokenList(entries, texts: EnglishPinnedAccountTexts()),
            "Claude Pro, <1% remaining; OpenAI Codex Plus, 42% remaining, Stale")
        XCTAssertEqual(PinnedAccountText.spokenList(entries, texts: German()),
            "Claude Pro, <1 % übrig; OpenAI Codex Plus, 42 % übrig, Veraltet")
    }

    func testOnlyAnUnreadableSnapshotAsksForAnEarlyReload() {
        XCTAssertEqual(PinnedAccountsCapture.capture(source: .unreadable, at: now).reloadInterval, 15 * 60)
        for source: GlanceSnapshotCodec.Load in [.corrupt, .empty, .missing, .loaded(.empty)] {
            XCTAssertEqual(PinnedAccountsCapture.capture(source: source, at: now).reloadInterval, 4 * 60 * 60)
        }
    }
}
