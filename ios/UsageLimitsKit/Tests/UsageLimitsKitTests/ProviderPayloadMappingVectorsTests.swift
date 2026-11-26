import XCTest
@testable import UsageLimitsKit

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The shape of the shared provider payload vectors.
///
/// The file pins how a documented response becomes credit rows or usage windows, and which
/// failures stay unknown rather than zero. Both parsers read it — iOS here, Android in
/// `ProviderPayloadMappingVectorsTest` — so a section this file loses or a field one side reads
/// differently has to fail a suite rather than pass as an empty test.
///
/// Only the shape is checked; each slice adds one test method per section that maps the same cases
/// through its parser.
final class ProviderPayloadMappingVectorsTests: XCTestCase {

    private let sections = [
        "openrouterKeyWindow", "vercelAiGateway", "deepinfra", "tokenrouter", "ollama", "minimax",
    ]

    private static let file = SharedVectors.file("provider-payload-mapping.json")

    private func root() throws -> [String: Any] {
        let data = try Data(contentsOf: Self.file)
        let object = try JSONSerialization.jsonObject(with: data)
        return try XCTUnwrap(object as? [String: Any], "the provider payload vector file must be one JSON object")
    }

    private func cases(_ section: String) throws -> [[String: Any]] {
        let value = try XCTUnwrap(try root()[section] as? [Any], "\(section) must be a JSON array")
        return try value.map { try XCTUnwrap($0 as? [String: Any], "\(section) holds only JSON objects") }
    }

    private func string(_ where_: String, _ value: Any?) throws -> String {
        try XCTUnwrap(value as? String, "\(where_) must be a JSON string")
    }

    private func decimalText(_ where_: String, _ value: Any?) throws {
        let text = try string(where_, value)
        XCTAssertNotNil(text.range(of: #"^-?\d+(\.\d+)?$"#, options: .regularExpression),
                        "\(where_) must be a decimal string, was \"\(text)\"")
        XCTAssertNotNil(Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")),
                        "\(where_) must parse as a decimal, was \"\(text)\"")
    }

    private func instant(_ where_: String, _ value: Any?) throws {
        let text = try string(where_, value)
        XCTAssertNotNil(Self.instantFormatter.date(from: text),
                        "\(where_) must be an ISO-8601 UTC instant, was \"\(text)\"")
    }

    /// A JSON value as the text a parser receives. A number stays a number: `true` serialized
    /// back is text, not digits, and a case's body can be handed over whole.
    private func jsonText(_ value: Any) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]) else {
            return nil
        }
        return String(decoding: data, as: UTF8.self)
    }

    func testTheFileHoldsTheDescriptionAndExactlyTheSixSections() throws {
        let root = try root()
        XCTAssertEqual(Set(root.keys), Set(sections + ["description"]))
        XCTAssertFalse(try string("description", root["description"]).isEmpty)
    }

    func testEveryCaseCarriesANameAStatusABodyAndOneExpectForm() throws {
        for section in sections {
            for (index, vector) in try cases(section).enumerated() {
                let where_ = "\(section)[\(index)]"
                let allowed: Set<String> = ["name", "status", "body", "expect", "now"]
                let required: Set<String> = ["name", "status", "body", "expect"]
                XCTAssertTrue(allowed.isSuperset(of: vector.keys), "\(where_) has unexpected keys \(vector.keys)")
                XCTAssertTrue(Set(vector.keys).isSuperset(of: required),
                              "\(where_) is missing \(required.subtracting(vector.keys))")
                XCTAssertFalse(try string("\(where_).name", vector["name"]).isEmpty)
                let status = try XCTUnwrap(vector["status"], "\(where_).status is missing")
                XCTAssertNotNil(Int(try XCTUnwrap(jsonText(status), "\(where_).status is not a number")),
                                "\(where_).status must be a JSON integer")
                if let now = vector["now"] { try instant("\(where_).now", now) }
                let expect = try XCTUnwrap(vector["expect"] as? [String: Any],
                                           "\(where_).expect must be a JSON object")
                try checkExpect("\(where_).expect", expect)
            }
        }
    }

    func testBalancesCarryStringAmountsAndWindowsCarryAUsedPercentThatMayBeNull() throws {
        for section in sections {
            for (index, vector) in try cases(section).enumerated() {
                let expect = try XCTUnwrap(vector["expect"] as? [String: Any])
                if let balances = expect["balances"] as? [Any] {
                    for (row, element) in balances.enumerated() {
                        let where_ = "\(section)[\(index)].expect.balances[\(row)]"
                        let balance = try XCTUnwrap(element as? [String: Any], "\(where_) must be a JSON object")
                        let allowed: Set<String> = ["currency", "amount", "total", "resetAt", "usedPercent"]
                        XCTAssertTrue(allowed.isSuperset(of: balance.keys),
                                      "\(where_) has unexpected keys \(balance.keys)")
                        XCTAssertTrue(Set(balance.keys).isSuperset(of: ["currency", "amount"]),
                                      "\(where_) is missing \(Set(["currency", "amount"]).subtracting(balance.keys))")
                        _ = try string("\(where_).currency", balance["currency"])
                        try decimalText("\(where_).amount", balance["amount"])
                        if let total = balance["total"], !(total is NSNull) { try decimalText("\(where_).total", total) }
                        if let reset = balance["resetAt"], !(reset is NSNull) { try instant("\(where_).resetAt", reset) }
                        if let used = balance["usedPercent"], !(used is NSNull) {
                            let text = try XCTUnwrap(jsonText(used),
                                                     "\(where_).usedPercent must be a JSON number or null")
                            XCTAssertNotNil(Double(text), "\(where_).usedPercent must be a JSON number or null")
                        }
                    }
                }
                if let windows = expect["windows"] as? [Any] {
                    for (row, element) in windows.enumerated() {
                        let where_ = "\(section)[\(index)].expect.windows[\(row)]"
                        let window = try XCTUnwrap(element as? [String: Any], "\(where_) must be a JSON object")
                        XCTAssertEqual(Set(window.keys), ["id", "usedPercent", "resetAt"])
                        _ = try string("\(where_).id", window["id"])
                        if let used = window["usedPercent"], !(used is NSNull) {
                            let text = try XCTUnwrap(jsonText(used), "\(where_).usedPercent must be a JSON number or null")
                            XCTAssertNotNil(Double(text), "\(where_).usedPercent must be a JSON number or null")
                        }
                        if let reset = window["resetAt"], !(reset is NSNull) { try instant("\(where_).resetAt", reset) }
                    }
                }
            }
        }
    }

    /// The OpenRouter key window: one row per key, its reset at the next UTC boundary of
    /// `limit_reset`, and its used share clamped to 0...100. A limit with no remainder must stay
    /// unknown rather than read as an empty balance.
    func testAnOpenRouterKeyLimitCarriesItsPeriodResetAndClampedUsedShare() throws {
        for (index, vector) in try cases("openrouterKeyWindow").enumerated() {
            let where_ = "openrouterKeyWindow[\(index)]"
            let nowValue = try XCTUnwrap(vector["now"], "\(where_) needs a now instant")
            let now = try XCTUnwrap(Self.instantFormatter.date(from: try string("\(where_).now", nowValue)))
            let body = try XCTUnwrap(vector["body"] as? [String: Any], "\(where_).body must be a JSON object")
            let expect = try XCTUnwrap(vector["expect"] as? [String: Any])
            let balances: [CreditBalance]
            do {
                balances = try OpenRouterCreditParser.parseKey(body, now: now)
            } catch {
                let creditError = try XCTUnwrap(error as? CreditError, "\(where_): \(error)")
                XCTAssertEqual(Self.errorKind(creditError),
                               try string("\(where_).expect.error", expect["error"]),
                               "\(where_): \(error)")
                continue
            }
            XCTAssertNil(expect["error"], "\(where_): the parse succeeded but the case expects an error")
            let rows = try XCTUnwrap(expect["balances"] as? [Any], "\(where_).expect.balances")
            XCTAssertEqual(rows.count, balances.count, "\(where_): row count")
            for (row, element) in rows.enumerated() {
                let at = "\(where_).expect.balances[\(row)]"
                let expected = try XCTUnwrap(element as? [String: Any], "\(at) must be a JSON object")
                let balance = balances[row]
                XCTAssertEqual(balance.currency, try string("\(at).currency", expected["currency"]), at)
                XCTAssertEqual(balance.amount,
                               Self.decimal(try string("\(at).amount", expected["amount"])), at)
                if let total = expected["total"], !(total is NSNull) {
                    XCTAssertEqual(balance.total, Self.decimal(try string("\(at).total", total)), at)
                } else {
                    XCTAssertNil(balance.total, at)
                }
                if let reset = expected["resetAt"], !(reset is NSNull) {
                    XCTAssertEqual(balance.resetsAt,
                                   Self.instantFormatter.date(from: try string("\(at).resetAt", reset)), at)
                } else {
                    XCTAssertNil(balance.resetsAt, at)
                }
                if let used = expected["usedPercent"], !(used is NSNull) {
                    let text = try XCTUnwrap(jsonText(used), "\(at).usedPercent must be a JSON number")
                    let expectedUsed = try XCTUnwrap(Double(text))
                    let percent = try XCTUnwrap(balance.remainingPercent, "\(at) has no bar to measure")
                    XCTAssertEqual(100 - percent, expectedUsed, accuracy: 1e-9, at)
                } else {
                    XCTAssertNil(balance.remainingPercent, "\(at): a row without a used share has no bar")
                }
            }
        }
    }

    /// The Vercel AI Gateway credits call: decimal strings only, an optional Token Pass row with
    /// its reset, and 401/403 as the documented failures. A JSON number must stay unknown rather
    /// than be rounded into a balance, and a null Token Pass must not become a second zero.
    func testVercelAiGatewayReadsDecimalStringsAndRefusesTheRest() async throws {
        for (index, vector) in try cases("vercelAiGateway").enumerated() {
            let where_ = "vercelAiGateway[\(index)]"
            let status = try XCTUnwrap(Int(try XCTUnwrap(jsonText(vector["status"] as Any))),
                                       "\(where_).status must be a JSON integer")
            let body = try XCTUnwrap(jsonText(vector["body"] as Any), "\(where_).body must be JSON")
            let expect = try XCTUnwrap(vector["expect"] as? [String: Any])
            let client = VercelCreditClient(httpClient: UsageHTTPClient(
                transport: StatusTransport(status: status, body: body), maxRetries: 0))
            let balances: [CreditBalance]
            do {
                balances = try await client.fetchBalances(key: "vck-test", attributes: [:])
            } catch {
                let creditError = try XCTUnwrap(error as? CreditError, "\(where_): \(error)")
                XCTAssertEqual(Self.errorKind(creditError),
                               try string("\(where_).expect.error", expect["error"]),
                               "\(where_): \(error)")
                continue
            }
            XCTAssertNil(expect["error"], "\(where_): the read succeeded but the case expects an error")
            let rows = try XCTUnwrap(expect["balances"] as? [Any], "\(where_).expect.balances")
            XCTAssertEqual(rows.count, balances.count, "\(where_): row count")
            for (row, element) in rows.enumerated() {
                let at = "\(where_).expect.balances[\(row)]"
                let expected = try XCTUnwrap(element as? [String: Any], "\(at) must be a JSON object")
                let balance = balances[row]
                XCTAssertEqual(balance.currency, try string("\(at).currency", expected["currency"]), at)
                XCTAssertEqual(balance.amount, Self.decimal(try string("\(at).amount", expected["amount"])), at)
                if let total = expected["total"], !(total is NSNull) {
                    XCTAssertEqual(balance.total, Self.decimal(try string("\(at).total", total)), at)
                } else {
                    XCTAssertNil(balance.total, at)
                }
                if let reset = expected["resetAt"], !(reset is NSNull) {
                    XCTAssertEqual(balance.resetsAt,
                                   Self.instantFormatter.date(from: try string("\(at).resetAt", reset)), at)
                } else {
                    XCTAssertNil(balance.resetsAt, at)
                }
                XCTAssertNil(balance.remainingPercent, "\(at): no row of this provider draws a bar")
            }
        }
    }

    /// DeepInfra's billing checklist: `stripe_balance` is signed cents where NEGATIVE is money left
    /// to spend, so a positive (owed) figure reads as a negative row. Each live scoped credit is one
    /// more row, an expired one adds none, and `limit` is a spending cap that never becomes a
    /// balance. A missing checklist and an unreadable figure must stay unknown, never zero.
    func testDeepinfraReadsTheSignedPrepaidBalanceAndTheLiveScopedCredits() async throws {
        for (index, vector) in try cases("deepinfra").enumerated() {
            let where_ = "deepinfra[\(index)]"
            let status = try XCTUnwrap(Int(try XCTUnwrap(jsonText(vector["status"] as Any))),
                                       "\(where_).status must be a JSON integer")
            let body = try XCTUnwrap(jsonText(vector["body"] as Any), "\(where_).body must be JSON")
            let expect = try XCTUnwrap(vector["expect"] as? [String: Any])
            let client = DeepInfraCreditClient(httpClient: UsageHTTPClient(
                transport: StatusTransport(status: status, body: body), maxRetries: 0))
            let balances: [CreditBalance]
            do {
                balances = try await client.fetchBalances(key: "di-test", attributes: [:])
            } catch {
                let creditError = try XCTUnwrap(error as? CreditError, "\(where_): \(error)")
                XCTAssertEqual(Self.errorKind(creditError),
                               try string("\(where_).expect.error", expect["error"]),
                               "\(where_): \(error)")
                continue
            }
            XCTAssertNil(expect["error"], "\(where_): the read succeeded but the case expects an error")
            let rows = try XCTUnwrap(expect["balances"] as? [Any], "\(where_).expect.balances")
            XCTAssertEqual(rows.count, balances.count, "\(where_): row count")
            for (row, element) in rows.enumerated() {
                let at = "\(where_).expect.balances[\(row)]"
                let expected = try XCTUnwrap(element as? [String: Any], "\(at) must be a JSON object")
                let balance = balances[row]
                XCTAssertEqual(balance.currency, try string("\(at).currency", expected["currency"]), at)
                XCTAssertEqual(balance.amount, Self.decimal(try string("\(at).amount", expected["amount"])), at)
                if let total = expected["total"], !(total is NSNull) {
                    XCTAssertEqual(balance.total, Self.decimal(try string("\(at).total", total)), at)
                } else {
                    XCTAssertNil(balance.total, at)
                }
                XCTAssertNil(balance.remainingPercent, "\(at): no row of this provider draws a bar")
            }
        }
    }

    /// TokenRouter's management wallet: the topped-up balance plus the currently valid voucher
    /// balance is the amount, and no cap is documented, so there is no bar. A wallet without a
    /// topped-up balance must stay unknown rather than read as the vouchers alone.
    func testTokenrouterAddsTheValidVouchersToTheToppedUpBalance() async throws {
        for (index, vector) in try cases("tokenrouter").enumerated() {
            let where_ = "tokenrouter[\(index)]"
            let status = try XCTUnwrap(Int(try XCTUnwrap(jsonText(vector["status"] as Any))),
                                       "\(where_).status must be a JSON integer")
            let body = try XCTUnwrap(jsonText(vector["body"] as Any), "\(where_).body must be JSON")
            let expect = try XCTUnwrap(vector["expect"] as? [String: Any])
            let client = TokenRouterCreditClient(httpClient: UsageHTTPClient(
                transport: StatusTransport(status: status, body: body), maxRetries: 0))
            let balances: [CreditBalance]
            do {
                balances = try await client.fetchBalances(key: "tr-test", attributes: [:])
            } catch {
                let creditError = try XCTUnwrap(error as? CreditError, "\(where_): \(error)")
                XCTAssertEqual(Self.errorKind(creditError),
                               try string("\(where_).expect.error", expect["error"]),
                               "\(where_): \(error)")
                continue
            }
            XCTAssertNil(expect["error"], "\(where_): the read succeeded but the case expects an error")
            let rows = try XCTUnwrap(expect["balances"] as? [Any], "\(where_).expect.balances")
            XCTAssertEqual(rows.count, balances.count, "\(where_): row count")
            for (row, element) in rows.enumerated() {
                let at = "\(where_).expect.balances[\(row)]"
                let expected = try XCTUnwrap(element as? [String: Any], "\(at) must be a JSON object")
                let balance = balances[row]
                XCTAssertEqual(balance.currency, try string("\(at).currency", expected["currency"]), at)
                XCTAssertEqual(balance.amount, Self.decimal(try string("\(at).amount", expected["amount"])), at)
                if let total = expected["total"], !(total is NSNull) {
                    XCTAssertEqual(balance.total, Self.decimal(try string("\(at).total", total)), at)
                } else {
                    XCTAssertNil(balance.total, at)
                }
                XCTAssertNil(balance.remainingPercent, "\(at): the wallet states no cap, so the row draws no bar")
            }
        }
    }

    /// One scripted reply, so a vector can pin a status as well as a body.
    private actor StatusTransport: HTTPTransport {
        let status: Int
        let body: String

        init(status: Int, body: String) {
            self.status = status
            self.body = body
        }

        func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
            let response = HTTPURLResponse(
                url: request.url ?? URL(string: "https://example.invalid")!,
                statusCode: status, httpVersion: "HTTP/1.1", headerFields: [:])!
            return (Data(body.utf8), response)
        }
    }

    /// The failure kinds the vectors name; a malformed payload is unknown, never zero.
    private static func errorKind(_ error: CreditError) -> String {
        switch error {
        case .rejected(let status): return status == 403 ? "forbidden" : "invalidKey"
        // Vercel's 403 asks for a team id instead of naming a rejected key; it is still a 403.
        case .teamDenied: return "forbidden"
        // DeepInfra's 403 names the scope the key lacks; it is still a 403.
        case .missingScope: return "forbidden"
        default: return "unknown"
        }
    }

    private static func decimal(_ text: String) -> Decimal? {
        Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))
    }

    private func checkExpect(_ where_: String, _ expect: [String: Any]) throws {
        let forms: Set<String> = ["balances", "windows", "error"]
        XCTAssertTrue(forms.isSuperset(of: expect.keys), "\(where_) has unexpected keys \(expect.keys)")
        XCTAssertFalse(expect.isEmpty, "\(where_) states none of balances, windows or error")
        if expect["error"] != nil {
            XCTAssertEqual(Set(expect.keys), ["error"], "\(where_): error cannot accompany balances or windows")
            XCTAssertTrue(["invalidKey", "forbidden", "unknown"].contains(try string("\(where_).error", expect["error"])),
                          "\(where_).error must be invalidKey, forbidden or unknown")
        }
        if let balances = expect["balances"] { XCTAssertTrue(balances is [Any], "\(where_).balances must be an array") }
        if let windows = expect["windows"] { XCTAssertTrue(windows is [Any], "\(where_).windows must be an array") }
    }

    private static let instantFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()
}
