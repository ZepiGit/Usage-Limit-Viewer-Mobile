import XCTest
@testable import UsageLimitsKit

final class PinnedAccountsVectorsTests: XCTestCase {
    private struct File: Decodable {
        let vectors: [Vector]
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["description", "schema", "vectors"])
            let c = try decoder.container(keyedBy: Keys.self)
            _ = try c.decode(String.self, forKey: Keys("description"))
            _ = try c.decode(String.self, forKey: Keys("schema"))
            vectors = try c.decode([Vector].self, forKey: Keys("vectors"))
            guard !vectors.isEmpty, Set(vectors.map(\.name)).count == vectors.count else {
                throw VectorFileError(description: "pin vectors must be nonempty and uniquely named")
            }
        }
    }
    private struct Account: Decodable {
        let id: String
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["id", "hasReading"])
            let c = try decoder.container(keyedBy: Keys.self)
            id = try c.decode(String.self, forKey: Keys("id"))
            _ = try c.decode(Bool.self, forKey: Keys("hasReading"))
        }
    }
    private struct Vector: Decodable {
        let name: String
        let enabled: Bool
        let pinned: [String]
        let accounts: [Account]
        let expected: [String]
        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder, ["name", "enabled", "pinned", "usageAccounts", "creditAccounts", "expected"])
            let c = try decoder.container(keyedBy: Keys.self)
            name = try c.decode(String.self, forKey: Keys("name"))
            enabled = try c.decode(Bool.self, forKey: Keys("enabled"))
            pinned = try c.decode([String].self, forKey: Keys("pinned"))
            accounts = try c.decode([Account].self, forKey: Keys("usageAccounts"))
            _ = try c.decode([String].self, forKey: Keys("creditAccounts"))
            expected = try c.decode([String].self, forKey: Keys("expected"))
        }
    }

    func testMalformedVectorsFailRatherThanSilentlyDroppingCoverage() throws {
        let data = try Data(contentsOf: SharedVectors.file("pinned-accounts.json"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let vectors = try XCTUnwrap(json["vectors"] as? [[String: Any]])
        func rejected(_ cases: [[String: Any]]) throws {
            var changed = json
            changed["vectors"] = cases
            XCTAssertThrowsError(try SharedVectors.parse(File.self,
                from: JSONSerialization.data(withJSONObject: changed)))
        }
        try rejected([])
        try rejected([vectors[0], vectors[0]])
        var missing = vectors[0]
        missing["pins"] = missing.removeValue(forKey: "pinned")
        try rejected([missing])
        var nested = vectors[0]
        nested["usageAccounts"] = [["id": "a", "reading": true]]
        try rejected([nested])
    }

    func testEverySharedVectorSelectsTheExpectedAccounts() throws {
        let file = try SharedVectors.parse(File.self, from: Data(contentsOf: SharedVectors.file("pinned-accounts.json")))
        for vector in file.vectors {
            XCTAssertEqual(PinnedAccounts.select(enabled: vector.enabled, pinnedIDs: vector.pinned,
                usageAccountIDs: vector.accounts.map(\.id)), vector.expected, vector.name)
        }
    }
}
