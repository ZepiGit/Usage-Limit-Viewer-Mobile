import Foundation
import XCTest
import UsageLimitsKit

final class OverviewRoutingTests: XCTestCase {
    func testColdWidgetURLWaitsForCacheAndIsConsumedOnce() throws {
        var router = OverviewRouter()
        let url = try XCTUnwrap(URL(string: "usagelimits://account/target"))
        XCTAssertNil(router.open(url, loadedAccountIDs: nil))
        XCTAssertEqual(router.accountsLoaded(["target"]), .account("target"))
        XCTAssertNil(router.accountsLoaded([]))
    }

    func testColdNotificationWithMissingAccountIsConsumedOnce() {
        var router = OverviewRouter()
        XCTAssertNil(router.notification(accountID: "removed", loadedAccountIDs: nil))
        XCTAssertEqual(router.accountsLoaded([]), .unavailable("removed"))
        XCTAssertNil(router.accountsLoaded(["removed"]))
    }

    func testWarmAccountTargetsUseTheRegisterNotTheVisibleFilter() throws {
        var router = OverviewRouter()
        let url = try XCTUnwrap(URL(string: "usagelimits://account/outside-filter"))
        XCTAssertEqual(router.open(url, loadedAccountIDs: ["inside-filter", "outside-filter"]), .account("outside-filter"))
        XCTAssertEqual(router.notification(accountID: "outside-filter", loadedAccountIDs: ["outside-filter"]),
            .account("outside-filter"))
        XCTAssertEqual(router.open(url, loadedAccountIDs: ["inside-filter"]), .unavailable("outside-filter"))
    }

    func testWholeWidgetLinkReturnsToOverviewAndCancelsPendingJump() throws {
        var router = OverviewRouter()
        XCTAssertNil(router.notification(accountID: "older", loadedAccountIDs: nil))
        XCTAssertEqual(router.open(try XCTUnwrap(URL(string: "usagelimits://glance")), loadedAccountIDs: nil),
            .overview)
        XCTAssertNil(router.accountsLoaded(["older"]))
        XCTAssertEqual(router.open(try XCTUnwrap(URL(string: "usagelimits://refresh")), loadedAccountIDs: []),
            .refreshOverview)
    }

    func testMalformedLinksDoNotReplaceAnEarlierValidTap() throws {
        var router = OverviewRouter()
        XCTAssertNil(router.notification(accountID: "valid", loadedAccountIDs: nil))
        for invalid in ["https://account/other", "usagelimits://account/", "usagelimits://account/a/b", "usagelimits://other"] {
            let url = try XCTUnwrap(URL(string: invalid))
            XCTAssertNil(router.open(url, loadedAccountIDs: nil), invalid)
        }
        XCTAssertNil(router.notification(accountID: "", loadedAccountIDs: nil))
        XCTAssertEqual(router.accountsLoaded(["valid"]), .account("valid"))
    }
}
