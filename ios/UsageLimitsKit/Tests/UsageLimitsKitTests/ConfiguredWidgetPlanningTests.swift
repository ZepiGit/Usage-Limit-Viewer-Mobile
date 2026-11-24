import XCTest
@testable import UsageLimitsKit

/// Configured providers capture inputs once for every timeline entry and retain
/// the empty-selection reason resolved from those inputs.
final class ConfiguredWidgetPlanningTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func account(_ id: String, used: Double, fetchedAt: Date? = nil) -> AccountUsage {
        let when = fetchedAt ?? now
        return AccountUsage(
            account: ProviderAccount(id: id, provider: .codex, externalAccountID: id,
                email: "\(id)@example.com", displayName: id, plan: "Plus", credentialReference: id,
                createdAt: now, lastSuccessfulSync: when),
            snapshot: UsageSnapshot(accountID: id, fetchedAt: when, status: .ok,
                windows: [UsageWindow(id: "short", label: "5h limit", category: .fiveHour,
                    usedPercent: used, periodSeconds: 18_000, resetAt: nil, exhausted: used >= 100)]))
    }

    private func loadedSnapshot() -> GlanceSnapshot {
        GlanceModel.build([account("a", used: 10), account("b", used: 50)], now: now, scope: .allAccounts)
    }

    private func capture(_ source: GlanceSnapshotCodec.Load,
        preset: ConfiguredWidgetCapture.PresetResolution = .notRead,
        scope: GlanceScope = .allAccounts, accountID: String? = nil) -> ConfiguredWidgetCapture {
        ConfiguredWidgetCapture(source: source, preset: preset, scope: scope,
            accountID: accountID, providerID: nil, transparent: false)
    }

    private func loadedSnapshot(percentDisplay: PercentDisplay) -> GlanceSnapshot {
        GlanceModel.build([account("a", used: 10), account("b", used: 50)], now: now,
            scope: .allAccounts, percentDisplay: percentDisplay)
    }

    // MARK: one capture drives many entries

    func testEntriesDeriveFromTheOneCapturedGeneration() {
        let request = capture(.loaded(loadedSnapshot()))
        let entries = stride(from: 0, through: 2700, by: 900).map {
            request.selectedSnapshot(at: now.addingTimeInterval(TimeInterval($0)))
        }
        XCTAssertEqual(entries.count, 4)
        // Same capture, same result at every date inside the freshness window — no entry can
        // carry a generation the request did not capture.
        XCTAssertTrue(entries.allSatisfy { $0 == entries[0] })
        XCTAssertEqual(entries[0].accounts.map(\.id), ["b", "a"])
    }

    func testResetEntryReordersAllAccountsWithoutMovingFirstPin() throws {
        let boundary = now.addingTimeInterval(90.125)
        let justBefore = now.addingTimeInterval(90)
        let crossing = account("b", used: 99)
        let windows = [
            UsageWindow(id: "short", label: "5h", category: .fiveHour, usedPercent: 99,
                periodSeconds: 18_000, resetAt: boundary, exhausted: false),
            UsageWindow(id: "weekly", label: "Weekly", category: .weekly, usedPercent: 5,
                periodSeconds: 604_800, resetAt: nil, exhausted: false)
        ]
        let storedFirst = account("a", used: 45)
        let second = AccountUsage(account: crossing.account, snapshot: UsageSnapshot(
            accountID: "b", fetchedAt: now, status: .ok, windows: windows))
        let export = GlanceModel.build([storedFirst, second], now: now, scope: .allAccounts,
            statusIconsEnabled: true, pinnedAccountIDs: ["b", "a"])
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try GlanceSnapshotCodec.write(export, toDirectory: directory)
        let source = GlanceSnapshotCodec.load(fromDirectory: directory)
        let request = capture(source)
        let resetEntry = try XCTUnwrap(request.presentationBoundaries(after: now).first)
        XCTAssertEqual(resetEntry, boundary)
        XCTAssertEqual(request.selectedSnapshot(at: justBefore).accounts.map(\.id), ["b", "a"])
        XCTAssertEqual(request.selectedSnapshot(at: resetEntry).accounts.map(\.id), ["a", "b"])
        XCTAssertEqual(export.overviewOrderedAccounts(at: justBefore).map(\.id), ["b", "a"])
        XCTAssertEqual(export.overviewOrderedAccounts(at: resetEntry).map(\.id), ["a", "b"])
        for date in [now, resetEntry] {
            let pins = PinnedAccountsCapture.capture(source: source, at: date).accounts
            XCTAssertEqual(pins.first?.id, "a") // The circular accessory also takes this entry.
            XCTAssertEqual(PinnedAccountText.inlineRow(pins, texts: EnglishPinnedAccountTexts())?.text, "55% left")
        }
    }

    func testNonCustomRequestNeverResolvesAPreset() {
        // ACCOUNT selection is exact, and a request that never consulted the preset store
        // cannot be poisoned by one.
        let request = capture(.loaded(loadedSnapshot()), scope: .account, accountID: "b")
        XCTAssertEqual(request.outcome(at: now), .ready)
        XCTAssertEqual(request.selectedSnapshot(at: now).accounts.map(\.id), ["b"])
    }

    // MARK: an empty selection keeps its reason

    func testMissingPresetIsNotNoAccounts() {
        // The preset file failed to resolve while other accounts exist. The old behaviour
        // reported "No accounts yet" for this — a sentence that sent the user to add
        // accounts they already have.
        let request = capture(.loaded(loadedSnapshot()), preset: .missing, scope: .custom)
        XCTAssertEqual(request.outcome(at: now), .missingPreset)
    }

    func testMissingAccountIsNotNoAccounts() {
        // A preset whose accounts all vanished, and a direct account selection that no
        // longer resolves, both mean "edit this widget" — not "no accounts yet".
        let vanished = capture(.loaded(loadedSnapshot()), preset: .matched(accountIDs: ["ghost"]), scope: .custom)
        XCTAssertEqual(vanished.outcome(at: now), .missingAccount)
        let direct = capture(.loaded(loadedSnapshot()), scope: .account, accountID: "ghost")
        XCTAssertEqual(direct.outcome(at: now), .missingAccount)
    }

    func testSourceFailureStatesKeepTheirReasons() {
        // A missing snapshot means no publication yet, independently of device-lock state.
        XCTAssertEqual(capture(.missing).outcome(at: now), .noAccounts)
        XCTAssertEqual(capture(.empty).outcome(at: now), .noAccounts)
        // An existing file that cannot be opened is the protected/unreadable case.
        XCTAssertEqual(capture(.unreadable).outcome(at: now), .sourceUnavailable)
        XCTAssertEqual(capture(.corrupt).outcome(at: now), .corruptSource)
    }

    // MARK: every widget follows the published app display mode

    func testPublishedAppModeReachesEveryConfiguredSelectionAndTimelineEntry() {
        let selections: [(GlanceScope, String?, String?, ConfiguredWidgetCapture.PresetResolution)] = [
            (.allAccounts, nil, nil, .notRead),
            (.account, "b", nil, .notRead),
            (.account, "ghost", nil, .notRead),
            (.provider, nil, "codex", .notRead),
            (.provider, nil, "claude", .notRead),
            (.custom, nil, nil, .matched(accountIDs: ["b", "a"])),
            (.custom, nil, nil, .matched(accountIDs: ["ghost"])),
            (.custom, nil, nil, .missing),
            (.closestResets, nil, nil, .notRead),
            (.mostCritical, nil, nil, .notRead)
        ]
        for appMode in [PercentDisplay.left, .used] {
            for (scope, accountID, providerID, preset) in selections {
                let request = ConfiguredWidgetCapture(
                    source: .loaded(loadedSnapshot(percentDisplay: appMode)), preset: preset,
                    scope: scope, accountID: accountID, providerID: providerID,
                    transparent: false)
                for offset in [0.0, 900, 7200] {
                    let selected = request.selectedSnapshot(at: now.addingTimeInterval(offset))
                    XCTAssertEqual(selected.percentDisplay, appMode,
                        "\(scope), account \(accountID ?? "none"), provider \(providerID ?? "none"), "
                            + "offset \(offset)")
                }
            }
        }
    }

    func testPublishedModeChangesNeitherSelectedReadingsNorTheirReset() {
        let scopes: [(GlanceScope, String?, [String])] = [
            (.allAccounts, nil, ["b", "a"]), (.account, "b", ["b"]), (.closestResets, nil, ["a", "b"])
        ]
        for (scope, accountID, expectedIDs) in scopes {
            let left = capture(.loaded(loadedSnapshot(percentDisplay: .left)), scope: scope,
                accountID: accountID).selectedSnapshot(at: now)
            let used = capture(.loaded(loadedSnapshot(percentDisplay: .used)), scope: scope,
                accountID: accountID).selectedSnapshot(at: now)

            XCTAssertEqual(left.percentDisplay, .left)
            XCTAssertEqual(used.percentDisplay, .used)
            XCTAssertEqual(used.accounts.map(\.id), expectedIDs)
            XCTAssertEqual(used.accounts, left.accounts)
            XCTAssertEqual(used.accountCount, left.accountCount)
            XCTAssertEqual(used.updatedAt, left.updatedAt)
            XCTAssertEqual(used.nextResetAt, left.nextResetAt)
            XCTAssertEqual(used.overallSeverity, left.overallSeverity)
            XCTAssertEqual(used.summaryLineShort, left.summaryLineShort)
            XCTAssertEqual(used.summaryLineLong, left.summaryLineLong)
            XCTAssertEqual(used.staleAfter, left.staleAfter)
            XCTAssertEqual(used.credits, left.credits)
        }
    }

    func testBothPublishedModesKeepSelectionOutcomesAndPlannedBoundaries() {
        for appMode in [PercentDisplay.left, .used] {
            let source = GlanceSnapshotCodec.Load.loaded(loadedSnapshot(percentDisplay: appMode))
            let selected = capture(source)
            XCTAssertEqual(selected.outcome(at: now), .ready)
            XCTAssertEqual(selected.presentationBoundaries(after: now), [now.addingTimeInterval(3600)])

            let missing = capture(source, scope: .account, accountID: "ghost")
            XCTAssertEqual(missing.outcome(at: now), .missingAccount)
            XCTAssertTrue(missing.presentationBoundaries(after: now).isEmpty)
        }
    }

    // MARK: age transitions are planned from the captured cache

    func testStalenessBoundaryIsPlannedFromTheCapture() {
        let fetchedEarlier = GlanceModel.build(
            [account("a", used: 10, fetchedAt: now.addingTimeInterval(-40 * 60))], now: now, scope: .allAccounts)
        let request = capture(.loaded(fetchedEarlier))
        let boundaries = request.presentationBoundaries(after: now)
        XCTAssertEqual(boundaries.count, 1)
        XCTAssertEqual(boundaries[0], now.addingTimeInterval(20 * 60))
    }

    func testAlreadyStaleAccountPlansNoBoundary() {
        let longAgo = GlanceModel.build(
            [account("a", used: 10, fetchedAt: now.addingTimeInterval(-5 * 3600))], now: now, scope: .allAccounts)
        XCTAssertTrue(capture(.loaded(longAgo)).presentationBoundaries(after: now).isEmpty)
    }

    // MARK: a reading that predates its own reset

    func testResetPendingDistinguishesPreResetReadingsFromFreshOnes() {
        let row = GlanceRow(label: "5h limit", category: .fiveHour, remainingPercent: 0,
            resetAt: now, severity: .exhausted)

        // The snapshot predates the reset: the reading is invalidated, pending fresh data.
        XCTAssertTrue(row.resetPending(at: now, fetchedAt: now.addingTimeInterval(-100)))
        // A post-reset fetch confirmed the state: nothing pending.
        XCTAssertFalse(row.resetPending(at: now, fetchedAt: now.addingTimeInterval(100)))
        // No fetch instant known: a passed reset on a cached reading can only be pending.
        XCTAssertTrue(row.resetPending(at: now, fetchedAt: nil))
        // Future reset, or no reset at all: never pending.
        XCTAssertFalse(row.resetPending(at: now.addingTimeInterval(-100), fetchedAt: now.addingTimeInterval(-200)))
        let noReset = GlanceRow(label: "5h limit", category: .fiveHour, remainingPercent: 50,
            resetAt: nil, severity: .healthy)
        XCTAssertFalse(noReset.resetPending(at: now, fetchedAt: now.addingTimeInterval(-100)))
    }

    // A reading whose own reset has passed is drawn in the stale treatment, as Android's
    // `WidgetRow.validity` does — never in the quota colour that vouches for the spent window.
    func testResetPendingRowIsShownStaleNotInItsQuotaColour() {
        let pending = GlanceRow(label: "5h limit", category: .fiveHour, remainingPercent: 0,
            resetAt: now.addingTimeInterval(-60), severity: .exhausted)
        let future = GlanceRow(label: "5h limit", category: .fiveHour, remainingPercent: 0,
            resetAt: now.addingTimeInterval(600), severity: .exhausted)
        let account = GlanceAccount(id: "a", title: "Codex", subtitle: nil, rows: [pending],
            severity: .exhausted, fetchedAt: now.addingTimeInterval(-600))

        XCTAssertEqual(account.displaySeverity(of: pending, at: now, staleAfter: 3600), .stale)
        XCTAssertEqual(account.displaySeverity(of: future, at: now, staleAfter: 3600), .exhausted)
        // Invalid data outranks everything; an aged account stays stale.
        let failed = GlanceAccount(id: "a", title: "Codex", subtitle: nil, rows: [pending],
            severity: .error, fetchedAt: now.addingTimeInterval(-600))
        XCTAssertEqual(failed.displaySeverity(of: pending, at: now, staleAfter: 3600), .error)
        XCTAssertEqual(account.displaySeverity(of: future, at: now, staleAfter: 60), .stale)
        // A healthy account still speaks with the shown row's own severity.
        let lowRow = GlanceRow(label: "Weekly", category: .weekly, remainingPercent: 15, resetAt: nil, severity: .low)
        let healthy = GlanceAccount(id: "h", title: "Codex", subtitle: nil, rows: [lowRow],
            severity: .healthy, fetchedAt: now)
        XCTAssertEqual(healthy.displaySeverity(of: lowRow, at: now, staleAfter: 3600), .low)
        // Each row keeps its own quota severity: a blocked weekly window does not recolour 5h.
        let fiveHour = GlanceRow(label: "5h limit", category: .fiveHour, remainingPercent: 80, resetAt: nil, severity: .healthy)
        let weekly = GlanceRow(label: "Weekly", category: .weekly, remainingPercent: 0, resetAt: nil, severity: .exhausted)
        let mixed = GlanceAccount(id: "m", title: "Codex", subtitle: nil, rows: [fiveHour, weekly],
            severity: .exhausted, fetchedAt: now)
        XCTAssertEqual(mixed.displaySeverity(of: fiveHour, at: now, staleAfter: 3600), .healthy)
        XCTAssertEqual(mixed.displaySeverity(of: weekly, at: now, staleAfter: 3600), .exhausted)
    }

    // A shown row's reset is a presentation boundary wherever it falls, not only the lead's
    // and not only inside the first hour: without an entry there, a throttled timeline keeps
    // showing the spent window's number as current, with "Resets 14:00" after 14:00.
    func testEveryShownRowResetIsAPresentationBoundary() {
        func usage(_ id: String, reset: Date) -> AccountUsage {
            AccountUsage(
                account: ProviderAccount(id: id, provider: .codex, externalAccountID: id,
                    email: "\(id)@example.com", displayName: id, plan: "Plus", credentialReference: id,
                    createdAt: now, lastSuccessfulSync: now),
                snapshot: UsageSnapshot(accountID: id, fetchedAt: now, status: .ok,
                    windows: [UsageWindow(id: "short", label: "5h limit", category: .fiveHour,
                        usedPercent: 90, periodSeconds: 18_000, resetAt: reset, exhausted: false)]))
        }
        let leadReset = now.addingTimeInterval(3 * 3600)
        let otherReset = now.addingTimeInterval(20 * 60)
        let snapshot = GlanceModel.build([usage("lead", reset: leadReset), usage("other", reset: otherReset)],
            now: now, scope: .allAccounts)

        let boundaries = Set(capture(.loaded(snapshot)).presentationBoundaries(after: now))
        XCTAssertTrue(boundaries.contains(leadReset))
        XCTAssertTrue(boundaries.contains(otherReset))
        XCTAssertTrue(boundaries.contains(now.addingTimeInterval(3600)))   // staleness still planned
        XCTAssertEqual(Set(snapshot.presentationBoundaries(after: now)), boundaries)
    }
}
