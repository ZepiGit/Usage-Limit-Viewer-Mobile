import XCTest
@testable import UsageLimitsKit

/// The percent-display parity vectors, shared verbatim with `DisplayPercentTest.kt` on Android:
/// one table, two platforms, pinned token by token. When a row changes here, change it there in
/// the same commit.
final class PercentDisplayTests: XCTestCase {

    private struct Vector {
        let remaining: Double?
        let exhausted: Bool
        let left: String
        let used: String
    }

    private let vectors: [Vector] = [
        Vector(remaining: 0.0, exhausted: false, left: "0", used: "100"),
        Vector(remaining: 0.4, exhausted: false, left: "<1", used: ">99"),
        Vector(remaining: 0.5, exhausted: false, left: "<1", used: ">99"),
        Vector(remaining: 1.0, exhausted: false, left: "1", used: "99"),
        Vector(remaining: 1.5, exhausted: false, left: "2", used: "99"),
        Vector(remaining: 25.0, exhausted: false, left: "25", used: "75"),
        Vector(remaining: 49.5, exhausted: false, left: "50", used: "51"),
        Vector(remaining: 50.0, exhausted: false, left: "50", used: "50"),
        Vector(remaining: 73.0, exhausted: false, left: "73", used: "27"),
        Vector(remaining: 99.0, exhausted: false, left: "99", used: "1"),
        Vector(remaining: 99.4, exhausted: false, left: "99", used: "<1"),
        Vector(remaining: 99.5, exhausted: false, left: ">99", used: "<1"),
        Vector(remaining: 99.6, exhausted: false, left: ">99", used: "<1"),
        Vector(remaining: 100.0, exhausted: false, left: "100", used: "0"),
        Vector(remaining: 0.001, exhausted: false, left: "<1", used: ">99"),
        Vector(remaining: -0.0, exhausted: false, left: "0", used: "100"),
        Vector(remaining: nil, exhausted: false, left: "—", used: "—"),
        Vector(remaining: nil, exhausted: true, left: "—", used: "100"),
        Vector(remaining: Double.nan, exhausted: false, left: "—", used: "—"),
        Vector(remaining: Double.nan, exhausted: true, left: "—", used: "100"),
        Vector(remaining: -1.0, exhausted: false, left: "—", used: "—"),
        Vector(remaining: 101.0, exhausted: false, left: "—", used: "—"),
        Vector(remaining: 101.0, exhausted: true, left: "—", used: "100"),
        Vector(remaining: Double.infinity, exhausted: false, left: "—", used: "—"),
        Vector(remaining: 15.0, exhausted: true, left: "15", used: "100"),
        Vector(remaining: 0.0, exhausted: true, left: "0", used: "100"),
    ]

    func testParityTableHoldsForBothModes() {
        for v in vectors {
            XCTAssertEqual(
                v.left,
                UsageWindow.percentToken(v.remaining, exhausted: v.exhausted, mode: .left),
                "LEFT token for remaining=\(String(describing: v.remaining)), exhausted=\(v.exhausted)")
            XCTAssertEqual(
                v.used,
                UsageWindow.percentToken(v.remaining, exhausted: v.exhausted, mode: .used),
                "USED token for remaining=\(String(describing: v.remaining)), exhausted=\(v.exhausted)")
        }
    }

    func testPercentLabelWrapsTheLeftTokenUnchanged() {
        XCTAssertEqual("73%", UsageWindow.percentLabel(73.0))
        XCTAssertEqual("<1%", UsageWindow.percentLabel(0.4))
        XCTAssertEqual(">99%", UsageWindow.percentLabel(99.5))
        XCTAssertEqual("—", UsageWindow.percentLabel(nil))
    }

    func testDisplayPercentDerivesUsedFromRemainingAndKeepsNilNil() {
        XCTAssertEqual(73.0, try XCTUnwrap(UsageWindow.displayPercent(73.0, .left)))
        XCTAssertEqual(27.0, try XCTUnwrap(UsageWindow.displayPercent(73.0, .used)))
        XCTAssertNil(UsageWindow.displayPercent(nil, .used))
        XCTAssertNil(UsageWindow.displayPercent(nil, .left))
    }

    func testFillFractionFillsExhaustedWindowsCompletelyOnlyInUsedMode() {
        XCTAssertEqual(1.0, UsageWindow.fillFraction(15.0, exhausted: true, mode: .used))
        XCTAssertEqual(0.15, UsageWindow.fillFraction(15.0, exhausted: true, mode: .left)!, accuracy: 1e-9)
        XCTAssertNil(UsageWindow.fillFraction(nil, exhausted: false, mode: .used))
        XCTAssertNil(UsageWindow.fillFraction(nil, exhausted: true, mode: .left))
        XCTAssertEqual(0.27, UsageWindow.fillFraction(73.0, exhausted: false, mode: .used)!, accuracy: 1e-9)
    }

    func testFillFractionForAWindowReadsTheStoredExhaustedFlag() {
        // Exhausted with a positive remaining: the conflict case the provider itself reports.
        let conflict = UsageWindow(
            id: "w", label: "Weekly", category: .weekly, usedPercent: 85.0,
            periodSeconds: nil, resetAt: nil, exhausted: true)
        XCTAssertEqual(1.0, try XCTUnwrap(conflict.fillFraction(.used)))
        XCTAssertEqual(0.15, try XCTUnwrap(conflict.fillFraction(.left)), accuracy: 1e-9)
    }

    func testAppSettingsDecodesPercentDisplayLeniently() throws {
        // A stored choice reads back; an unknown name and a missing key read as .left without
        // failing the decode, which would reset every other preference.
        let used = try JSONDecoder().decode(
            AppSettings.self, from: Data(#"{"percentDisplay":"used"}"#.utf8))
        XCTAssertEqual(.used, used.percentDisplay)
        let unknown = try JSONDecoder().decode(
            AppSettings.self, from: Data(#"{"percentDisplay":"sideways"}"#.utf8))
        XCTAssertEqual(.left, unknown.percentDisplay)
        let missing = try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8))
        XCTAssertEqual(.left, missing.percentDisplay)
    }

    func testResolvedOverridePrefersTheWidgetChoiceButFallsBackToTheAppMode() {
        XCTAssertEqual(.used, PercentDisplay.left.resolved(override: .used))
        XCTAssertEqual(.left, PercentDisplay.used.resolved(override: .left))
        XCTAssertEqual(.left, PercentDisplay.left.resolved(override: nil))
        XCTAssertEqual(.used, PercentDisplay.used.resolved(override: nil))
    }

    /// The same vectors as the Android predicate of the same name.
    func testUsedFillOverridesMissingInkOnlyForAPositiveFractionInUsedMode() {
        for fraction in [nil, 0.0, 0.15, 1.0] as [Double?] {
            XCTAssertFalse(
                PercentDisplay.left.usedFillOverridesMissingInk(fraction),
                "LEFT never overrides, fraction=\(String(describing: fraction))")
        }
        XCTAssertFalse(PercentDisplay.used.usedFillOverridesMissingInk(nil))
        XCTAssertFalse(PercentDisplay.used.usedFillOverridesMissingInk(0.0))
        XCTAssertTrue(PercentDisplay.used.usedFillOverridesMissingInk(0.15))
        XCTAssertTrue(PercentDisplay.used.usedFillOverridesMissingInk(1.0))
    }
}
