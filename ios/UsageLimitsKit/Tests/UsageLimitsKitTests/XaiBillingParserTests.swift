import XCTest
@testable import UsageLimitsKit

/// xAI fixtures, ported from the Android suite.
///
/// This parser has the worst history of the four, and the fixtures reflect it: the original
/// returned ZERO windows against a real payload while every hand-written test passed, because
/// the tests were written in the shape the code expected rather than the shape xAI sends.
/// The envelope and cent-object cases below are that bug, pinned.
///
/// Every value is synthetic.
final class XaiBillingParserTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)

    private func payload(_ json: String) -> [String: Any] {
        (try! JSONSerialization.jsonObject(with: json.data(using: .utf8)!)) as! [String: Any]
    }

    /// Percentage of the named window, unwrapped.
    ///
    /// `windows.first { ... }?.usedPercent` is a `Double?` twice over, and the `accuracy:`
    /// overload of `XCTAssertEqual` takes a non-optional. Unwrapping here keeps every
    /// assertion below reading as the single fact it is checking, and a missing window fails
    /// with "expected non-nil" rather than a type error.
    private func percent(_ windows: [UsageWindow], _ id: String) throws -> Double {
        try XCTUnwrap(XCTUnwrap(windows.first { $0.id == id }).usedPercent)
    }

    // MARK: - The config envelope

    func testCreditsWrappedInConfigAreParsed() {
        // Both billing responses wrap their body in `config`. Reading the root yields nil for
        // every field — and since the caller only errors when BOTH endpoints fail, not when
        // both come back empty, the account recorded OK with zero windows, which the model
        // reads as .error. A permanent red pill from two HTTP 200s.
        let windows = XaiBillingParser.parseCredits(payload("""
        {
          "config": {
            "creditUsagePercent": 34.0,
            "currentPeriod": { "type": "weekly",
                               "start": "2026-09-01T00:00:00Z",
                               "end": "2026-09-08T00:00:00Z" }
          }
        }
        """), now: now)

        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].usedPercent, 34.0)
        XCTAssertEqual(windows[0].category, .weekly)
    }

    func testBillingCentsWrappedAsValObjectsAreParsed() throws {
        // Money arrives either bare or as {"val": n}. Reading only the bare form drops the
        // whole billing view.
        let windows = XaiBillingParser.parseBilling(payload("""
        {
          "config": {
            "monthlyLimit": { "val": 10000 },
            "used": { "val": 4200 },
            "onDemandCap": { "val": 5000 },
            "onDemandUsed": { "val": 1000 },
            "billingPeriodEnd": "2026-10-01T00:00:00Z"
          }
        }
        """), now: now)

        XCTAssertEqual(try percent(windows, "xai-monthly"), 42.0, accuracy: 0.001)
        XCTAssertEqual(try percent(windows, "xai-on-demand"), 20.0, accuracy: 0.001)
    }

    func testFlatPayloadWithoutTheEnvelopeStillParses() throws {
        let windows = XaiBillingParser.parseBilling(payload("""
        { "monthlyLimit": 10000, "used": 2500 }
        """), now: now)

        XCTAssertEqual(try percent(windows, "xai-monthly"), 25.0, accuracy: 0.001)
    }

    // MARK: - Not inventing numbers

    func testMissingMonthlyLimitDoesNotInventAnOnDemandFigure() {
        // With the limit defaulting to zero, ALL spend became overage: used=4000 against a
        // 5000 cap rendered "80% of on-demand consumed", a figure the provider never sent.
        let windows = XaiBillingParser.parseBilling(payload("""
        { "config": { "used": 4000, "onDemandCap": 5000 } }
        """), now: now)

        // The included window may appear, but with an UNKNOWN percentage, never a computed one.
        XCTAssertNil(windows.first { $0.id == "xai-monthly" }?.usedPercent ?? nil)
        // The on-demand row is shown — the facility exists, the cap says so — but its figure
        // cannot be derived without a limit to subtract, so it is unknown too. Omitting the
        // row read as "no on-demand spend", which is a claim the provider never made; Android
        // shows the same unknown row.
        XCTAssertNil(windows.first { $0.id == "xai-on-demand" }?.usedPercent ?? nil)
        XCTAssertNotNil(windows.first { $0.id == "xai-on-demand" })
    }

    func testExplicitOnDemandUsedIsHonouredWithoutALimit() throws {
        let windows = XaiBillingParser.parseBilling(payload("""
        { "config": { "used": 4000, "onDemandCap": 5000, "onDemandUsed": 500 } }
        """), now: now)

        XCTAssertEqual(try percent(windows, "xai-on-demand"), 10.0, accuracy: 0.001)
    }

    func testOverLimitSpendClampsTheIncludedWindow() throws {
        // Spend past the allowance is on-demand spend, so the included bar stops at 100 %.
        // Without the clamp an overspending account reads "140 % used".
        let windows = XaiBillingParser.parseBilling(payload("""
        { "config": { "monthlyLimit": 10000, "used": 14000 } }
        """), now: now)

        let monthly = windows.first { $0.id == "xai-monthly" }
        XCTAssertEqual(try percent(windows, "xai-monthly"), 100.0, accuracy: 0.001)
        XCTAssertTrue(monthly?.exhausted ?? false)
    }

    func testZeroMonthlyLimitYieldsNilRatherThanDividingByZero() {
        let windows = XaiBillingParser.parseBilling(payload("""
        { "config": { "monthlyLimit": 0, "used": 500 } }
        """), now: now)

        let monthly = windows.first { $0.id == "xai-monthly" }
        XCTAssertNotNil(monthly)
        XCTAssertNil(monthly?.usedPercent ?? nil)
    }

    func testOnDemandOmittedWhenCapIsZeroOrAbsent() {
        let zeroCap = XaiBillingParser.parseBilling(payload("""
        { "config": { "monthlyLimit": 10000, "used": 500, "onDemandCap": 0 } }
        """), now: now)
        XCTAssertNil(zeroCap.first { $0.id == "xai-on-demand" })

        let noCap = XaiBillingParser.parseBilling(payload("""
        { "config": { "monthlyLimit": 10000, "used": 500 } }
        """), now: now)
        XCTAssertNil(noCap.first { $0.id == "xai-on-demand" })
    }

    // MARK: - Period type

    func testPeriodTypeClassifiesWhenStampsAreAbsent() {
        // A weekly period with no stamps would otherwise classify as .other, and the widget
        // selects its long-horizon headline by .weekly — so xAI's only ready-made percentage
        // would vanish from the home screen.
        let windows = XaiBillingParser.parseCredits(payload("""
        { "config": { "creditUsagePercent": 10.0, "currentPeriod": { "type": "weekly" } } }
        """), now: now)

        XCTAssertEqual(windows[0].category, .weekly)
        XCTAssertEqual(windows[0].periodSeconds, 604_800)
    }

    func testMeasuredSpanWinsOverTheReportedType() {
        // The span is evidence; the type is a label. A fortnight must not read as a week just
        // because the payload says "weekly".
        let windows = XaiBillingParser.parseCredits(payload("""
        {
          "config": {
            "creditUsagePercent": 10.0,
            "currentPeriod": { "type": "weekly",
                               "start": "2026-09-01T00:00:00Z",
                               "end": "2026-10-01T00:00:00Z" }
          }
        }
        """), now: now)

        XCTAssertEqual(windows[0].category, .monthly)
    }

    func testSnakeCaseSpellingsParseIdentically() throws {
        let windows = XaiBillingParser.parseBilling(payload("""
        {
          "config": {
            "monthly_limit": 10000, "used": 3000,
            "on_demand_cap": 5000, "on_demand_used": 250,
            "billing_period_end": "2026-10-01T00:00:00Z"
          }
        }
        """), now: now)

        XCTAssertEqual(try percent(windows, "xai-monthly"), 30.0, accuracy: 0.001)
        XCTAssertEqual(try percent(windows, "xai-on-demand"), 5.0, accuracy: 0.001)
    }

    // MARK: - Merge and empties

    func testMergePutsCreditsFirstAndDropsDuplicateIDs() {
        let credits = XaiBillingParser.parseCredits(payload("""
        { "config": { "creditUsagePercent": 20.0 } }
        """), now: now)
        let billing = XaiBillingParser.parseBilling(payload("""
        { "config": { "monthlyLimit": 10000, "used": 1000 } }
        """), now: now)

        let merged = XaiBillingParser.merge(credits, billing)
        XCTAssertEqual(merged.map(\.id), ["xai-credits"])
        XCTAssertEqual(Set(merged.map(\.id)).count, merged.count)
    }

    func testEmptyPayloadsYieldEmptyLists() {
        XCTAssertTrue(XaiBillingParser.parseCredits(payload("{}"), now: now).isEmpty)
        XCTAssertTrue(XaiBillingParser.parseBilling(payload("{}"), now: now).isEmpty)
        XCTAssertTrue(XaiBillingParser.merge([], []).isEmpty)
    }

    func testMergeReplacesUnknownCreditsWithMeasuredBillingFallback() throws {
        let credits = XaiBillingParser.parseCredits(payload("""
        { "creditUsagePercent": null, "currentPeriod": { "type": "weekly" } }
        """), now: now)
        let billing = XaiBillingParser.parseBilling(payload("""
        { "creditUsagePercent": 20, "usagePeriodType": "weekly", "monthlyLimit": 1000, "used": 100 }
        """), now: now)

        let merged = XaiBillingParser.merge(credits, billing)
        XCTAssertEqual(merged.map(\.id), ["xai-credits"])
        XCTAssertEqual(try percent(merged, "xai-credits"), 20)
        XCTAssertEqual(try percent(XaiBillingParser.merge(billing, credits), "xai-credits"), 20)
    }

    func testNullAndInvalidCreditPercentagesNeverBecomeFullCredit() throws {
        let values = ["null", "-1", "101", "true", "\"bad\"", "\"NaN\"", "\"Infinity\"", "{}", "[]"]
        for value in values {
            for metadata in ["", ", \"currentPeriod\": { \"type\": \"weekly\", \"start\": \"2026-09-02T00:00:00Z\", \"end\": \"2026-09-09T00:00:00Z\" }"] {
                let windows = XaiBillingParser.parseCredits(payload("{\"creditUsagePercent\": \(value)\(metadata)}"), now: liveNow)
                let window = try XCTUnwrap(windows.first, value)
                XCTAssertNil(window.usedPercent, value)
                XCTAssertNil(window.remainingPercent, value)
                XCTAssertFalse(window.exhausted, value)
            }
        }
    }

    func testExplicitCreditPercentagesPreserveProviderScale() throws {
        for value in [0.0, 0.1, 20, 99.9, 100] {
            let windows = XaiBillingParser.parseCredits(["creditUsagePercent": value], now: now)
            XCTAssertEqual(try percent(windows, "xai-credits"), value)
        }
    }

    func testInvalidMoneyValuesStayUnknown() throws {
        for value in ["null", "-1", "true", "\"bad\"", "\"NaN\"", "\"Infinity\"", "{\"val\": -1}", "{\"val\": null}", "{\"unknown\": 1}", "[]"] {
            let invalidSpend = XaiBillingParser.parseBilling(payload("{\"monthlyLimit\":10000,\"used\":\(value)}"), now: now)
            XCTAssertNil(try XCTUnwrap(invalidSpend.first { $0.id == "xai-monthly" }).usedPercent, value)
            let invalidLimit = XaiBillingParser.parseBilling(payload("{\"monthlyLimit\":\(value),\"used\":500}"), now: now)
            XCTAssertNil(try XCTUnwrap(invalidLimit.first { $0.id == "xai-monthly" }).usedPercent, value)
            let invalidOnly = XaiBillingParser.parseBilling(payload("{\"monthlyLimit\":\(value)}"), now: now)
            XCTAssertNil(try XCTUnwrap(invalidOnly.first { $0.id == "xai-monthly" }).usedPercent, value)
        }
    }

    func testPresentEmptyCentObjectIsTheOnlyImplicitMoneyZero() throws {
        // xai-org/grok-build Cent.val has serde(default): a present {} is zero cents.
        let zero = XaiBillingParser.parseBilling(payload("""
        { "monthlyLimit": { "val": 10000 }, "used": {} }
        """), now: now)
        XCTAssertEqual(try percent(zero, "xai-monthly"), 0)
        let absent = XaiBillingParser.parseBilling(payload("{\"monthlyLimit\":10000}"), now: now)
        XCTAssertNil(try XCTUnwrap(absent.first).usedPercent)
    }

    func testExplicitUnknownOnDemandSpendDoesNotDeriveFromTotalSpend() throws {
        for value in ["null", "-1", "\"NaN\"", "{\"val\":null}"] {
            let windows = XaiBillingParser.parseBilling(payload("{\"monthlyLimit\":10000,\"used\":14000,\"onDemandCap\":5000,\"onDemandUsed\":\(value)}"), now: now)
            XCTAssertNil(try XCTUnwrap(windows.first { $0.id == "xai-on-demand" }).usedPercent, value)
        }
        let absent = XaiBillingParser.parseBilling(payload("""
        { "monthlyLimit": 10000, "used": 14000, "onDemandCap": 5000 }
        """), now: now)
        XCTAssertEqual(try percent(absent, "xai-on-demand"), 80)
    }

    func testOnDemandOverageIsExhaustedAfterValidatingAmounts() throws {
        let windows = XaiBillingParser.parseBilling(payload("""
        { "monthlyLimit": 10000, "used": 14000, "onDemandCap": 5000, "onDemandUsed": 6000 }
        """), now: now)
        XCTAssertEqual(try percent(windows, "xai-on-demand"), 100)
        XCTAssertTrue(try XCTUnwrap(windows.first { $0.id == "xai-on-demand" }).exhausted)
    }

    func testOnDemandCanBeMeasuredWithoutMonthlyFields() throws {
        let windows = XaiBillingParser.parseBilling(payload("""
        { "onDemandCap": { "val": 5000 }, "onDemandUsed": { "val": 1000 } }
        """), now: now)
        XCTAssertEqual(windows.map(\.id), ["xai-on-demand"])
        XCTAssertEqual(try percent(windows, "xai-on-demand"), 20)
    }

    func testUnknownOnDemandCapPreservesAnUnknownWindow() throws {
        for json in ["{\"onDemandCap\":null}", "{\"onDemandCap\":-1}", "{\"onDemandCap\":\"Infinity\"}", "{\"onDemandUsed\":1000}"] {
            let windows = XaiBillingParser.parseBilling(payload(json), now: now)
            let onDemand = try XCTUnwrap(windows.first { $0.id == "xai-on-demand" }, json)
            XCTAssertNil(onDemand.usedPercent, json)
            XCTAssertFalse(onDemand.exhausted, json)
            XCTAssertEqual(onDemand.scope, .model, json)
        }
    }

    func testIncludedAndOnDemandAreIndependentPoolsWhenOnDemandIsAvailable() throws {
        let windows = XaiBillingParser.parseBilling(payload("""
        { "monthlyLimit": 10000, "used": 11000,
          "onDemandCap": 5000, "onDemandUsed": 1000 }
        """), now: now)
        let monthly = try XCTUnwrap(windows.first { $0.id == "xai-monthly" })
        let onDemand = try XCTUnwrap(windows.first { $0.id == "xai-on-demand" })
        XCTAssertEqual(monthly.scope, .model)
        XCTAssertEqual(onDemand.scope, .model)
        XCTAssertTrue(monthly.exhausted)
        XCTAssertEqual(onDemand.remainingPercent, 80)

        let withoutOnDemand = XaiBillingParser.parseBilling(payload("""
        { "monthlyLimit": 10000, "used": 10000, "onDemandCap": 0 }
        """), now: now)
        XCTAssertEqual(try XCTUnwrap(withoutOnDemand.first).scope, .global)
    }

    func testMeasuredCreditsSupersedeDeprecatedMonthlyAllowanceInEitherEndpoint() throws {
        // The first-party BillingConfig contract says to prefer credit_usage_percent over
        // deprecated monthly_limit/used; both describe included credit, not separate limits.
        for usedPercent in [0.0, 20.0, 100.0] {
            let source: [String: Any] = ["creditUsagePercent": usedPercent,
                "monthlyLimit": 10000, "used": 10000, "onDemandCap": 5000, "onDemandUsed": 1000]
            let fromBilling = XaiBillingParser.parseBilling(source, now: now)
            XCTAssertEqual(fromBilling.map(\.id), ["xai-credits", "xai-on-demand"])
            XCTAssertEqual(try percent(fromBilling, "xai-credits"), usedPercent)
            // A positive cap makes the included allowance one of two pools, exactly as the
            // deprecated monthly view of the same account reports it.
            XCTAssertEqual(fromBilling.first?.scope, .model)

            let credits = XaiBillingParser.parseCredits(["creditUsagePercent": usedPercent], now: now)
            let legacy = XaiBillingParser.parseBilling(["monthlyLimit": 10000, "used": 10000], now: now)
            XCTAssertEqual(XaiBillingParser.merge(credits, legacy).map(\.id), ["xai-credits"])
        }
    }

    func testUnknownCreditsKeepDeprecatedMonthlyFallback() throws {
        let creditValues: [Any] = [NSNull(), -1, "bad"]
        for credit in creditValues {
            let source: [String: Any] = ["creditUsagePercent": credit, "monthlyLimit": 10000, "used": 2000]
            let windows = XaiBillingParser.parseBilling(source, now: now)
            XCTAssertEqual(windows.map(\.id), ["xai-credits", "xai-monthly"])
            XCTAssertEqual(try percent(windows, "xai-monthly"), 20)
        }
    }

    func testExplicitUnknownPrimaryAmountDoesNotReadAnAlternateAsAZeroDefault() throws {
        let windows = XaiBillingParser.parseBilling(payload("""
        { "monthlyLimit": 10000, "used": null, "includedUsed": 0,
          "creditUsagePercent": null, "credit_usage_percent": 0 }
        """), now: now)
        XCTAssertNil(try XCTUnwrap(windows.first { $0.id == "xai-monthly" }).usedPercent)
        XCTAssertNil(try XCTUnwrap(windows.first { $0.id == "xai-credits" }).usedPercent)
    }

    func testInvalidUsageDiagnosticSeparatesUnknownFromMalformedValues() {
        for json in ["{}", "{\"creditUsagePercent\":null}", "{\"monthlyLimit\":null}", "{\"used\":{}}", "{\"used\":{\"val\":null}}", "{\"creditUsagePercent\":0}", "{\"used\":\"500\"}"] {
            XCTAssertFalse(XaiBillingParser.hasInvalidUsage(payload(json)), json)
        }
        for json in ["{\"creditUsagePercent\":101}", "{\"creditUsagePercent\":true}", "{\"creditUsagePercent\":\"NaN\"}", "{\"creditUsagePercent\":{\"val\":null}}", "{\"monthlyLimit\":-1}", "{\"used\":{\"val\":\"Infinity\"}}", "{\"used\":{\"wrong\":0}}", "{\"onDemandCap\":false}", "{\"onDemandUsed\":-1}"] {
            XCTAssertTrue(XaiBillingParser.hasInvalidUsage(payload(json)), json)
        }
    }

    // MARK: - Missing percentages remain unknown

    private var liveNow: Date { ISO8601DateFormatter().date(from: "2026-09-05T12:00:00Z")! }

    /// A period identifies the row, but does not prove that the absent percentage is zero.
    private let weekWithoutPercentage = """
    { "config": {
        "currentPeriod": { "type": "USAGE_PERIOD_TYPE_WEEKLY",
                           "start": "2026-09-02T00:00:00Z",
                           "end":   "2026-09-09T00:00:00Z" },
        "onDemandCap": { "val": 0 }, "onDemandUsed": { "val": 0 },
        "isUnifiedBillingUser": true } }
    """

    func testAnAbsentPercentageInsideTheLivePeriodKeepsAnUnknownRow() throws {
        // xai-org/grok-build BillingConfig declares credit_usage_percent as Option<f64>.
        // Its presence cannot be inferred from the current period.
        let windows = XaiBillingParser.parseCredits(payload(weekWithoutPercentage), now: liveNow)

        XCTAssertEqual(windows.count, 1)
        let window = try XCTUnwrap(windows.first)
        XCTAssertEqual(window.id, "xai-credits")
        XCTAssertNil(window.usedPercent)
        XCTAssertNil(window.remainingPercent)
        XCTAssertEqual(window.category, .weekly)
        XCTAssertEqual(window.resetAt, ISO8601DateFormatter().date(from: "2026-09-09T00:00:00Z"))
        XCTAssertFalse(window.exhausted)
    }

    func testAnAbsentPercentageStaysUnknownAcrossPeriodBoundaries() throws {
        let later = ISO8601DateFormatter().date(from: "2026-09-20T12:00:00Z")!
        let earlier = ISO8601DateFormatter().date(from: "2026-09-01T12:00:00Z")!
        let boundary = ISO8601DateFormatter().date(from: "2026-09-09T00:00:00Z")!
        for date in [earlier, liveNow, boundary, later] {
            let window = try XCTUnwrap(XaiBillingParser.parseCredits(
                payload(weekWithoutPercentage), now: date).first)
            XCTAssertNil(window.usedPercent)
            XCTAssertNil(window.remainingPercent)
        }

        // Without a credit field or period there is no evidence of a credit window.
        let noPeriod = """
        { "config": { "isUnifiedBillingUser": true, "onDemandCap": { "val": 0 } } }
        """
        XCTAssertTrue(XaiBillingParser.parseCredits(payload(noPeriod), now: liveNow).isEmpty)
    }

    func testTheFlatUsagePeriodFieldsIdentifyTheWeeklyWindow() throws {
        let flat = """
        { "creditUsagePercent": 12.0,
          "usagePeriodType": "USAGE_PERIOD_TYPE_WEEKLY",
          "usagePeriodStart": "2026-09-02T00:00:00Z",
          "usagePeriodEnd":   "2026-09-09T00:00:00Z" }
        """
        let window = try XCTUnwrap(XaiBillingParser.parseCredits(payload(flat), now: liveNow).first)

        XCTAssertEqual(window.usedPercent, 12)
        XCTAssertEqual(window.category, .weekly)
        XCTAssertEqual(window.periodSeconds, 604_800)
        XCTAssertEqual(window.resetAt, ISO8601DateFormatter().date(from: "2026-09-09T00:00:00Z"))

        let flatUnknown = """
        { "usagePeriodType": "USAGE_PERIOD_TYPE_WEEKLY",
          "usagePeriodStart": "2026-09-02T00:00:00Z",
          "usagePeriodEnd":   "2026-09-09T00:00:00Z" }
        """
        let unknown = try XCTUnwrap(XaiBillingParser.parseCredits(payload(flatUnknown), now: liveNow).first)
        XCTAssertEqual(unknown.category, .weekly)
        XCTAssertNil(unknown.usedPercent)
    }

    func testTheUnifiedBillingViewPrefersWeeklyCreditsOverDeprecatedMonthlyUsage() throws {
        // The plain billing view of a unified-billing account carries the weekly figures too.
        // Reading them here keeps the weekly row when the credit view alone stops answering,
        // and merge keeps exactly one copy when both do.
        let unified = """
        { "monthlyLimit": 10000, "used": 4200, "onDemandCap": 0,
          "creditUsagePercent": 41.0,
          "usagePeriodType": "USAGE_PERIOD_TYPE_WEEKLY",
          "usagePeriodStart": "2026-09-02T00:00:00Z",
          "usagePeriodEnd":   "2026-09-09T00:00:00Z",
          "billingPeriodStart": "2026-09-01T00:00:00Z",
          "billingPeriodEnd":   "2026-10-01T00:00:00Z" }
        """
        let fromBilling = XaiBillingParser.parseBilling(payload(unified), now: liveNow)
        XCTAssertEqual(fromBilling.map(\.id), ["xai-credits"])
        XCTAssertEqual(try percent(fromBilling, "xai-credits"), 41, accuracy: 0.001)
        XCTAssertNil(fromBilling.first { $0.id == "xai-monthly" })

        let fromCredits = XaiBillingParser.parseCredits(payload("""
        { "config": { "creditUsagePercent": 34.0,
                      "currentPeriod": { "type": "weekly",
                                         "start": "2026-09-02T00:00:00Z",
                                         "end": "2026-09-09T00:00:00Z" } } }
        """), now: liveNow)
        let merged = XaiBillingParser.merge(fromCredits, fromBilling)
        XCTAssertEqual(merged.filter { $0.id == "xai-credits" }.count, 1)
        XCTAssertEqual(try percent(merged, "xai-credits"), 34, accuracy: 0.001, "the credit view's own figure wins")

        // A legacy billing view has no weekly figures and keeps yielding only its own rows.
        let legacy = XaiBillingParser.parseBilling(payload("""
        { "monthlyLimit": 10000, "used": 4200, "onDemandCap": 5000, "onDemandUsed": 0,
          "billingPeriodEnd": "2026-10-01T00:00:00Z" }
        """), now: liveNow)
        XCTAssertEqual(legacy.map(\.id), ["xai-monthly", "xai-on-demand"])
    }

    // MARK: - The credit and monthly views of one allowance agree on availability

    private func severity(_ windows: [UsageWindow]) -> Severity {
        UsageSnapshot(accountID: "a", fetchedAt: now, status: .ok, windows: windows).severity
    }

    private func views(_ fields: String) -> (monthly: Severity, credits: Severity, unified: Severity) {
        let monthly = XaiBillingParser.parseBilling(
            payload(#"{"monthlyLimit": 10000, "used": 10000, \#(fields)}"#), now: now)
        let credits = XaiBillingParser.merge(
            XaiBillingParser.parseCredits(payload(#"{"creditUsagePercent": 100}"#), now: now),
            XaiBillingParser.parseBilling(payload("{\(fields)}"), now: now))
        let unified = XaiBillingParser.parseBilling(
            payload(#"{"creditUsagePercent": 100, "monthlyLimit": 10000, "used": 10000, \#(fields)}"#), now: now)
        return (severity(monthly), severity(credits), severity(unified))
    }

    func testExhaustedCreditsWithUnusedPositiveOnDemandCapAreLimited() {
        let result = views(#""onDemandCap": 5000, "onDemandUsed": 0"#)
        XCTAssertEqual(result.monthly, .limited)
        XCTAssertEqual(result.credits, .limited)
        XCTAssertEqual(result.unified, .limited)
    }

    func testBothPoolsExhaustedReadExhaustedInEitherRepresentation() {
        let result = views(#""onDemandCap": 5000, "onDemandUsed": 5000"#)
        XCTAssertEqual(result.monthly, .exhausted)
        XCTAssertEqual(result.credits, .exhausted)
        XCTAssertEqual(result.unified, .exhausted)
    }

    func testWithoutAProvenPositiveCapExhaustedCreditsStayGlobal() {
        for fields in [#""onDemandCap": 0, "onDemandUsed": 0"#, #""onDemandUsed": 0"#,
                       #""onDemandCap": "x""#, #""onDemandCap": null"#] {
            let credits = XaiBillingParser.merge(
                XaiBillingParser.parseCredits(payload(#"{"creditUsagePercent": 100}"#), now: now),
                XaiBillingParser.parseBilling(payload("{\(fields)}"), now: now))
            XCTAssertEqual(credits.first { $0.id == "xai-credits" }?.scope, .global, fields)
            XCTAssertEqual(severity(credits), .exhausted, fields)
        }
        let alone = XaiBillingParser.merge(
            XaiBillingParser.parseCredits(payload(#"{"creditUsagePercent": 100}"#), now: now), [])
        XCTAssertEqual(severity(alone), .exhausted)
    }
}
