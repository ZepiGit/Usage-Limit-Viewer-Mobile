import XCTest
@testable import UsageLimitsKit

final class PinnedAccountsPlanningTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func account(_ id: String = "claude", remaining: Double = 42, reset: TimeInterval = 600) -> GlanceAccount {
        let row = GlanceRow(label: "5h", category: .fiveHour, remainingPercent: remaining,
            resetAt: now.addingTimeInterval(reset), severity: .medium)
        return GlanceAccount(id: id, title: id, subtitle: nil, rows: [row], severity: .medium,
            fetchedAt: now, providerID: "claude")
    }

    private func snapshot(_ accounts: [GlanceAccount], mode: PercentDisplay = .left,
        enabled: Bool = true, pins: [String] = ["claude"]) -> GlanceSnapshot {
        GlanceSnapshot(accounts: accounts, accountCount: accounts.count, updatedAt: now,
            nextResetAt: nil, overallSeverity: .medium, summaryLineShort: nil, summaryLineLong: nil,
            staleAfter: 60, percentDisplay: mode, statusIconsEnabled: enabled, pinnedAccountIDs: pins)
    }

    func testPinnedFigureBecomesOutdatedAtStaleEntryWithoutRepublishing() throws {
        let source = snapshot([account(), account("not-pinned", reset: 30)])
        let dates = PinnedAccountsCapture.entryDates(snapshot: source, after: now)
        XCTAssertEqual(dates, [now, now.addingTimeInterval(60), now.addingTimeInterval(600)])
        let current = try XCTUnwrap(PinnedAccountsCapture.capture(snapshot: source, at: now).accounts.first)
        let stale = try XCTUnwrap(PinnedAccountsCapture.capture(snapshot: source, at: dates[1]).accounts.first)
        XCTAssertEqual(current.content, StatusIconContent(content: .figure(42), outdated: false))
        XCTAssertEqual(stale.content, StatusIconContent(content: .figure(42), outdated: true))
        XCTAssertEqual(stale.presentation.token, "42")
    }

    func testHeadlineChangesAtResetEntryAndFollowsAppMode() throws {
        let short = GlanceRow(label: "5h", category: .fiveHour, remainingPercent: 12,
            resetAt: now.addingTimeInterval(20), severity: .low)
        let weekly = GlanceRow(label: "Weekly", category: .weekly, remainingPercent: 70,
            resetAt: now.addingTimeInterval(600), severity: .healthy)
        let account = GlanceAccount(id: "claude", title: "Claude", subtitle: nil, rows: [short, weekly],
            severity: .low, fetchedAt: now)
        let source = snapshot([account], mode: .used)
        XCTAssertTrue(PinnedAccountsCapture.entryDates(snapshot: source, after: now).contains(now.addingTimeInterval(20)))
        let before = try XCTUnwrap(PinnedAccountsCapture.capture(snapshot: source, at: now).accounts.first)
        let after = try XCTUnwrap(PinnedAccountsCapture.capture(snapshot: source, at: now.addingTimeInterval(20)).accounts.first)
        XCTAssertEqual(before.presentation.token, "88")
        XCTAssertEqual(after.presentation.token, "30")
        XCTAssertEqual(after.content, StatusIconContent(content: .figure(30), outdated: false))
    }

    func testLastResetKeepsItsFigureButMarksItOutdated() throws {
        let source = snapshot([account(reset: 20)])
        let after = try XCTUnwrap(PinnedAccountsCapture.capture(
            snapshot: source, at: now.addingTimeInterval(20)).accounts.first)
        XCTAssertEqual(after.presentation.token, "42")
        XCTAssertEqual(after.content, StatusIconContent(content: .figure(42), outdated: true))
    }

    func testPinsSurviveSnapshotRoundTripAndCopyPaths() throws {
        let source = snapshot([account(), account("other")], mode: .used, pins: ["other", "claude"])
        let decoded = GlanceSnapshotCodec.decode(try GlanceSnapshotCodec.encode(source))
        for copy in [decoded, decoded.with(credits: []), decoded.selecting(scope: .mostCritical, now: now)] {
            XCTAssertTrue(copy.statusIconsEnabled)
            XCTAssertEqual(copy.pinnedAccountIDs, ["other", "claude"])
            XCTAssertEqual(copy.percentDisplay, .used)
        }
        XCTAssertEqual(PinnedAccountsCapture.capture(snapshot: decoded, at: now).accounts.map(\.id), ["claude", "other"])
        XCTAssertTrue(PinnedAccountsCapture.capture(snapshot: snapshot([account()], enabled: false), at: now).accounts.isEmpty)
    }

    func testOldSnapshotKeepsItsAccountsWithPinsOff() throws {
        let source = snapshot([account()], mode: .used)
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: GlanceSnapshotCodec.encode(source)) as? [String: Any])
        json.removeValue(forKey: "statusIconsEnabled")
        json.removeValue(forKey: "pinnedAccountIDs")
        let decoded = GlanceSnapshotCodec.decode(try JSONSerialization.data(withJSONObject: json))
        XCTAssertEqual(decoded.accounts.map(\.id), ["claude"])
        XCTAssertEqual(decoded.percentDisplay, .used)
        XCTAssertFalse(decoded.statusIconsEnabled)
        XCTAssertEqual(decoded.pinnedAccountIDs, [])
    }

    func testUnfetchedAccountIsUnknownAndReconnectionOutranksCachedFigure() throws {
        let unfetched = GlanceAccount(id: "new", title: "New", subtitle: nil, rows: [], severity: .unknown)
        let reconnect = GlanceAccount(id: "claude", title: "Claude", subtitle: nil,
            rows: account().rows, severity: .medium, fetchedAt: now, connectionStatus: .reconnectRequired)
        let source = snapshot([unfetched, reconnect], pins: ["new", "claude"])
        let entries = PinnedAccountsCapture.capture(snapshot: source, at: now).accounts
        XCTAssertEqual(entries.map(\.content.content), [.unknown, .error])
    }

    func testEmptyBuildStillCarriesPinSettings() {
        let source = GlanceModel.build([], now: now, scope: .allAccounts, percentDisplay: .used,
            statusIconsEnabled: true, pinnedAccountIDs: ["gone"])
        XCTAssertTrue(source.statusIconsEnabled)
        XCTAssertEqual(source.pinnedAccountIDs, ["gone"])
        XCTAssertEqual(source.percentDisplay, .left)
    }
}
