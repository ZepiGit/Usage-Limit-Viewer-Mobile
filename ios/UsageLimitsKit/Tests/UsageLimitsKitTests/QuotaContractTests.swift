import XCTest
@testable import UsageLimitsKit

/// Synthetic regressions for the shared quota contract.
final class QuotaContractTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func window(_ id: String, used: Double?, scope: UsageScope = .global) -> UsageWindow {
        UsageWindow(id: id, label: id, category: .weekly, usedPercent: used,
                    periodSeconds: 604_800, resetAt: nil, exhausted: used == 100, scope: scope)
    }

    private func snapshot(_ windows: [UsageWindow]) -> UsageSnapshot {
        UsageSnapshot(accountID: "synthetic", fetchedAt: now, status: .ok, windows: windows)
    }

    func testScopedExhaustionDoesNotExhaustAvailableGlobalQuotas() {
        let value = snapshot([window("5h", used: 0), window("Weekly", used: 63),
                              window("Weekly (Fable)", used: 100, scope: .model)])
        XCTAssertEqual(value.severity, .limited)
        XCTAssertEqual(value.mostCritical?.remainingPercent, 37)
        XCTAssertEqual(value.restrictedWindows.map(\.label), ["Weekly (Fable)"])
    }

    func testExhaustedGlobalQuotaBlocksDespiteHealthyWeeklyQuota() {
        XCTAssertEqual(snapshot([window("5h", used: 100), window("Weekly", used: 20)]).severity, .exhausted)
    }

    func testUnknownOptionalQuotaDoesNotOverrideKnownCreditBalance() {
        let value = snapshot([window("Weekly credits", used: 20), window("Monthly included", used: nil)])
        XCTAssertEqual(value.severity, .healthy)
        XCTAssertEqual(value.mostCritical?.remainingPercent, 80)
        XCTAssertEqual(snapshot([window("Monthly included", used: nil)]).severity, .unknown)
    }

    func testUnknownPoolPreventsClaimThatAllPoolsAreExhausted() {
        XCTAssertEqual(snapshot([window("model", used: 100, scope: .model),
                                 window("unidentified", used: nil, scope: .unknown)]).severity, .limited)
        XCTAssertEqual(snapshot([window("model", used: 100, scope: .model)]).severity, .exhausted)
    }

    func testInvalidPercentagesNeverBecomeFullOrEmpty() {
        for used in [-1.0, 101.0, .nan, .infinity, -.infinity] {
            XCTAssertNil(window("invalid", used: used).remainingPercent)
            XCTAssertEqual(window("invalid", used: used).severity, .unknown)
        }
    }

    func testPercentTextPreservesPositiveRemaindersAndNotQuiteFullQuotas() {
        let examples: [(Double?, String)] = [(nil, "—"), (0, "0%"), (0.1, "<1%"),
            (0.99, "<1%"), (1, "1%"), (37, "37%"), (99.4, "99%"),
            (99.9, ">99%"), (100, "100%"), (101, "—"), (.nan, "—")]
        for (percent, expected) in examples {
            XCTAssertEqual(UsageWindow.percentLabel(percent), expected)
        }
    }

    func testLegacyWindowCacheDecodesWithConservativeClaudeScope() throws {
        let encoded = Data(#"{"id":"seven-day-fable","label":"Weekly (Fable)","category":"weekly","usedPercent":100,"exhausted":true}"#.utf8)
        let value = try JSONDecoder().decode(UsageWindow.self, from: encoded)
        XCTAssertEqual(value.scope, .model)
    }

    func testSeverityRawValuesStayCompatibleWithExistingCaches() throws {
        for (raw, severity) in [(0, Severity.healthy), (1, .medium), (2, .low),
                                (3, .exhausted), (4, .stale), (5, .error)] {
            XCTAssertEqual(try JSONDecoder().decode(Severity.self, from: Data("\(raw)".utf8)), severity)
        }
    }
}

extension QuotaContractTests {
    func testClaudeKeepsZeroGlobalWindowsAndDistinctWindowsSharingReset() throws {
        let payload = try JSONSerialization.jsonObject(with: Data(#"{"five_hour":{"utilization":0},"seven_day":{"utilization":63,"resets_at":"2027-01-16T00:00:00Z"},"limits":[{"kind":"weekly_scoped","percent":100,"resets_at":"2027-01-16T00:00:00Z","scope":{"model":{"id":"fable","display_name":"Fable"}}}]}"#.utf8)) as! [String: Any]
        let windows = ClaudeUsageParser.parse(payload, now: now)
        XCTAssertEqual(Set(windows.map(\.id)), ["five-hour", "seven-day", "seven-day-fable"])
        XCTAssertEqual(windows.first { $0.id == "five-hour" }?.remainingPercent, 100)
        XCTAssertEqual(windows.first { $0.id == "seven-day-fable" }?.scope, .model)
        XCTAssertEqual(snapshot(windows).severity, .limited)
    }

    func testClaudeScopeAndIDsSurviveReorderedCollidingModelNames() throws {
        let entries: [[String: Any]] = ["Sonnet 4.5", "sonnet-4.5"].enumerated().map { index, name in
            ["kind": "weekly_scoped", "percent": index * 10, "scope": ["model": ["display_name": name]]]
        }
        let first = ClaudeUsageParser.parse(["limits": entries], now: now)
        let reversed = ClaudeUsageParser.parse(["limits": Array(entries.reversed())], now: now)
        XCTAssertEqual(Dictionary(uniqueKeysWithValues: first.map { ($0.label, $0.id) }),
                       Dictionary(uniqueKeysWithValues: reversed.map { ($0.label, $0.id) }))
    }
}

extension QuotaContractTests {
    func testOtherProvidersDoNotClampMalformedValuesIntoValidQuotas() {
        let kimi = KimiUsageParser.parse(["usage": ["limit": 100, "used": -1]])
        XCTAssertEqual(kimi.count, 1)
        XCTAssertNil(kimi.first?.remainingPercent)
        let meta = MetaUsageParser.parse(["subs_usage": ["window": ["used_percent": -1]]])
        XCTAssertNil(meta.first { $0.id == "meta-window" }?.remainingPercent)
    }

    func testCodexBlockDoesNotInventMissingPercentage() {
        let windows = CodexUsageParser.parse(["rate_limit": ["limit_reached": true,
            "primary_window": ["limit_window_seconds": 18_000]]], now: now)
        XCTAssertTrue(windows.first?.exhausted == true)
        XCTAssertNil(windows.first?.remainingPercent)
    }

    func testConflictingBlockAndPositiveQuotaArePreservedAndExplained() {
        let blocked = UsageWindow(id: "block", label: "5h limit", category: .fiveHour,
            usedPercent: 20, periodSeconds: 18_000, resetAt: nil, exhausted: true)
        XCTAssertEqual(blocked.remainingPercent, 80)
        XCTAssertTrue(blocked.hasConflictingAvailability)
        XCTAssertEqual(snapshot([blocked]).severity, .exhausted)
        XCTAssertTrue(snapshot([blocked]).statusMessage?.contains("despite remaining quota") == true)
    }
}

extension QuotaContractTests {
    func testLegacySnapshotDefaultsAttemptToMeasurementTime() throws {
        let original = snapshot([window("weekly", used: 20)])
        var json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(original)) as! [String: Any]
        json.removeValue(forKey: "lastAttemptAt")
        let decoded = try JSONDecoder().decode(UsageSnapshot.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertEqual(decoded.fetchedAt, now)
        XCTAssertEqual(decoded.lastAttemptAt, now)
    }

    func testCodexGlobalBlockDoesNotExhaustEveryIndependentWindow() {
        let windows = CodexUsageParser.parse(["rate_limit": ["limit_reached": true,
            "primary_window": ["used_percent": 100, "limit_window_seconds": 18_000],
            "secondary_window": ["used_percent": 20, "limit_window_seconds": 604_800]]], now: now)
        XCTAssertTrue(windows.first { $0.category == .fiveHour }?.exhausted == true)
        XCTAssertFalse(windows.first { $0.category == .weekly }?.exhausted ?? true)
        XCTAssertEqual(windows.first { $0.category == .weekly }?.remainingPercent, 80)
    }
}

extension QuotaContractTests {
    func testEachIndependentModelPoolNeedsABindingExhaustedWindow() {
        func grouped(_ id: String, _ used: Double, _ group: String) -> UsageWindow {
            UsageWindow(id: id, label: id, category: .weekly, usedPercent: used,
                        periodSeconds: 604_800, resetAt: nil, exhausted: used == 100,
                        group: group, scope: .model)
        }
        let blockedPool = [grouped("a-5h", 100, "a"), grouped("a-weekly", 20, "a")]
        XCTAssertEqual(snapshot(blockedPool).severity, .exhausted)
        XCTAssertEqual(snapshot(blockedPool + [grouped("b", 20, "b")]).severity, .limited)
    }

    func testDistinctClaudeModelIDsDoNotMergeBecauseDisplayNamesMatch() {
        let entries: [[String: Any]] = [
            ["kind": "weekly_scoped", "percent": 100, "scope": ["model": ["id": "first", "display_name": "Shared name"]]],
            ["kind": "weekly_scoped", "percent": 20, "scope": ["model": ["id": "second", "display_name": "Shared name"]]]]
        let windows = ClaudeUsageParser.parse(["limits": entries], now: now)
        XCTAssertEqual(windows.count, 2)
        XCTAssertEqual(snapshot(windows).severity, .limited)
    }
}

extension QuotaContractTests {
    func testClaudeUsesMeasuredTwinWhenPrimaryMeasurementIsUnknown() {
        let windows = ClaudeUsageParser.parse([
            "limits": [["kind": "session", "percent": NSNull()]],
            "five_hour": ["utilization": 20, "resets_at": "2027-01-16T00:00:00Z"]], now: now)
        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows.first?.remainingPercent, 80)
        XCTAssertNotNil(windows.first?.resetAt)
    }

    func testUnidentifiedClaudeLimitsDoNotCollapseAndRemainStableOnReorder() {
        let limits = [["percent": 20], ["percent": 80]]
        let first = ClaudeUsageParser.parse(["limits": limits], now: now)
        let reversed = ClaudeUsageParser.parse(["limits": Array(limits.reversed())], now: now)
        XCTAssertEqual(first.count, 2)
        XCTAssertEqual(first.sorted { $0.id < $1.id }, reversed.sorted { $0.id < $1.id })
    }
}

extension QuotaContractTests {
    func testLegacyClaudeCodenameCannotExhaustHealthyGlobalsOnUpgrade() throws {
        let encoded = Data(#"{"id":"iguana-necktie","label":"Weekly (Fable)","category":"weekly","usedPercent":100,"exhausted":true}"#.utf8)
        let codename = try JSONDecoder().decode(UsageWindow.self, from: encoded)
        XCTAssertEqual(codename.scope, .unknown)
        XCTAssertEqual(snapshot([window("five-hour", used: 0), window("seven-day", used: 63), codename]).severity, .limited)
    }
}

extension QuotaContractTests {
    func testLegacyXaiCacheDoesNotReintroduceDeprecatedMonthlyBlock() throws {
        let oldSnapshot = snapshot([window("xai-credits", used: 20), window("xai-monthly", used: 100)])
        let migrated = try JSONDecoder().decode(UsageSnapshot.self, from: JSONEncoder().encode(oldSnapshot))
        XCTAssertEqual(migrated.windows.map(\.id), ["xai-credits"])
        XCTAssertEqual(migrated.severity, .healthy)
        let onDemandData = Data(#"{"id":"xai-on-demand","label":"On-demand","category":"monthly","usedPercent":100,"exhausted":true}"#.utf8)
        XCTAssertEqual(try JSONDecoder().decode(UsageWindow.self, from: onDemandData).scope, .model)
    }

    func testLegacyXaiCacheKeepsAvailableOnDemandAsAnAlternativeToSpentMonthlyQuota() throws {
        let migrated = try decodeXaiCache()
        XCTAssertEqual(migrated.windows.map(\.scope), [.model, .model])
        XCTAssertEqual(migrated.windows.map(\.remainingPercent), [0, 100])
        XCTAssertEqual(migrated.severity, .limited)
    }

    func testLegacyXaiCacheWithoutOnDemandRetainsGlobalMonthlyBlock() throws {
        let migrated = try decodeXaiCache(includeOnDemand: false)
        XCTAssertEqual(migrated.windows.first?.scope, .global)
        XCTAssertEqual(migrated.severity, .exhausted)
    }

    func testLegacyXaiCachePreservesExplicitMonthlyScopes() throws {
        for scope in [UsageScope.global, .model, .unknown] {
            let migrated = try decodeXaiCache(monthlyScope: scope)
            XCTAssertEqual(migrated.windows.first?.scope, scope)
            XCTAssertEqual(migrated.windows.last?.scope, .model)
        }
    }

    func testScopedOnDemandDoesNotImplyLegacyMonthlyAlternative() throws {
        for scope in [UsageScope.global, .model, .unknown] {
            let migrated = try decodeXaiCache(onDemandScope: scope)
            XCTAssertEqual(migrated.windows.first?.scope, .global)
            XCTAssertEqual(migrated.windows.last?.scope, scope)
            XCTAssertEqual(migrated.severity, .exhausted)
        }
    }

    private func decodeXaiCache(monthlyScope: UsageScope? = nil,
                                onDemandScope: UsageScope? = nil,
                                includeOnDemand: Bool = true) throws -> UsageSnapshot {
        var monthly: [String: Any] = ["id": "xai-monthly", "label": "Monthly included",
            "category": "monthly", "usedPercent": 100, "exhausted": true]
        var onDemand: [String: Any] = ["id": "xai-on-demand", "label": "On-demand",
            "category": "monthly", "usedPercent": 0, "exhausted": false]
        if let monthlyScope { monthly["scope"] = monthlyScope.rawValue }
        if let onDemandScope { onDemand["scope"] = onDemandScope.rawValue }
        let json: [String: Any] = ["accountID": "synthetic", "fetchedAt": now.timeIntervalSinceReferenceDate,
            "status": "ok", "windows": includeOnDemand ? [monthly, onDemand] : [monthly]]
        return try JSONDecoder().decode(UsageSnapshot.self, from: JSONSerialization.data(withJSONObject: json))
    }
}
