import XCTest
@testable import UsageLimitsKit

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The Credit Viewer: parsers (with the quirks recorded in docs/credits.md), clients, severity,
/// formatting, storage, the container wiring and the widget file. Every key is synthetic.
final class CreditsTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_757_000_000)
    private let usEnglish = Locale(identifier: "en_US")
    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("credits-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func json(_ text: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] ?? [:]
    }

    private func dec(_ text: String) -> Decimal { Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))! }

    /// Answers by URL fragment, in order per route, repeating the last. Records every request.
    private actor Transport: HTTPTransport {
        private var routes: [(match: String, replies: [(status: Int, body: String)])]
        private(set) var requests: [URLRequest] = []

        init(_ routes: [(String, [(Int, String)])]) {
            self.routes = routes.map { ($0.0, $0.1.map { (status: $0.0, body: $0.1) }) }
        }

        var urls: [String] { requests.compactMap { $0.url?.absoluteString } }

        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            requests.append(request)
            let url = request.url?.absoluteString ?? ""
            var reply: (status: Int, body: String) = (404, "{}")
            if let index = routes.firstIndex(where: { url.contains($0.match) }) {
                reply = routes[index].replies.count > 1
                    ? routes[index].replies.removeFirst() : routes[index].replies[0]
            }
            let response = HTTPURLResponse(
                url: request.url ?? URL(string: "https://example.invalid")!,
                statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: [:])!
            return (Data(reply.body.utf8), response)
        }
    }

    private func http(_ transport: Transport) -> UsageHTTPClient {
        UsageHTTPClient(transport: transport, maxRetries: 0, now: { [now] in now })
    }

    // MARK: - Decimal handling

    func testMoneyIsExactAndStringsAreStrict() {
        XCTAssertEqual(CreditJSON.decimal(["a": 0.1], "a")! + CreditJSON.decimal(["a": 0.2], "a")!, dec("0.3"))
        XCTAssertEqual(CreditJSON.decimal(["a": 49.58894], "a"), dec("49.58894"))
        XCTAssertEqual(CreditJSON.decimal(["a": 1e-05], "a"), dec("0.00001"))
        XCTAssertNil(CreditJSON.decimal(["a": "12abc"], "a"))
        XCTAssertNil(CreditJSON.decimal(["a": ""], "a"))
        XCTAssertNil(CreditJSON.decimal(["a": true], "a"))
        XCTAssertEqual(CreditJSON.decimal(["a": " -3.50 "], "a"), dec("-3.5"))
    }

    private func assertMalformed(_ body: @autoclosure () throws -> Any, _ message: String = "", file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try body(), message, file: file, line: line) { error in
            guard case .malformed = error as? CreditError else {
                return XCTFail("expected a malformed payload error, got \(error). \(message)", file: file, line: line)
            }
        }
    }

    func testNumericMoneyNeverGoesThroughDouble() async throws {
        // As a Double 100.000000000000001 is exactly 100, and the balance would read exhausted.
        let body = #"{"data":{"total_credits":100.000000000000001,"total_usage":100}}"#
        let balances = try OpenRouterCreditParser.parseCredits(try XCTUnwrap(CreditJSON.object(fromText: body)))
        XCTAssertEqual(balances[0].amount, dec("0.000000000000001"))
        XCTAssertEqual(balances[0].total, dec("100.000000000000001"))

        let transport = Transport([("/api/v1/credits", [(200, body)])])
        let fetched = try await OpenRouterCreditClient(httpClient: http(transport))
            .fetchBalances(key: "sk-test", attributes: [:])
        XCTAssertEqual(fetched[0].amount, dec("0.000000000000001"))

        XCTAssertEqual(
            CreditJSON.decimal(try XCTUnwrap(CreditJSON.object(fromText: #"{"a":1e-05}"#)), "a"),
            dec("0.00001"))
        // Literals and strings are not mistaken for numbers.
        let mixed = try XCTUnwrap(CreditJSON.object(fromText: #"{"ok":true,"gone":null,"s":"a 1 \" 2","n":-3}"#))
        XCTAssertEqual(mixed["ok"] as? Bool, true)
        XCTAssertTrue(mixed["gone"] is NSNull)
        XCTAssertEqual(mixed["s"] as? String, #"a 1 " 2"#)
        XCTAssertEqual(CreditJSON.decimal(mixed, "n"), -3)
        XCTAssertNil(CreditJSON.object(fromText: "[1]"))
        // A combining mark right after a quote must not hide the quote.
        let accented = try XCTUnwrap(CreditJSON.object(fromText: "{\"s\":\"\u{301}12\",\"n\":5}"))
        XCTAssertEqual(accented["s"] as? String, "\u{301}12")
        XCTAssertEqual(CreditJSON.decimal(accented, "n"), 5)
    }

    func testOpenRouterKeyMissingFieldsAreMalformedNotUnlimited() throws {
        for body in [
            #"{}"#, #"{"data":{}}"#, #"{"data":null}"#,
            #"{"data":{"usage_monthly":3}}"#,  // no `limit` at all
            #"{"data":{"limit":null}}"#,       // unlimited, but no spend to show
            #"{"data":{"limit":null,"usage_monthly":"abc"}}"#,
        ] {
            assertMalformed(try OpenRouterCreditParser.parseKey(json(body)), body)
        }
        // An explicit null limit with a spend is the genuine unlimited key, even at $0.
        let balance = try OpenRouterCreditParser.parseKey(json(#"{"data":{"limit":null,"usage_monthly":0}}"#))[0]
        XCTAssertTrue(balance.unlimited)
        XCTAssertEqual(balance.amount, 0)
    }

    func testOpenRouterCreditsMissingTotalsAreMalformed() {
        for body in [#"{}"#, #"{"data":{}}"#, #"{"data":{"total_usage":1}}"#, #"{"data":{"total_credits":1}}"#] {
            assertMalformed(try OpenRouterCreditParser.parseCredits(json(body)), body)
        }
    }

    // MARK: - OpenRouter

    func testOpenRouterCreditsIsPurchasedMinusUsed() throws {
        let balances = try OpenRouterCreditParser.parseCredits(
            json(#"{"data":{"total_credits":100.5,"total_usage":25.75}}"#))
        XCTAssertEqual(balances, [CreditBalance(currency: "USD", amount: dec("74.75"), total: dec("100.5"))])
        XCTAssertThrowsError(try OpenRouterCreditParser.parseCredits(json(#"{"data":{"total_credits":1}}"#)))
    }

    func testOpenRouterKeyPrefersEffectiveBudget() throws {
        let body = #"""
        {"data":{"label":"k","limit":50,"limit_remaining":40,"usage_monthly":3,
         "effective_budget":{"limit_usd":20,"spend_usd":5,"remaining_usd":15,"reset_interval":"monthly"}}}
        """#
        let balance = try OpenRouterCreditParser.parseKey(json(body))[0]
        XCTAssertEqual(balance.amount, 15)
        XCTAssertEqual(balance.total, 20)
        XCTAssertFalse(balance.unlimited)
    }

    func testOpenRouterKeyFallsBackToLimitRemaining() throws {
        let balance = try OpenRouterCreditParser.parseKey(
            json(#"{"data":{"limit":50,"limit_remaining":40.25,"effective_budget":null}}"#))[0]
        XCTAssertEqual(balance.amount, dec("40.25"))
        XCTAssertEqual(balance.total, 50)
    }

    func testOpenRouterKeyWithNoLimitIsUnlimitedNotZero() throws {
        let balance = try OpenRouterCreditParser.parseKey(
            json(#"{"data":{"limit":null,"limit_remaining":null,"usage_monthly":12.5,"effective_budget":null}}"#))[0]
        XCTAssertTrue(balance.unlimited)
        XCTAssertEqual(balance.label, "No limit")
        XCTAssertEqual(balance.amount, dec("12.5"))
        XCTAssertEqual(CreditSeverity.of(balance, lowThreshold: 5), .healthy)
    }

    func testOpenRouterClientFallsBackOnlyOn403() async throws {
        let transport = Transport([
            ("/api/v1/credits", [(403, #"{"error":"Only management keys"}"#)]),
            ("/api/v1/key", [(200, #"{"data":{"limit":10,"limit_remaining":4}}"#)]),
        ])
        let balances = try await OpenRouterCreditClient(httpClient: http(transport))
            .fetchBalances(key: "sk-test", attributes: [:])
        XCTAssertEqual(balances[0].amount, 4)
        let urls = await transport.urls
        XCTAssertEqual(urls, ["https://openrouter.ai/api/v1/credits", "https://openrouter.ai/api/v1/key"])

        // A bad key (401) is a bad key: no second request.
        let rejected = Transport([("/api/v1/credits", [(401, "{}")])])
        do {
            _ = try await OpenRouterCreditClient(httpClient: http(rejected)).fetchBalances(key: "x", attributes: [:])
            XCTFail("expected a rejection")
        } catch {
            XCTAssertEqual(error as? CreditError, .rejected(401))
        }
        let rejectedCount = await rejected.urls.count
        XCTAssertEqual(rejectedCount, 1)
    }

    // MARK: - DeepSeek

    func testDeepSeekGivesOneBalancePerCurrency() throws {
        let body = #"""
        {"is_available":true,"balance_infos":[
          {"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"},
          {"currency":"USD","total_balance":"2.50","granted_balance":"0.00","topped_up_balance":"2.50"}]}
        """#
        let balances = try DeepSeekCreditParser.parse(json(body))
        XCTAssertEqual(balances.map(\.currency), ["CNY", "USD"])
        XCTAssertEqual(balances.map(\.amount), [110, dec("2.5")])
    }

    func testDeepSeekUnavailableReadsAsEmpty() throws {
        let balances = try DeepSeekCreditParser.parse(
            json(#"{"is_available":false,"balance_infos":[{"currency":"CNY","total_balance":"3.00"}]}"#))
        XCTAssertEqual(balances[0].amount, 0)
        XCTAssertEqual(CreditSeverity.of(balances[0], lowThreshold: 35), .exhausted)
        XCTAssertEqual(try DeepSeekCreditParser.parse(json(#"{"is_available":false,"balance_infos":[]}"#))[0].amount, 0)
        XCTAssertThrowsError(try DeepSeekCreditParser.parse(json(#"{"is_available":true,"balance_infos":[]}"#)))
    }

    // MARK: - Moonshot

    func testMoonshotShowsAvailableEvenWhenCashIsNegative() throws {
        let body = #"{"code":0,"data":{"available_balance":49.58894,"voucher_balance":50.58894,"cash_balance":-1.0},"scode":"0x0","status":true}"#
        let balance = try MoonshotCreditParser.parse(json(body), region: .intl)[0]
        XCTAssertEqual(balance.currency, "USD")
        XCTAssertEqual(balance.amount, dec("49.58894"))
        XCTAssertEqual(try MoonshotCreditParser.parse(json(body), region: .cn)[0].currency, "CNY")
        XCTAssertThrowsError(try MoonshotCreditParser.parse(json(#"{"code":0,"data":{}}"#), region: .intl))
    }

    func testMoonshotHostFollowsTheRegion() async throws {
        let transport = Transport([("moonshot", [(200, #"{"data":{"available_balance":1}}"#)])])
        let client = MoonshotCreditClient(httpClient: http(transport))
        _ = try await client.fetchBalances(key: "k", attributes: ["region": "cn"])
        _ = try await client.fetchBalances(key: "k", attributes: [:])
        let urls = await transport.urls
        XCTAssertEqual(urls, [
            "https://api.moonshot.cn/v1/users/me/balance", "https://api.moonshot.ai/v1/users/me/balance",
        ])
    }

    // MARK: - xAI

    func testXaiBalanceSignIsInvertedAndInCents() throws {
        XCTAssertEqual(try XaiCreditParser.parseBalance(json(#"{"changes":[],"total":{"val":"-1000"}}"#))[0].amount, 10)
        XCTAssertEqual(try XaiCreditParser.parseBalance(json(#"{"total":{"val":"-2317"}}"#))[0].amount, dec("23.17"))
        // Spent past the purchase: arrears, shown negative rather than hidden.
        XCTAssertEqual(try XaiCreditParser.parseBalance(json(#"{"total":{"val":"250"}}"#))[0].amount, dec("-2.5"))
        XCTAssertEqual(try XaiCreditParser.parseBalance(json(#"{"total":{"val":"0"}}"#))[0].amount, 0)
    }

    func testXaiMissingTotalIsAnErrorNeverZero() {
        for body in [#"{}"#, #"{"total":{}}"#, #"{"total":{"val":"abc"}}"#, #"{"total":null}"#] {
            XCTAssertThrowsError(try XaiCreditParser.parseBalance(json(body)), body)
        }
    }

    func testXaiTeamComesFromATeamScopedKeyOnly() {
        XCTAssertEqual(XaiCreditParser.parseTeamID(json(#"{"scope":"SCOPE_TEAM","scopeId":"team-9"}"#)), "team-9")
        XCTAssertNil(XaiCreditParser.parseTeamID(json(#"{"scope":"SCOPE_ORGANIZATION","scopeId":"org-1"}"#)))
        XCTAssertNil(XaiCreditParser.parseTeamID(json(#"{"scope":"SCOPE_TEAM"}"#)))
    }

    func testXaiClientResolvesTeamThenReadsTheLedger() async throws {
        let transport = Transport([
            ("auth/management-keys/validation", [(200, #"{"scope":"SCOPE_TEAM","scopeId":"team-9"}"#)]),
            ("/v1/billing/teams/team-9/prepaid/balance", [(200, #"{"total":{"val":"-1000"}}"#)]),
        ])
        let balances = try await XaiCreditClient(httpClient: http(transport)).fetchBalances(key: "mk", attributes: [:])
        XCTAssertEqual(balances[0].amount, 10)

        // A stored team id skips the validation call.
        let stored = Transport([("prepaid/balance", [(200, #"{"total":{"val":"-500"}}"#)])])
        _ = try await XaiCreditClient(httpClient: http(stored)).fetchBalances(key: "mk", attributes: ["teamId": "t/../x"])
        let urls = await stored.urls
        XCTAssertEqual(urls, ["https://management-api.x.ai/v1/billing/teams/t%2F..%2Fx/prepaid/balance"])
    }

    func testXaiOrgKeyWithoutTeamAsksForOneAnd404IsNotZero() async {
        let org = Transport([("validation", [(200, #"{"scope":"SCOPE_ORGANIZATION","scopeId":"o"}"#)])])
        do {
            _ = try await XaiCreditClient(httpClient: http(org)).fetchBalances(key: "mk", attributes: [:])
            XCTFail("expected teamIDRequired")
        } catch { XCTAssertEqual(error as? CreditError, .teamIDRequired) }

        let missing = Transport([("prepaid/balance", [(404, "{}")])])
        do {
            _ = try await XaiCreditClient(httpClient: http(missing)).fetchBalances(key: "mk", attributes: ["teamId": "t"])
            XCTFail("expected notFound")
        } catch {
            guard case .notFound? = error as? CreditError else { return XCTFail("\(error)") }
        }
    }

    // MARK: - fal and Novita

    func testFalReadsCreditsAndTreatsAMissingBlockAsAnError() throws {
        let balance = try FalCreditParser.parse(json(#"{"username":"t","credits":{"current_balance":24.5,"currency":"USD"}}"#))[0]
        XCTAssertEqual(balance.amount, dec("24.5"))
        XCTAssertEqual(try FalCreditParser.parse(json(#"{"credits":{"current_balance":"7.25","currency":"eur"}}"#))[0].currency, "EUR")
        XCTAssertThrowsError(try FalCreditParser.parse(json(#"{"username":"t"}"#)))
        XCTAssertThrowsError(try FalCreditParser.parse(json(#"{"credits":{"currency":"USD"}}"#)))
    }

    func testFalClientSendsKeyPrefixAndExpand() async throws {
        let transport = Transport([("api.fal.ai", [(200, #"{"credits":{"current_balance":1,"currency":"USD"}}"#)])])
        _ = try await FalCreditClient(httpClient: http(transport)).fetchBalances(key: "fk", attributes: [:])
        let request = await transport.requests[0]
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Key fk")
        XCTAssertEqual(request.url?.absoluteString, "https://api.fal.ai/v1/account/billing?expand=credits")
    }

    func testNovitaStringsAreTenThousandthsOfADollar() throws {
        XCTAssertEqual(try NovitaCreditParser.parse(json(#"{"availableBalance":"1000000","cashBalance":"800000"}"#))[0].amount, 100)
        XCTAssertEqual(try NovitaCreditParser.parse(json(#"{"availableBalance":"5"}"#))[0].amount, dec("0.0005"))
        XCTAssertEqual(try NovitaCreditParser.parse(json(#"{"availableBalance":"-25000"}"#))[0].amount, dec("-2.5"))
        XCTAssertThrowsError(try NovitaCreditParser.parse(json(#"{"availableBalance":"lots"}"#)))
        XCTAssertThrowsError(try NovitaCreditParser.parse(json(#"{}"#)))
    }

    // MARK: - Severity and formatting

    private func account(_ attributes: [String: String] = [:]) -> CreditAccount {
        CreditAccount(id: "a", provider: .deepseek, label: "A", credentialReference: "credit:a",
                      attributes: attributes, createdAt: now)
    }

    func testSeverityWithAndWithoutATotal() {
        func level(_ amount: String, total: String? = nil, low: Decimal = 5) -> Severity {
            CreditSeverity.of(CreditBalance(currency: "USD", amount: dec(amount), total: total.map(dec)), lowThreshold: low)
        }
        XCTAssertEqual(level("0"), .exhausted)
        XCTAssertEqual(level("-3"), .exhausted)
        XCTAssertEqual(level("4.99"), .low)
        XCTAssertEqual(level("5"), .medium)
        XCTAssertEqual(level("9.99"), .medium)
        XCTAssertEqual(level("10"), .healthy)
        XCTAssertEqual(level("30", low: 20), .medium)
        // With a total the share decides, whatever the threshold says.
        XCTAssertEqual(level("60", total: "100"), .healthy)
        XCTAssertEqual(level("50", total: "100"), .medium)
        XCTAssertEqual(level("20", total: "100"), .low)
        XCTAssertEqual(level("1", total: "100"), .low)
        XCTAssertEqual(level("150", total: "100"), .healthy)
        XCTAssertEqual(level("0", total: "100"), .exhausted)
    }

    func testDefaultThresholdIsFiveUnitsAndThirtyFiveForYuan() {
        XCTAssertEqual(account().lowThreshold(for: "USD"), 5)
        XCTAssertEqual(account().lowThreshold(for: "CNY"), 35)
        XCTAssertEqual(account(["lowThreshold": "12.5"]).lowThreshold(for: "CNY"), dec("12.5"))
        XCTAssertEqual(account(["lowThreshold": "junk"]).lowThreshold(for: "USD"), 5)
        XCTAssertEqual(account(["lowThreshold": "-1"]).lowThreshold(for: "USD"), 5)
    }

    func testAccountSeverityTakesTheWorstBalanceAndAgesLikeUsage() {
        let balances = [CreditBalance(currency: "CNY", amount: 200), CreditBalance(currency: "USD", amount: 1)]
        let fresh = CreditSnapshot(accountID: "a", balances: balances, fetchedAt: now)
        XCTAssertEqual(CreditSeverity.base(account(), fresh), .low)
        XCTAssertEqual(CreditSeverity.base(account(), nil), .unknown)
        let failedEmpty = CreditSnapshot(accountID: "a", balances: [], fetchedAt: now, status: .failed, error: "x")
        XCTAssertEqual(CreditSeverity.base(account(), failedEmpty), .error)

        let stale = Severity.staleAfter(syncIntervalMinutes: 15)
        XCTAssertEqual(CreditSeverity.aged(base: .low, fetchedAt: now, now: now, staleAfter: stale), .low)
        XCTAssertEqual(CreditSeverity.aged(base: .low, fetchedAt: now, now: now.addingTimeInterval(stale), staleAfter: stale), .stale)
        XCTAssertEqual(CreditSeverity.aged(base: .error, fetchedAt: now, now: now.addingTimeInterval(stale), staleAfter: stale), .error)
    }

    func testCurrencyFormatting() {
        XCTAssertEqual(CreditFormat.string(dec("12.3"), currency: "USD", locale: usEnglish), "$12.30")
        XCTAssertEqual(CreditFormat.string(dec("0.004"), currency: "USD", locale: usEnglish), "<$0.01")
        XCTAssertEqual(CreditFormat.string(0, currency: "USD", locale: usEnglish), "$0.00")
        XCTAssertEqual(CreditFormat.string(dec("-3"), currency: "USD", locale: usEnglish), "-$3.00")
        XCTAssertTrue(CreditFormat.string(110, currency: "CNY", locale: usEnglish).contains("110.00"))
        XCTAssertEqual(CreditFormat.string(dec("1234.5"), currency: "USD", locale: usEnglish), "$1,234.50")
        XCTAssertTrue(CreditFormat.summary(
            [CreditBalance(currency: "CNY", amount: 110), CreditBalance(currency: "USD", amount: 5)],
            locale: usEnglish).contains(" · $5.00"))
    }

    // MARK: - Storage

    func testBalancesRoundTripAsStringsAndOldNumbersStillDecode() throws {
        let balance = CreditBalance(currency: "USD", amount: dec("49.58894"), total: 100, label: "x")
        let data = try JSONEncoder().encode(balance)
        XCTAssertTrue(String(decoding: data, as: UTF8.self).contains(#""49.58894""#))
        XCTAssertEqual(try JSONDecoder().decode(CreditBalance.self, from: data), balance)
        XCTAssertEqual(
            try JSONDecoder().decode(CreditBalance.self, from: Data(#"{"currency":"USD","amount":4.5}"#.utf8)).amount,
            dec("4.5"))
    }

    func testBalanceDecodingRejectsAMissingOrInvalidAmount() throws {
        for body in [
            #"{"currency":"USD"}"#, #"{"currency":"USD","amount":"garbage"}"#,
            #"{"currency":"USD","amount":"12abc"}"#, #"{"currency":"USD","amount":true}"#,
        ] {
            XCTAssertThrowsError(try JSONDecoder().decode(CreditBalance.self, from: Data(body.utf8)), body)
        }
        let noTotal = try JSONDecoder().decode(CreditBalance.self, from: Data(#"{"currency":"USD","amount":"4"}"#.utf8))
        XCTAssertNil(noTotal.total)
    }

    func testThresholdFieldDisplaysAndParsesInOneLocale() {
        let german = Locale(identifier: "de_DE")
        XCTAssertEqual(CreditThresholdField.display("5.5", locale: german), "5,5")
        XCTAssertEqual(CreditThresholdField.display("5.5", locale: usEnglish), "5.5")
        XCTAssertEqual(CreditThresholdField.display(nil, locale: german), "")
        XCTAssertEqual(CreditThresholdField.parse("5,5", locale: german)?.value, dec("5.5"))
        XCTAssertEqual(CreditThresholdField.parse("5.5", locale: usEnglish)?.value, dec("5.5"))
        // The display of a stored figure parses back to it.
        XCTAssertEqual(CreditThresholdField.parse(CreditThresholdField.display("12.25", locale: german), locale: german)?.value, dec("12.25"))
        // Reject partially parsed or nonnumeric input, including 5.5 under de_DE.
        for text in ["5.5", "5,5x", "abc", "0", "-1", "1,2,3"] {
            XCTAssertNil(CreditThresholdField.parse(text, locale: german), text)
        }
        XCTAssertNil(CreditThresholdField.parse("5,5", locale: usEnglish))
        XCTAssertNotNil(CreditThresholdField.parse("  ", locale: german))
        XCTAssertNil(CreditThresholdField.parse("  ", locale: german)?.value)
    }

    func testAFailedSyncKeepsThePreviousBalancesAndFetchTime() async throws {
        let repository = CreditRepository(directory: directory)
        try await repository.upsert(account())
        try await repository.record(accountID: "a", balances: [CreditBalance(currency: "USD", amount: 9)], error: nil, at: now)
        try await repository.record(accountID: "a", balances: nil, error: "The key was rejected", at: now.addingTimeInterval(900))

        let snapshot = try await firstSnapshot(repository)
        XCTAssertEqual(snapshot.status, .failed)
        XCTAssertEqual(snapshot.balances.map(\.amount), [9])
        XCTAssertEqual(snapshot.fetchedAt, now)
        XCTAssertEqual(snapshot.lastAttemptAt, now.addingTimeInterval(900))
        XCTAssertEqual(snapshot.error, "The key was rejected")

        // And it survives a reload from disk.
        let reloaded = try await firstSnapshot(CreditRepository(directory: directory))
        XCTAssertEqual(reloaded, snapshot)
    }

    func testAnUnreadableRegisterIsNeverOverwritten() async throws {
        let file = directory.appendingPathComponent(CreditRepository.fileName)
        try Data("not json".utf8).write(to: file)
        let repository = CreditRepository(directory: directory)
        do {
            try await repository.upsert(account())
            XCTFail("expected a refusal")
        } catch { XCTAssertEqual(error as? AccountStoreError, .corrupt) }
        XCTAssertEqual(try Data(contentsOf: file), Data("not json".utf8))
    }

    func testAccountsKeepTheirOrderAndNeverHoldAKey() async throws {
        let repository = CreditRepository(directory: directory)
        for id in ["x", "y", "z"] {
            try await repository.upsert(CreditAccount(
                id: id, provider: .fal, label: id, credentialReference: "credit:\(id)", createdAt: now))
        }
        try await repository.upsert(CreditAccount(
            id: "x", provider: .fal, label: "renamed", credentialReference: "credit:x", createdAt: now))
        let accounts = await repository.accounts()
        XCTAssertEqual(accounts.map(\.id), ["x", "y", "z"])
        XCTAssertEqual(accounts.first?.label, "renamed")
        try await repository.remove(id: "y")
        let remaining = await repository.accounts()
        XCTAssertEqual(remaining.map(\.id), ["x", "z"])
    }

    // MARK: - The container

    private func container(_ routes: [(String, [(Int, String)])], clock: @escaping @Sendable () -> Date)
        -> (UsageLimitsContainer, InMemoryCredentialStore, Transport) {
        let store = InMemoryCredentialStore()
        let transport = Transport(routes)
        return (UsageLimitsContainer(directory: directory, credentials: store, transport: transport, now: clock),
                store, transport)
    }

    // `await` cannot sit inside XCTUnwrap's autoclosure.
    private func firstEntry(_ container: UsageLimitsContainer) async throws -> CreditEntry {
        let entries = await container.credits()
        return try XCTUnwrap(entries.first)
    }

    private func firstSnapshot(_ repository: CreditRepository) async throws -> CreditSnapshot {
        let entries = await repository.entries()
        return try XCTUnwrap(entries.first?.snapshot)
    }

    func testAddingAKeyProvesItStoresItInTheKeychainOnlyAndPublishesTheWidgetFile() async throws {
        let (subject, store, _) = container([
            ("api.deepseek.com", [(200, #"{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"8.00"}]}"#)]),
        ], clock: { [now] in now })

        let added = try await subject.addCreditAccount(provider: .deepseek, key: "  ds-secret  ", label: " Work ")

        XCTAssertEqual(added.label, "Work")
        let stored = try await store.load(reference: added.credentialReference)
        XCTAssertEqual(stored?.accessToken, "ds-secret")
        // The key never reaches either file in the container.
        for name in [CreditRepository.fileName, GlanceSnapshotCodec.fileName] {
            let text = String(decoding: try Data(contentsOf: directory.appendingPathComponent(name)), as: UTF8.self)
            XCTAssertFalse(text.contains("ds-secret"), name)
        }
        guard case .loaded(let glance) = GlanceSnapshotCodec.loadCredits(fromDirectory: directory) else {
            return XCTFail("the widget file carries no credits")
        }
        XCTAssertEqual(glance.credits.map(\.title), ["Work"])
        XCTAssertEqual(glance.credits.first?.balances.first?.amount, 8)
        // A user with only credit accounts is "empty" to the usage widgets and loaded to this one.
        XCTAssertEqual(GlanceSnapshotCodec.load(fromDirectory: directory), .empty)
    }

    func testARejectedKeyLeavesNoAccountAndNoKey() async throws {
        let (subject, store, _) = container([("api.deepseek.com", [(401, "{}")])], clock: { [now] in now })
        do {
            _ = try await subject.addCreditAccount(provider: .deepseek, key: "bad")
            XCTFail("expected a rejection")
        } catch { XCTAssertEqual(error as? CreditError, .rejected(401)) }
        let entries = await subject.credits()
        XCTAssertTrue(entries.isEmpty)
        let references = try await store.allReferences()
        XCTAssertTrue(references.isEmpty)
    }

    func testAddingAnXaiKeyStoresTheResolvedTeam() async throws {
        let (subject, _, _) = container([
            ("validation", [(200, #"{"scope":"SCOPE_TEAM","scopeId":"team-9"}"#)]),
            ("team-9/prepaid", [(200, #"{"total":{"val":"-1000"}}"#)]),
        ], clock: { [now] in now })
        let added = try await subject.addCreditAccount(provider: .xai, key: "mk")
        XCTAssertEqual(added.teamID, "team-9")

        let org = container([("validation", [(200, #"{"scope":"SCOPE_ORGANIZATION","scopeId":"o"}"#)])], clock: { [now] in now }).0
        do {
            _ = try await org.addCreditAccount(provider: .xai, key: "mk")
            XCTFail("expected teamIDRequired")
        } catch { XCTAssertEqual(error as? CreditError, .teamIDRequired) }
    }

    func testRefreshUpdatesCreditsAndAFailureKeepsTheLastBalance() async throws {
        let transport = Transport([
            ("api.novita.ai", [
                (200, #"{"availableBalance":"500000"}"#),
                (200, #"{"availableBalance":"250000"}"#),
                (401, "{}"),
            ]),
        ])
        let store = InMemoryCredentialStore()
        let subject = UsageLimitsContainer(directory: directory, credentials: store, transport: transport, now: { [now] in now })

        try await subject.addCreditAccount(provider: .novita, key: "nv")
        // No usage accounts at all: the credit sync must still run.
        _ = try await subject.refresh()
        var entry = try await firstEntry(subject)
        XCTAssertEqual(entry.snapshot?.balances.first?.amount, 25)
        XCTAssertEqual(entry.snapshot?.status, .ok)

        _ = try await subject.refresh()
        entry = try await firstEntry(subject)
        XCTAssertEqual(entry.snapshot?.status, .failed)
        XCTAssertEqual(entry.snapshot?.balances.first?.amount, 25)
        XCTAssertEqual(entry.snapshot?.error, CreditError.rejected(401).errorDescription)
    }

    func testRemovingDeletesTheKeyAndTheAccount() async throws {
        let (subject, store, _) = container([
            ("api.fal.ai", [(200, #"{"credits":{"current_balance":3,"currency":"USD"}}"#)]),
        ], clock: { [now] in now })
        let added = try await subject.addCreditAccount(provider: .fal, key: "fk")
        try await subject.removeCreditAccount(id: added.id)
        let entries = await subject.credits()
        XCTAssertTrue(entries.isEmpty)
        let key = try await store.load(reference: added.credentialReference)
        XCTAssertNil(key)
        XCTAssertEqual(GlanceSnapshotCodec.loadCredits(fromDirectory: directory), .empty)
    }

    func testEditingTheThresholdChangesTheSeverity() async throws {
        let (subject, _, _) = container([
            ("api.fal.ai", [(200, #"{"credits":{"current_balance":8,"currency":"USD"}}"#)]),
        ], clock: { [now] in now })
        let added = try await subject.addCreditAccount(provider: .fal, key: "fk")
        var entry = try await firstEntry(subject)
        XCTAssertEqual(CreditSeverity.base(entry.account, entry.snapshot), .medium)

        try await subject.updateCreditAccount(id: added.id, label: "Fal", lowThreshold: 20)
        entry = try await firstEntry(subject)
        XCTAssertEqual(entry.account.label, "Fal")
        XCTAssertEqual(CreditSeverity.base(entry.account, entry.snapshot), .low)
        // The balance survives the edit.
        XCTAssertEqual(entry.snapshot?.balances.first?.amount, 8)
    }

    // MARK: - The widget file

    func testASnapshotWrittenBeforeCreditsStillDecodes() {
        // The exact shape of a file written by a build that had no credits (no `credits`, no
        // `staleAfter`): it must still decode, or every widget blanks on the upgrade.
        let old = #"""
        {"accounts":[{"id":"codex:1","title":"Codex","rows":[],"severity":0}],
         "accountCount":1,"overallSeverity":0}
        """#
        let decoded = GlanceSnapshotCodec.decode(Data(old.utf8))
        XCTAssertEqual(decoded.accounts.map(\.id), ["codex:1"])
        XCTAssertEqual(decoded.credits, [])
        XCTAssertEqual(decoded.staleAfter, Severity.staleAfter)
    }

    func testCreditsRoundTripThroughTheCodecAndRankWorstFirst() throws {
        let entries = [
            CreditEntry(account: account(), snapshot: CreditSnapshot(
                accountID: "a", balances: [CreditBalance(currency: "USD", amount: 100)], fetchedAt: now)),
            CreditEntry(
                account: CreditAccount(id: "b", provider: .fal, label: "B", credentialReference: "credit:b", createdAt: now),
                snapshot: CreditSnapshot(accountID: "b", balances: [CreditBalance(currency: "USD", amount: 1)], fetchedAt: now)),
            CreditEntry(
                account: CreditAccount(id: "c", provider: .fal, label: "C", credentialReference: "credit:c", createdAt: now),
                snapshot: CreditSnapshot(accountID: "c", balances: [CreditBalance(currency: "USD", amount: 0)], fetchedAt: now)),
        ]
        let snapshot = GlanceSnapshot.empty.with(credits: GlanceModel.buildCredits(entries, now: now))
        let decoded = GlanceSnapshotCodec.decode(try GlanceSnapshotCodec.encode(snapshot))
        XCTAssertEqual(decoded, snapshot)
        XCTAssertEqual(decoded.creditsByUrgency(at: now).map(\.id), ["c", "b", "a"])
        // Aged at render time: a day later everything reads stale, not healthy.
        let later = now.addingTimeInterval(86_400)
        XCTAssertTrue(decoded.creditsByUrgency(at: later).allSatisfy { $0.severity(at: later, staleAfter: decoded.staleAfter) == .stale })
    }

    func testEndpointsAreTheDocumentedOnes() {
        XCTAssertEqual(ProviderEndpoints.Credits.moonshotBalance(.intl), "https://api.moonshot.ai/v1/users/me/balance")
        XCTAssertEqual(ProviderEndpoints.Credits.novitaBalance, "https://api.novita.ai/openapi/v1/billing/balance/detail")
        XCTAssertEqual(CreditProviderID.allCases.map(\.rawValue), ["openrouter", "deepseek", "moonshot", "xai", "fal", "novita"])
        XCTAssertEqual(CreditProviderID.notPossible.map(\.id), ["anthropic", "openai"])
        XCTAssertEqual(CreditProviderID.xai.consoleURL, "https://console.x.ai/team/default/api-keys")
    }

    func testEveryCreditProviderHasItsOwnBundledIcon() throws {
        let assets = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("UsageLimits/Sources/Assets.xcassets")
        XCTAssertEqual(Set(CreditProviderID.allCases.map(\.iconAssetName)).count, 6)
        for provider in CreditProviderID.allCases {
            XCTAssertFalse(provider.iconAssetName.isEmpty)
            let directory = assets.appendingPathComponent("\(provider.iconAssetName).imageset")
            let contents = try XCTUnwrap(JSONSerialization.jsonObject(
                with: Data(contentsOf: directory.appendingPathComponent("Contents.json"))) as? [String: Any])
            let images = try XCTUnwrap(contents["images"] as? [[String: String]])
            let filename = try XCTUnwrap(images.first?["filename"])
            XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appendingPathComponent(filename).path))
        }
    }
}
