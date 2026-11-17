import XCTest
@testable import UsageLimitsKit

/// The inline accessory is one row: the system shows a single line of text and at most one image
/// there (WidgetKit, `WidgetFamily.accessoryInline`). The first pinned account's glyph is that
/// image; these pin down the text beside it for one, two and three accounts, how it shortens, how
/// an outdated figure is marked without an underline, and that the reload interval follows the
/// capture.
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

    private func english(_ entries: [PinnedAccountEntry]) -> PinnedAccountText.InlineRow? {
        PinnedAccountText.inlineRow(entries, texts: EnglishPinnedAccountTexts())
    }

    private func german(_ entries: [PinnedAccountEntry]) -> PinnedAccountText.InlineRow? {
        PinnedAccountText.inlineRow(entries, texts: German())
    }

    func testNothingPinnedHasNoRow() {
        XCTAssertNil(english([]))
    }

    func testOneAccountIsItsGlyphAndItsFullFigureWithSide() {
        XCTAssertEqual(english([entry("claude", remaining: 42)]),
            .init(glyphProviderID: "claude", text: "42% left"))
        XCTAssertEqual(english([entry("claude", remaining: 42, mode: .used)])?.text, "58% used")
        XCTAssertEqual(english([entry("codex", remaining: 0.4)])?.text, "<1% left")
        XCTAssertEqual(english([entry("kimi", remaining: 100)])?.text, "100% left")
        XCTAssertEqual(german([entry("claude", remaining: 42)])?.text, "42 % übrig")
        XCTAssertEqual(german([entry("devin", remaining: 0, severity: .exhausted)])?.text, "Aufgebraucht")
    }

    func testTheGlyphIsTheFirstAccountsAndOthersFollowByName() {
        XCTAssertEqual(english([entry("claude", remaining: 42), entry("codex", remaining: 87)]),
            .init(glyphProviderID: "claude", text: "42 · Codex 87"))
        XCTAssertEqual(english([entry("devin", remaining: 0, severity: .exhausted), entry("codex", remaining: 87)])?.text,
            "Exhausted · Codex 87")
        XCTAssertEqual(german([entry("zai", remaining: 42, validity: .error), entry("codex", remaining: 87)])?.text,
            "Fehler · Codex 87")
        XCTAssertEqual(english([entry("claude", remaining: 42), entry("codex", remaining: 7),
            entry("kimi", remaining: 100)])?.text, "42 · Codex 7 · Kimi 100")
        // A provider this build does not know leads with the fallback disc.
        XCTAssertEqual(english([entry(nil, title: "Mistral Le Chat", remaining: 42), entry("codex", remaining: 5)]),
            .init(glyphProviderID: nil, text: "42 · Codex 5"))
    }

    func testAnOutdatedFigureIsMarkedWithATildeBecauseInlineDropsTheUnderline() {
        XCTAssertEqual(english([entry("claude", remaining: 42, validity: .stale)])?.text, "~42% left")
        XCTAssertEqual(english([entry("claude", remaining: 42), entry("codex", remaining: 7, validity: .resetDue),
            entry("kimi", remaining: 100, validity: .stale)])?.text, "42 · Codex ~7 · Kimi ~100")
        XCTAssertEqual(german([entry("claude", remaining: 42, validity: .stale)])?.text, "~42 % übrig")
    }

    func testARowTextLongerThanTwentyEightCharactersUsesTheIconSignsInsteadOfWords() {
        let three = [entry("xai", remaining: 99.6), entry("devin", remaining: 0, severity: .exhausted),
            entry("meta", remaining: nil)]
        // ">99 · Devin Exhausted · Muse Unknown" would be 36 characters.
        XCTAssertEqual(english(three)?.text, ">99 · Devin ⊘ · Muse —")
        XCTAssertEqual(german(three)?.text, ">99 · Devin ⊘ · Muse —")
        // Exactly twenty-eight characters keep their words.
        XCTAssertEqual(english([entry("zai", remaining: 42, validity: .error), entry("devin", remaining: 0,
            severity: .exhausted)])?.text, "Error · Devin Exhausted")
        XCTAssertEqual(english([entry("claude", remaining: 42), entry("meta", remaining: nil),
            entry("kimi", remaining: 100)])?.text, "42 · Muse Unknown · Kimi 100")
    }

    func testNamesAfterTheGlyphAreCutToTheBudgetOfTheirCount() {
        // Two accounts: 10 characters; three: 6. A longer name keeps one character fewer than its
        // budget and ends in an ellipsis, so it is exactly the budget long.
        XCTAssertEqual(english([entry("claude", remaining: 42), entry("antigravity", remaining: 5)])?.text,
            "42 · Antigravi… 5")
        XCTAssertEqual(english([entry("claude", remaining: 42), entry("antigravity", remaining: 5),
            entry("opencode", remaining: 7)])?.text, "42 · Antig… 5 · OpenC… 7")
        // A provider this build does not know is named by the first word of the account title.
        XCTAssertEqual(english([entry("claude", remaining: 42), entry(nil, title: "Mistral Le Chat", remaining: 5)])?.text,
            "42 · Mistral 5")
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
