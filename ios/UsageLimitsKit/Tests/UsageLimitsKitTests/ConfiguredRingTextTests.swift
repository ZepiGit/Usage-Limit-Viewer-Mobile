import XCTest
@testable import UsageLimitsKit

/// What an iOS Account Rings ring writes in its centre and what VoiceOver hears for it (#38),
/// through the same composition the widget calls. The centre is compact in every language;
/// speech uses `PercentTexts` with the catalog's wording, including the German space.
final class ConfiguredRingTextTests: XCTestCase {

    private struct GermanTestPercentTexts: PercentTexts {
        func withSign(_ token: String) -> String { "\(token) %" }
        func spoken(_ figure: String, _ mode: PercentDisplay) -> String {
            mode == .used ? "\(figure) verbraucht" : "\(figure) übrig"
        }
    }

    private let english = EnglishPercentTexts()
    private let german = GermanTestPercentTexts()

    private func presentation(
        _ remaining: Double?, _ severity: Severity, _ validity: ReadingValidity = .current, _ mode: PercentDisplay
    ) -> WindowPresentation {
        WindowPresentation(remaining: remaining, severity: severity, validity: validity, mode: mode)
    }

    private func account(remaining: Double?, severity: Severity) -> GlanceAccount {
        let row = GlanceRow(label: "Weekly", category: .weekly, remainingPercent: remaining, resetAt: nil, severity: severity)
        return GlanceAccount(id: "a", title: "Claude", subtitle: "me@example.invalid", rows: [row], severity: severity)
    }

    // MARK: - Left 100 in every validity

    /// iOS paints the arc only with a fill whose fill state has ink, so the two "no fill" cases
    /// draw a bare track and keep their 100, without the sign.
    func testAFullAllowanceIsEmptyUnderItsArcAndABare100WithoutOne() {
        let cases: [(String, WindowPresentation, String?)] = [
            ("current", presentation(100, .healthy, .current, .left), nil),
            ("exhausted", presentation(100, .exhausted, .current, .left), "100"),
            ("stale", presentation(100, .healthy, .stale, .left), nil),
            ("reset due", presentation(100, .healthy, .resetDue, .left), nil),
            ("error", presentation(100, .healthy, .error, .left), "100"),
        ]
        for (name, p, expected) in cases {
            XCTAssertEqual(ConfiguredRingText.centre(p), expected, name)
        }
    }

    // MARK: - Centre text

    func testAnExhaustedWindowInUsedWritesABare100() {
        let p = presentation(0, .exhausted, .current, .used)
        XCTAssertEqual(ConfiguredRingText.centre(p), "100")
    }

    func testOtherFiguresKeepTheirAttachedSignAndTheDashStays() {
        XCTAssertEqual(ConfiguredRingText.centre(presentation(42, .healthy, .current, .left)), "42%")
        XCTAssertEqual(ConfiguredRingText.centre(presentation(1, .low, .current, .used)), "99%")
        XCTAssertEqual(ConfiguredRingText.centre(presentation(nil, .unknown, .current, .left)), "—")
    }

    // MARK: - Spoken text is the full figure

    func testAFullAllowanceStillSaysOneHundredPercentRemaining() {
        let account = account(remaining: 100, severity: .healthy)
        let p = presentation(100, .healthy, .current, .left)
        XCTAssertEqual(ConfiguredRingText.spoken(account, presentation: p, texts: english),
                       "Claude, me@example.invalid, 100% remaining")
        XCTAssertEqual(ConfiguredRingText.spoken(account, presentation: p, texts: german),
                       "Claude, me@example.invalid, 100 % übrig")
    }

    func testBothSidesSpeakTheDrawnWindowInsteadOfAnotherLowerWindow() {
        let passed = GlanceRow(label: "5h", category: .fiveHour, remainingPercent: 0,
            resetAt: Date(timeIntervalSince1970: 10), severity: .exhausted)
        let shown = GlanceRow(label: "Weekly", category: .weekly, remainingPercent: 60,
            resetAt: Date(timeIntervalSince1970: 200), severity: .healthy)
        let account = GlanceAccount(id: "a", title: "Claude", subtitle: nil,
            rows: [passed, shown], severity: .healthy)
        for mode in [PercentDisplay.left, .used] {
            let p = account.presentation(of: shown, at: Date(timeIntervalSince1970: 100),
                staleAfter: 3600, mode: mode)
            XCTAssertEqual(ConfiguredRingText.spoken(account, presentation: p, texts: english),
                mode == .left ? "Claude, 60% remaining" : "Claude, 40% used")
            XCTAssertEqual(ConfiguredRingText.spoken(account, presentation: p, texts: german),
                mode == .left ? "Claude, 60 % übrig" : "Claude, 40 % verbraucht")
        }
    }

    func testUnknownSpeechDoesNotInventAZeroFigure() {
        let account = account(remaining: nil, severity: .unknown)
        for mode in [PercentDisplay.left, .used] {
            let p = presentation(nil, .unknown, .current, mode)
            XCTAssertTrue(ConfiguredRingText.spoken(account, presentation: p, texts: english).contains("—"))
            XCTAssertFalse(ConfiguredRingText.spoken(account, presentation: p, texts: english).contains("0%"))
        }
    }

    func testAnExhaustedWindowStillSaysOneHundredPercentUsed() {
        let account = account(remaining: 0, severity: .exhausted)
        let p = presentation(0, .exhausted, .current, .used)
        XCTAssertEqual(ConfiguredRingText.spoken(account, presentation: p, texts: english),
                       "Claude, me@example.invalid, 100% used")
        XCTAssertEqual(ConfiguredRingText.spoken(account, presentation: p, texts: german),
                       "Claude, me@example.invalid, 100 % verbraucht")
    }
}
