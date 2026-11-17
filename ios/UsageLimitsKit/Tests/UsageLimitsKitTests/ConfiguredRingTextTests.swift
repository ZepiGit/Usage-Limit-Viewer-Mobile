import XCTest
@testable import UsageLimitsKit

/// What an iOS Account Rings ring writes in its centre and what VoiceOver hears for it (#38),
/// through the same composition the widget calls. English is the kit's default texts; German is
/// a test stand-in in the catalog's wording ("%@ %%", "%@ übrig", "%@ verbraucht"), proving the
/// words come through `PercentTexts` rather than from literals in the kit.
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
            XCTAssertEqual(ConfiguredRingText.centre(p, texts: english), expected, name)
            XCTAssertEqual(ConfiguredRingText.centre(p, texts: german), expected, "\(name), German")
        }
    }

    // MARK: - Centre text

    func testAnExhaustedWindowInUsedWritesABare100InBothLanguages() {
        let p = presentation(0, .exhausted, .current, .used)
        XCTAssertEqual(ConfiguredRingText.centre(p, texts: english), "100")
        XCTAssertEqual(ConfiguredRingText.centre(p, texts: german), "100")
    }

    func testOtherFiguresKeepTheirSignAndTheDashStays() {
        XCTAssertEqual(ConfiguredRingText.centre(presentation(42, .healthy, .current, .left), texts: english), "42%")
        XCTAssertEqual(ConfiguredRingText.centre(presentation(42, .healthy, .current, .left), texts: german), "42 %")
        XCTAssertEqual(ConfiguredRingText.centre(presentation(1, .low, .current, .used), texts: english), "99%")
        XCTAssertEqual(ConfiguredRingText.centre(presentation(nil, .unknown, .current, .left), texts: english), "—")
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

    func testAnExhaustedWindowStillSaysOneHundredPercentUsed() {
        let account = account(remaining: 0, severity: .exhausted)
        let p = presentation(0, .exhausted, .current, .used)
        XCTAssertEqual(ConfiguredRingText.spoken(account, presentation: p, texts: english),
                       "Claude, me@example.invalid, 100% used")
        XCTAssertEqual(ConfiguredRingText.spoken(account, presentation: p, texts: german),
                       "Claude, me@example.invalid, 100 % verbraucht")
    }
}
