import XCTest
@testable import UsageLimitsKit

final class SameProviderIdentityTests: XCTestCase {
    func testEveryRankUsesTheSharedExpectedIdentities() throws {
        let file = try SharedVectors.parse(VectorFile.self,
            from: Data(contentsOf: SharedVectors.file("same-provider-identity.json")))
        for vector in file.vectors {
            let accounts = Dictionary(uniqueKeysWithValues: vector.accounts.map { ($0.id, $0) })
            let expected = Dictionary(uniqueKeysWithValues: vector.expected.map { ($0.id, $0.identity) })
            for rank in vector.ranks {
                let entries = try rank.map { id in
                    let account = try XCTUnwrap(accounts[id])
                    return OverviewProjection.Entry(account: GlanceAccount(
                        id: account.id, title: account.provider, subtitle: account.maskedEmail,
                        rows: [], severity: .unknown, providerID: account.provider,
                        createdAt: Date(timeIntervalSince1970: TimeInterval(account.createdAt) / 1000)),
                        headlineWindowID: nil)
                }
                XCTAssertEqual(OverviewProjection.sameProviderIdentities(in: entries), expected,
                    "\(vector.name), rank: \(rank)")
            }
        }
        XCTAssertEqual(OverviewProjection.sameProviderIdentities(in: []), [:])
    }

    func testStrictReaderRejectsMalformedVectors() throws {
        let data = try Data(contentsOf: SharedVectors.file("same-provider-identity.json"))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let vectors = try XCTUnwrap(root["vectors"] as? [[String: Any]])
        let first = try XCTUnwrap(vectors.first)
        let accounts = try XCTUnwrap(first["accounts"] as? [[String: Any]])
        let expected = try XCTUnwrap(first["expected"] as? [[String: Any]])

        func rejected(_ vector: [String: Any], _ reason: String) throws {
            var changed = root
            changed["vectors"] = [vector]
            XCTAssertThrowsError(try SharedVectors.parse(VectorFile.self,
                from: JSONSerialization.data(withJSONObject: changed)), reason)
        }
        var empty = root
        empty["vectors"] = []
        XCTAssertThrowsError(try SharedVectors.parse(VectorFile.self,
            from: JSONSerialization.data(withJSONObject: empty)))
        var account = accounts[0]
        account.removeValue(forKey: "maskedEmail")
        var changed = first
        changed["accounts"] = [account, accounts[1]]
        try rejected(changed, "missing nullable account field")

        changed = first
        changed["ranks"] = [["a", "a"]]
        try rejected(changed, "rank repeats one ID and omits another")

        var result = expected[0]
        result["kind"] = "number"
        changed = first
        changed["expected"] = [result, expected[1]]
        try rejected(changed, "number identity without a number")
    }

    private struct VectorFile: Decodable {
        let vectors: [Vector]

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["description", "schema", "vectors"])
            let c = try decoder.container(keyedBy: Keys.self)
            _ = try c.decode(String.self, forKey: Keys("description"))
            _ = try c.decode(String.self, forKey: Keys("schema"))
            vectors = try c.decode([Vector].self, forKey: Keys("vectors"))
            guard !vectors.isEmpty, Set(vectors.map(\.name)).count == vectors.count else {
                throw VectorFileError(description: "identity vectors must be nonempty and uniquely named")
            }
        }
    }

    private struct Vector: Decodable {
        let name: String
        let accounts: [Account]
        let ranks: [[String]]
        let expected: [Expected]

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["name", "accounts", "ranks", "expected"])
            let c = try decoder.container(keyedBy: Keys.self)
            name = try c.decode(String.self, forKey: Keys("name"))
            accounts = try c.decode([Account].self, forKey: Keys("accounts"))
            ranks = try c.decode([[String]].self, forKey: Keys("ranks"))
            expected = try c.decode([Expected].self, forKey: Keys("expected"))
            let ids = Set(accounts.map(\.id))
            guard !name.isEmpty, !accounts.isEmpty, ids.count == accounts.count,
                  !ranks.isEmpty, ranks.allSatisfy({ $0.count == ids.count && Set($0) == ids }),
                  expected.count == ids.count, Set(expected.map(\.id)) == ids else {
                throw VectorFileError(description: "invalid identity vector: \(name)")
            }
        }
    }

    private struct Account: Decodable {
        let id: String
        let provider: String
        let createdAt: Int64
        let maskedEmail: String?

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["id", "provider", "createdAt", "maskedEmail"])
            let c = try decoder.container(keyedBy: Keys.self)
            id = try c.decode(String.self, forKey: Keys("id"))
            provider = try c.decode(String.self, forKey: Keys("provider"))
            createdAt = try c.decode(Int64.self, forKey: Keys("createdAt"))
            maskedEmail = try c.decodeIfPresent(String.self, forKey: Keys("maskedEmail"))
            guard !id.isEmpty, !provider.isEmpty, maskedEmail != "" else {
                throw VectorFileError(description: "invalid identity account")
            }
        }
    }

    private struct Expected: Decodable {
        let id: String
        let identity: SameProviderIdentity

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["id", "kind", "email", "number"])
            let c = try decoder.container(keyedBy: Keys.self)
            id = try c.decode(String.self, forKey: Keys("id"))
            let kind = try c.decode(String.self, forKey: Keys("kind"))
            let email = try c.decodeIfPresent(String.self, forKey: Keys("email"))
            let number = try c.decodeIfPresent(Int.self, forKey: Keys("number"))
            switch (kind, email, number) {
            case ("providerOnly", nil, nil): identity = .providerOnly
            case ("email", let email?, nil) where !email.isEmpty: identity = .email(email)
            case ("number", nil, let number?) where number > 0: identity = .number(number)
            default: throw VectorFileError(description: "invalid identity result for \(id)")
            }
        }
    }
}
