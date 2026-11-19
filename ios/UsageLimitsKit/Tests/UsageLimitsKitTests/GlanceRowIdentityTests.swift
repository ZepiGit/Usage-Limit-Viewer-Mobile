import Foundation
import UsageLimitsKit
import XCTest

/// A row's identity is the window it shows. The overview keys its rows on it, so a figure or a
/// bar animates only between two readings of one window.
///
/// Every value is synthetic.
final class GlanceRowIdentityTests: XCTestCase {

    private func row(
        _ label: String, _ category: WindowCategory, remaining: Double? = 50, scope: UsageScope = .global
    ) -> GlanceRow {
        GlanceRow(
            label: label, category: category, remainingPercent: remaining,
            resetAt: nil, severity: .healthy, scope: scope)
    }

    private func ids(_ rows: [GlanceRow]) -> [IdentifiedGlanceRow.ID] {
        GlanceAccount(id: "account", title: "Account", subtitle: nil, rows: rows, severity: .healthy)
            .identifiedRows.map(\.id)
    }

    func testIdsAreUniqueWhenRowsShareHorizonScopeAndName() {
        let rows = [
            row("Weekly", .weekly, remaining: 80),
            row("5-hour", .fiveHour),
            row("Weekly", .weekly, remaining: 20),
            row("Weekly", .weekly, remaining: 80),
        ]

        let identities = ids(rows)

        XCTAssertEqual(identities.count, rows.count)
        XCTAssertEqual(Set(identities).count, rows.count, "two rows share an id: \(identities)")
        // The twins are told apart by the order they came in, so each keeps its id as long as
        // that order holds.
        XCTAssertEqual(identities.map(\.occurrence), [0, 0, 1, 2])
    }

    func testARowKeepsItsIdWhenItsReadingChanges() {
        let before = [row("5-hour", .fiveHour, remaining: 60), row("Weekly", .weekly, remaining: 10)]
        let after = [
            GlanceRow(
                label: "5-hour", category: .fiveHour, remainingPercent: 0,
                resetAt: Date(timeIntervalSince1970: 1_757_000_000), severity: .exhausted),
            row("Weekly", .weekly, remaining: nil),
        ]

        XCTAssertEqual(ids(before), ids(after))
    }

    func testADifferentWindowInTheSameSlotGetsADifferentId() {
        let before = ids([row("5-hour", .fiveHour), row("Weekly", .weekly)])

        // Another horizon, another name and another scope each make it another window, with the
        // same reading in the same place.
        let otherHorizon = ids([row("5-hour", .fiveHour), row("Weekly", .monthly)])
        let otherName = ids([row("5-hour", .fiveHour), row("Weekly (Opus)", .weekly)])
        let otherScope = ids([row("5-hour", .fiveHour), row("Weekly", .weekly, scope: .model)])

        for after in [otherHorizon, otherName, otherScope] {
            XCTAssertEqual(after[0], before[0], "the untouched slot keeps its id")
            XCTAssertNotEqual(after[1], before[1])
        }
    }

    func testEachIdCarriesItsRow() {
        let rows = [row("5-hour", .fiveHour, remaining: 60), row("Weekly", .weekly, remaining: 10)]
        let account = GlanceAccount(
            id: "account", title: "Account", subtitle: nil, rows: rows, severity: .healthy)

        XCTAssertEqual(account.identifiedRows.map(\.row), rows)
    }
}
