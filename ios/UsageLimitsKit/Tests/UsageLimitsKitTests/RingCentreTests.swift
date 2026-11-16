import XCTest
@testable import UsageLimitsKit

/// What a ring writes in its centre (#38): a 100 in LEFT is the closed arc and is not written, a
/// 100 in USED is the exhausted window and is written without its sign, everything else as it is.
/// Expected values are literals.
final class RingCentreTests: XCTestCase {

    private func centre(
        _ remaining: Double?, _ severity: Severity, _ validity: ReadingValidity, _ mode: PercentDisplay
    ) -> RingCentre {
        RingCentre.of(WindowPresentation(
            remaining: remaining, severity: severity, validity: validity, mode: mode))
    }

    func testAFullAllowanceInLeftLeavesTheCentreEmpty() {
        XCTAssertEqual(centre(100, .healthy, .current, .left), .empty)
    }

    func testAnExhaustedWindowInUsedIsWrittenWithoutItsSign() {
        XCTAssertEqual(centre(0, .exhausted, .current, .used), .bare("100"))
        // No percentage, but the provider's verdict: still 100 in USED.
        XCTAssertEqual(centre(nil, .exhausted, .current, .used), .bare("100"))
    }

    func testEveryOtherFigureIsWrittenWithItsSign() {
        XCTAssertEqual(centre(0, .exhausted, .current, .left), .figure("0"))
        XCTAssertEqual(centre(100, .healthy, .current, .used), .figure("0"))
        XCTAssertEqual(centre(99, .healthy, .current, .left), .figure("99"))
        XCTAssertEqual(centre(1, .low, .current, .used), .figure("99"))
        XCTAssertEqual(centre(42, .healthy, .current, .left), .figure("42"))
        // Rounds to 100 but is not: the token says ">99", and the ring is not closed.
        XCTAssertEqual(centre(99.6, .healthy, .current, .left), .figure(">99"))
    }

    func testAnUnknownFigureKeepsItsDash() {
        XCTAssertEqual(centre(nil, .unknown, .current, .left), .figure("—"))
        XCTAssertEqual(centre(nil, .unknown, .current, .used), .figure("—"))
    }

    func testAnAgedReadingAtOneHundredFollowsTheSameRuleBecauseItsGreyArcStillCloses() {
        XCTAssertEqual(centre(100, .healthy, .stale, .left), .empty)
        XCTAssertEqual(centre(100, .healthy, .resetDue, .left), .empty)
        XCTAssertEqual(centre(0, .exhausted, .stale, .used), .bare("100"))
        XCTAssertEqual(centre(0, .exhausted, .resetDue, .used), .bare("100"))
    }

    func testAFailedReadingAtAFullAllowanceKeepsItsFigureBecauseNoArcShowsIt() {
        XCTAssertEqual(centre(100, .healthy, .error, .left), .figure("100"))
        XCTAssertEqual(centre(0, .exhausted, .error, .used), .bare("100"))
    }
}
