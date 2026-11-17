import XCTest
@testable import UsageLimitsKit

final class PinnedAccountTextTests: XCTestCase {
    private struct German: PercentTexts {
        func withSign(_ token: String) -> String { "\(token) %" }
        func spoken(_ figure: String, _ mode: PercentDisplay) -> String {
            mode == .used ? "\(figure) verbraucht" : "\(figure) übrig"
        }
    }

    private func entry(remaining: Double?, mode: PercentDisplay = .left,
        severity: Severity = .healthy, validity: ReadingValidity = .current) -> PinnedAccountEntry {
        let p = WindowPresentation(remaining: remaining, severity: severity, validity: validity, mode: mode)
        return PinnedAccountEntry(account: GlanceAccount(id: "claude", title: "Claude", subtitle: nil,
            rows: [], severity: severity), presentation: p,
            content: .of(headline: p, validity: validity, needsReauthentication: false))
    }

    func testSpokenFigureKeepsFullTokensWhenIconShortensThem() {
        XCTAssertEqual(spoken(entry(remaining: 0.4)), "Claude, <1% remaining")
        XCTAssertEqual(spoken(entry(remaining: 99.6)), "Claude, >99% remaining")
        XCTAssertEqual(spoken(entry(remaining: 100)), "Claude, 100% remaining")
        XCTAssertEqual(spoken(entry(remaining: 58, mode: .used)), "Claude, 42% used")
        XCTAssertEqual(spoken(entry(remaining: 42, validity: .stale)), "Claude, 42% remaining, Outdated")
    }

    func testGermanCompositionKeepsFullFigureAndNonNumericStates() {
        let texts = German()
        func german(_ entry: PinnedAccountEntry) -> String {
            PinnedAccountText.spoken(entry: entry, texts: texts, error: "Fehler", exhausted: "Ausgeschöpft",
                unknown: "Unbekannt", outdated: "Veraltet")
        }
        XCTAssertEqual(german(entry(remaining: 0.4)), "Claude, <1 % übrig")
        XCTAssertEqual(german(entry(remaining: 58, mode: .used)), "Claude, 42 % verbraucht")
        XCTAssertEqual(german(entry(remaining: nil)), "Claude, Unbekannt")
        XCTAssertEqual(german(entry(remaining: 0, severity: .exhausted)), "Claude, Ausgeschöpft")
        XCTAssertEqual(german(entry(remaining: 42, validity: .error)), "Claude, Fehler")
        XCTAssertEqual(german(entry(remaining: 42, validity: .resetDue)), "Claude, 42 % übrig, Veraltet")
    }

    private func spoken(_ entry: PinnedAccountEntry) -> String {
        PinnedAccountText.spoken(entry: entry, texts: EnglishPercentTexts(), error: "Error",
            exhausted: "Exhausted", unknown: "Unknown", outdated: "Outdated")
    }
}
