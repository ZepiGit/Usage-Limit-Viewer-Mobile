import XCTest
@testable import UsageLimitsKit

final class PinnedAccountTextTests: XCTestCase {
    private struct German: PinnedAccountTexts {
        let error = "Fehler"
        let signInExpired = "Anmeldung abgelaufen"
        let exhausted = "Ausgeschöpft"
        let unknown = "Unbekannt"
        let outdated = "Veraltet"
        func withSign(_ token: String) -> String { "\(token) %" }
        func spoken(_ figure: String, _ mode: PercentDisplay) -> String {
            mode == .used ? "\(figure) verbraucht" : "\(figure) übrig"
        }
        func sideWord(_ mode: PercentDisplay) -> String { mode == .used ? "verbraucht" : "übrig" }
    }

    private func entry(remaining: Double?, mode: PercentDisplay = .left,
        severity: Severity = .healthy, validity: ReadingValidity = .current,
        connectionStatus: ConnectionStatus = .unknown) -> PinnedAccountEntry {
        let p = WindowPresentation(remaining: remaining, severity: severity, validity: validity, mode: mode)
        return PinnedAccountEntry(account: GlanceAccount(id: "claude", title: "Claude", subtitle: nil,
            rows: [], severity: severity, connectionStatus: connectionStatus), presentation: p,
            content: .of(headline: p, validity: validity,
                needsReauthentication: connectionStatus == .reconnectRequired))
    }

    func testSpokenFigureKeepsFullTokensWhenIconShortensThem() {
        XCTAssertEqual(spoken(entry(remaining: 0.4)), "Claude, <1% remaining")
        XCTAssertEqual(spoken(entry(remaining: 99.6)), "Claude, >99% remaining")
        XCTAssertEqual(spoken(entry(remaining: 100)), "Claude, 100% remaining")
        XCTAssertEqual(spoken(entry(remaining: 58, mode: .used)), "Claude, 42% used")
        XCTAssertEqual(spoken(entry(remaining: 42, validity: .stale)), "Claude, 42% remaining, Stale")
    }

    func testGermanCompositionKeepsFullFigureAndNonNumericStates() {
        let texts = German()
        func german(_ entry: PinnedAccountEntry) -> String {
            PinnedAccountText.spoken(entry: entry, texts: texts)
        }
        XCTAssertEqual(german(entry(remaining: 0.4)), "Claude, <1 % übrig")
        XCTAssertEqual(german(entry(remaining: 58, mode: .used)), "Claude, 42 % verbraucht")
        XCTAssertEqual(german(entry(remaining: nil)), "Claude, Unbekannt")
        XCTAssertEqual(german(entry(remaining: 0, severity: .exhausted)), "Claude, Ausgeschöpft")
        XCTAssertEqual(german(entry(remaining: 42, validity: .error)), "Claude, Fehler")
        XCTAssertEqual(german(entry(remaining: 42, validity: .resetDue)), "Claude, 42 % übrig, Veraltet")
    }

    func testCellAndSpokenTextCoverEveryKindInEnglishAndGerman() {
        let cases: [(PinnedAccountEntry, String?, String, String)] = [
            (entry(remaining: 42), "42", "Claude, 42% remaining", "Claude, 42 % übrig"),
            (entry(remaining: 42, validity: .stale), "42", "Claude, 42% remaining, Stale", "Claude, 42 % übrig, Veraltet"),
            (entry(remaining: 100), nil, "Claude, 100% remaining", "Claude, 100 % übrig"),
            (entry(remaining: nil), "—", "Claude, Unknown", "Claude, Unbekannt"),
            (entry(remaining: 0, severity: .exhausted), "⊘", "Claude, Exhausted", "Claude, Ausgeschöpft"),
            (entry(remaining: 42, validity: .error), "!", "Claude, Error", "Claude, Fehler"),
            (entry(remaining: 42, connectionStatus: .reconnectRequired), "!", "Claude, Sign-in expired", "Claude, Anmeldung abgelaufen")
        ]
        for (entry, cell, english, german) in cases {
            XCTAssertEqual(PinnedAccountText.cellText(entry), cell)
            XCTAssertEqual(PinnedAccountText.spoken(entry: entry, texts: EnglishPinnedAccountTexts()), english)
            XCTAssertEqual(PinnedAccountText.spoken(entry: entry, texts: German()), german)
        }
    }

    private func spoken(_ entry: PinnedAccountEntry) -> String {
        PinnedAccountText.spoken(entry: entry, texts: EnglishPinnedAccountTexts())
    }
}
