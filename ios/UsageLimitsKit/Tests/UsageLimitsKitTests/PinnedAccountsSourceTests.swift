import XCTest
@testable import UsageLimitsKit

final class PinnedAccountsSourceTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    func testUnreadableAndCorruptSourcesDoNotAskThePersonToPinAgain() {
        let unreadable = PinnedAccountsCapture.capture(source: .unreadable, at: now)
        let corrupt = PinnedAccountsCapture.capture(source: .corrupt, at: now)
        XCTAssertEqual(unreadable.outcome, .sourceUnavailable)
        XCTAssertEqual(corrupt.outcome, .corruptSource)
        XCTAssertEqual(unreadable.accounts, [])
        XCTAssertEqual(corrupt.accounts, [])
        for source: GlanceSnapshotCodec.Load in [.empty, .missing, .loaded(.empty)] {
            XCTAssertEqual(PinnedAccountsCapture.capture(source: source, at: now).outcome, .noAccounts)
        }
    }
}
