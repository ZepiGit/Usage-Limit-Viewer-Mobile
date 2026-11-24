import XCTest
@testable import UsageLimitsKit

final class OverviewCreditSummaryTests: XCTestCase {
    func testSharedVectorsChooseTheOriginalBalanceAndCountTrackedAccounts() throws {
        let vectors = try Self.parse(Data(contentsOf: SharedVectors.file("overview-credits-summary.json")))
        for vector in vectors {
            let now = date(vector.now)
            let summary = OverviewCreditSummary(entries: vector.accounts.map { $0.entry(now: now) },
                now: now, staleAfter: TimeInterval(vector.staleAfterMs) / 1000)
            XCTAssertEqual(summary.count, vector.expected.count, vector.name)
            guard let expected = vector.expected.winner else {
                XCTAssertNil(summary.winner, vector.name)
                continue
            }
            guard let winner = summary.winner else {
                XCTFail("Expected a winning balance: \(vector.name)")
                continue
            }
            XCTAssertEqual(winner.accountID, expected.accountId, vector.name)
            XCTAssertEqual(winner.balanceIndex, expected.balanceIndex, vector.name)
            XCTAssertEqual(winner.balance.currency, expected.currency, vector.name)
            XCTAssertEqual(winner.balance.amount, expected.amount.value, vector.name)
            let account = try XCTUnwrap(vector.accounts.first { $0.id == expected.accountId })
            let balance = try XCTUnwrap(account.snapshot?.balances[expected.balanceIndex].balance)
            XCTAssertEqual(winner.balance, balance, vector.name)
            XCTAssertEqual(winner.title, "Label \(expected.accountId)", vector.name)
        }
    }

    func testReaderAcceptsTheValidFixtureAndExplicitNulls() throws {
        let valid = try Self.parse(Data(Self.valid.utf8))
        XCTAssertEqual(valid.count, 1)
        XCTAssertEqual(valid.first?.expected.winner?.accountId, "a")
        let absent = Self.valid
            .replacingOccurrences(of: #""snapshot":{"fetchedAt":1000,"status":"ok","balances":[{"currency":"USD","amount":"1.25","total":null,"unlimited":false}]}"#,
                with: #""snapshot":null"#)
            .replacingOccurrences(of: #""winner":{"accountId":"a","balanceIndex":0,"currency":"USD","amount":"1.25"}"#,
                with: #""winner":null"#)
        let vector = try XCTUnwrap(Self.parse(Data(absent.utf8)).first)
        XCTAssertNil(vector.accounts.first?.snapshot)
        XCTAssertNil(vector.expected.winner)
        XCTAssertEqual(vector.expected.count, 1)
    }

    func testTheReaderRejectsEmptyMalformedAndDuplicateCaseLists() throws {
        for json in ["", "not json", "[]", "{}",
            #"{"description":"rules","schema":"schema","vectors":[]}"#,
            Self.valid.replacingOccurrences(of: #""name":"one""#, with: #""name":"""#),
        ] {
            XCTAssertThrowsError(try Self.parse(Data(json.utf8)), json)
        }
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(Self.valid.utf8)) as? [String: Any])
        let vectors = try XCTUnwrap(root["vectors"] as? [[String: Any]])
        var duplicate = root
        duplicate["vectors"] = vectors + vectors
        XCTAssertThrowsError(try Self.parse(JSONSerialization.data(withJSONObject: duplicate)))
    }

    func testEveryNestedObjectRejectsMissingOrUnknownKeys() throws {
        for key in ["description", "schema", "vectors", "name", "now", "staleAfterMs", "accounts", "expected",
                    "id", "customThreshold", "snapshot", "fetchedAt", "status", "balances", "currency", "amount",
                    "total", "unlimited", "count", "winner", "accountId", "balanceIndex"] {
            let renamed = Self.valid.replacingOccurrences(of: "\"\(key)\":", with: "\"renamed-\(key)\":")
            XCTAssertThrowsError(try Self.parse(Data(renamed.utf8)), key)
        }
        for fragment in [#""customThreshold":null,"#, #""total":null,"#] {
            XCTAssertThrowsError(try Self.parse(Data(Self.valid.replacingOccurrences(of: fragment, with: "").utf8)), fragment)
        }
        for fragment in [
            #","snapshot":{"fetchedAt":1000,"status":"ok","balances":[{"currency":"USD","amount":"1.25","total":null,"unlimited":false}]}"#,
            #", "winner":{"accountId":"a","balanceIndex":0,"currency":"USD","amount":"1.25"}"#,
        ] {
            let missing = Self.valid.replacingOccurrences(of: fragment, with: "")
            XCTAssertThrowsError(try Self.parse(Data(missing.utf8)), fragment)
        }
        let extra = Self.valid.replacingOccurrences(of: #""count":1"#, with: #""count":1,"extra":true"#)
        XCTAssertThrowsError(try Self.parse(Data(extra.utf8)))
    }

    func testMoneyMustBeAnExactDecimalString() throws {
        for invalid in [#""""#, #"" 1.25""#, #""1.25 ""#, #""1,25""#, #""1e0""#, #""+1""#,
                        #"".5""#, #""1.""#, #""NaN""#, #""Infinity""#, #""1x""#, #""١""#, "1.25", "true", "null"] {
            let json = Self.valid.replacingOccurrences(of: #""amount":"1.25""#, with: "\"amount\":\(invalid)")
            XCTAssertThrowsError(try Self.parse(Data(json.utf8)), invalid)
        }
        for key in ["customThreshold", "total"] {
            for invalid in ["1.25", #""""#] {
                let json = Self.valid.replacingOccurrences(of: "\"\(key)\":null", with: "\"\(key)\":\(invalid)")
                XCTAssertThrowsError(try Self.parse(Data(json.utf8)), "\(key): \(invalid)")
            }
        }
        for invalid in [#""""#, #""0""#, #""-1""#] {
            let json = Self.valid.replacingOccurrences(of: #""customThreshold":null"#, with: "\"customThreshold\":\(invalid)")
            XCTAssertThrowsError(try Self.parse(Data(json.utf8)), invalid)
        }
        let exact = Self.valid.replacingOccurrences(of: "1.25", with: "1.000000000000000001")
        let vector = try XCTUnwrap(Self.parse(Data(exact.utf8)).first)
        XCTAssertEqual(vector.expected.winner?.amount.value,
            Decimal(string: "1.000000000000000001", locale: Locale(identifier: "en_US_POSIX")))
        XCTAssertNotEqual(vector.expected.winner?.amount.value, Decimal(1))
    }

    func testInvalidInputsAndUnreferencedExpectedResultsAreRejected() {
        for (old, new) in [
            (#""id":"a""#, #""id":"""#),
            (#""staleAfterMs":3600000"#, #""staleAfterMs":0"#),
            (#""staleAfterMs":3600000"#, #""staleAfterMs":-1"#),
            (#""status":"ok""#, #""status":"unknown""#),
            (#""currency":"USD""#, #""currency":"usd""#),
            (#""currency":"USD""#, #""currency":"""#),
            (#""count":1"#, #""count":0"#),
            (#""accountId":"a""#, #""accountId":"missing""#),
            (#""balanceIndex":0"#, #""balanceIndex":-1"#),
            (#""balanceIndex":0"#, #""balanceIndex":1"#),
            (#""winner":{"accountId":"a","balanceIndex":0,"currency":"USD","amount":"1.25"}"#,
             #""winner":{"accountId":"a","balanceIndex":0,"currency":"EUR","amount":"1.25"}"#),
            (#""winner":{"accountId":"a","balanceIndex":0,"currency":"USD","amount":"1.25"}"#,
             #""winner":{"accountId":"a","balanceIndex":0,"currency":"USD","amount":"2"}"#),
        ] {
            XCTAssertThrowsError(try Self.parse(Data(Self.valid.replacingOccurrences(of: old, with: new).utf8)), new)
        }
    }

    func testDuplicateAccountIDsAndWinnerWithoutASnapshotAreRejected() throws {
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(Self.valid.utf8)) as? [String: Any])
        var vectors = try XCTUnwrap(root["vectors"] as? [[String: Any]])
        var accounts = try XCTUnwrap(vectors[0]["accounts"] as? [[String: Any]])
        vectors[0]["accounts"] = accounts + accounts
        vectors[0]["expected"] = ["count": 2, "winner": NSNull()]
        root["vectors"] = vectors
        XCTAssertThrowsError(try Self.parse(JSONSerialization.data(withJSONObject: root)))

        accounts[0]["snapshot"] = NSNull()
        vectors = try XCTUnwrap((JSONSerialization.jsonObject(with: Data(Self.valid.utf8)) as? [String: Any])?["vectors"] as? [[String: Any]])
        vectors[0]["accounts"] = accounts
        root["vectors"] = vectors
        XCTAssertThrowsError(try Self.parse(JSONSerialization.data(withJSONObject: root)))
    }

    private static let valid = #"""
        {"description":"rules","schema":"schema","vectors":[{"name":"one","now":2000,"staleAfterMs":3600000,
         "accounts":[{"id":"a","customThreshold":null,"snapshot":{"fetchedAt":1000,"status":"ok","balances":[{"currency":"USD","amount":"1.25","total":null,"unlimited":false}]}}],
         "expected":{"count":1, "winner":{"accountId":"a","balanceIndex":0,"currency":"USD","amount":"1.25"}}}]}
        """#

    private static func parse(_ data: Data) throws -> [Vector] {
        let vectors = try SharedVectors.parse(VectorFile.self, from: data).vectors
        guard !vectors.isEmpty, Set(vectors.map(\.name)).count == vectors.count else {
            throw VectorFileError(description: "expected nonempty, uniquely named credit summary vectors")
        }
        return vectors
    }

    private struct VectorFile: Decodable {
        let vectors: [Vector]

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["description", "schema", "vectors"])
            let c = try decoder.container(keyedBy: Keys.self)
            _ = try c.decode(String.self, forKey: Keys("description"))
            _ = try c.decode(String.self, forKey: Keys("schema"))
            vectors = try c.decode([Vector].self, forKey: Keys("vectors"))
        }
    }

    private struct Vector: Decodable {
        let name: String
        let now: Int64
        let staleAfterMs: Int64
        let accounts: [Account]
        let expected: Expected

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["name", "now", "staleAfterMs", "accounts", "expected"])
            let c = try decoder.container(keyedBy: Keys.self)
            name = try c.decode(String.self, forKey: Keys("name"))
            now = try c.decode(Int64.self, forKey: Keys("now"))
            staleAfterMs = try c.decode(Int64.self, forKey: Keys("staleAfterMs"))
            accounts = try c.decode([Account].self, forKey: Keys("accounts"))
            expected = try c.decode(Expected.self, forKey: Keys("expected"))
            guard !name.isEmpty, staleAfterMs > 0, Set(accounts.map(\.id)).count == accounts.count,
                  expected.count == accounts.count else {
                throw VectorFileError(description: "invalid credit summary vector \(name)")
            }
            if let winner = expected.winner {
                guard let account = accounts.first(where: { $0.id == winner.accountId }),
                      let balances = account.snapshot?.balances, balances.indices.contains(winner.balanceIndex),
                      balances[winner.balanceIndex].balance.currency == winner.currency,
                      balances[winner.balanceIndex].balance.amount == winner.amount.value else {
                    throw VectorFileError(description: "winner does not reference its original balance in \(name)")
                }
            }
        }
    }

    private struct Account: Decodable {
        let id: String
        let customThreshold: Money?
        let snapshot: Snapshot?

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["id", "customThreshold", "snapshot"])
            let c = try decoder.container(keyedBy: Keys.self)
            id = try c.decode(String.self, forKey: Keys("id"))
            customThreshold = try c.decodeIfPresent(Money.self, forKey: Keys("customThreshold"))
            snapshot = try c.decodeIfPresent(Snapshot.self, forKey: Keys("snapshot"))
            guard !id.isEmpty, customThreshold.map({ $0.value > 0 }) ?? true else {
                throw VectorFileError(description: "invalid credit account or threshold")
            }
        }

        func entry(now: Date) -> CreditEntry {
            CreditEntry(account: CreditAccount(id: id, provider: .deepseek, label: "Label \(id)",
                credentialReference: "credit:\(id)",
                attributes: customThreshold.map { [CreditAccount.lowThresholdKey: $0.text] } ?? [:],
                createdAt: date(0)), snapshot: snapshot.map {
                    CreditSnapshot(accountID: id, balances: $0.balances.map(\.balance), fetchedAt: date($0.fetchedAt),
                        lastAttemptAt: $0.status == .ok ? nil : now, status: $0.status == .ok ? .ok : .failed,
                        error: $0.status == .paused ? StoredMessage.rateLimitedPauseMessage : ($0.status == .failed ? "Read failed" : nil))
                })
        }
    }

    private enum Status: String, Decodable { case ok, paused, failed }

    private struct Snapshot: Decodable {
        let fetchedAt: Int64
        let status: Status
        let balances: [Balance]

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["fetchedAt", "status", "balances"])
            let c = try decoder.container(keyedBy: Keys.self)
            fetchedAt = try c.decode(Int64.self, forKey: Keys("fetchedAt"))
            status = try c.decode(Status.self, forKey: Keys("status"))
            balances = try c.decode([Balance].self, forKey: Keys("balances"))
        }
    }

    private struct Balance: Decodable {
        let balance: CreditBalance

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["currency", "amount", "total", "unlimited"])
            let c = try decoder.container(keyedBy: Keys.self)
            let currency = try c.decode(String.self, forKey: Keys("currency"))
            try validateCurrency(currency)
            balance = CreditBalance(currency: currency,
                amount: try c.decode(Money.self, forKey: Keys("amount")).value,
                total: try c.decodeIfPresent(Money.self, forKey: Keys("total"))?.value,
                unlimited: try c.decode(Bool.self, forKey: Keys("unlimited")))
        }
    }

    private struct Expected: Decodable {
        let count: Int
        let winner: Winner?

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["count", "winner"])
            let c = try decoder.container(keyedBy: Keys.self)
            count = try c.decode(Int.self, forKey: Keys("count"))
            winner = try c.decodeIfPresent(Winner.self, forKey: Keys("winner"))
        }
    }

    private struct Winner: Decodable {
        let accountId: String
        let balanceIndex: Int
        let currency: String
        let amount: Money

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["accountId", "balanceIndex", "currency", "amount"])
            let c = try decoder.container(keyedBy: Keys.self)
            accountId = try c.decode(String.self, forKey: Keys("accountId"))
            balanceIndex = try c.decode(Int.self, forKey: Keys("balanceIndex"))
            currency = try c.decode(String.self, forKey: Keys("currency"))
            try validateCurrency(currency)
            amount = try c.decode(Money.self, forKey: Keys("amount"))
        }
    }

    private struct Money: Decodable {
        let text: String
        let value: Decimal

        init(from decoder: Decoder) throws {
            text = try decoder.singleValueContainer().decode(String.self)
            guard text.range(of: #"\A-?[0-9]+(?:\.[0-9]+)?\z"#, options: .regularExpression) != nil,
                  let decimal = Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")), !decimal.isNaN else {
                throw VectorFileError(description: "invalid decimal string \(text)")
            }
            value = decimal
        }
    }
}

private func date(_ milliseconds: Int64) -> Date {
    Date(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
}

private func validateCurrency(_ currency: String) throws {
    guard currency.utf8.count == 3, currency.utf8.allSatisfy({ (65...90).contains($0) }) else {
        throw VectorFileError(description: "invalid ISO currency \(currency)")
    }
}
