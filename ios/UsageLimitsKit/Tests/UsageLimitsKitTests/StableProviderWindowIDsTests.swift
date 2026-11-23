import XCTest
@testable import UsageLimitsKit

final class StableProviderWindowIDsTests: XCTestCase {
    func testBothParsersPreserveIdentitiesAndReadingsThroughEverySharedPayload() throws {
        let data = try Data(contentsOf: SharedVectors.file("stable-provider-window-ids.json"))
        let file = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(Set(file.keys), ["description", "schema", "vectors"])
        _ = try XCTUnwrap(file["description"] as? String)
        _ = try XCTUnwrap(file["schema"] as? String)
        let vectors = try XCTUnwrap(file["vectors"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(vectors.count, 16, "Keep reorder, shortened, Unicode and duplicate cases")
        var names = Set<String>()
        var providers = Set<String>()
        for vector in vectors {
            XCTAssertEqual(Set(vector.keys), ["name", "provider", "payload", "expected"])
            let name = try XCTUnwrap(vector["name"] as? String)
            XCTAssertTrue(names.insert(name).inserted, "Duplicate vector name: \(name)")
            let provider = try XCTUnwrap(vector["provider"] as? String)
            providers.insert(provider)
            let payload = try XCTUnwrap(vector["payload"] as? [String: Any])
            let expectedPayload = try XCTUnwrap(vector["expected"] as? [[String: Any]])
            let expected = try SharedVectors.parse([Window].self,
                from: JSONSerialization.data(withJSONObject: expectedPayload))
            let windows: [UsageWindow]
            switch provider {
            case "kimi": windows = KimiUsageParser.parse(payload)
            case "zai": windows = ZaiUsageParser.parse(payload)
            default:
                XCTFail("Unknown vector provider: \(provider)")
                continue
            }
            XCTAssertEqual(windows, expected.map(\.window), name)
            XCTAssertEqual(Set(windows.map(\.id)).count, windows.count, "\(name): IDs must be unique")
        }
        XCTAssertEqual(providers, ["kimi", "zai"])
    }

    private struct Window: Decodable {
        let window: UsageWindow

        init(from decoder: Decoder) throws {
            try requireExactKeys(decoder,
                ["id", "label", "category", "usedPercent", "periodSeconds", "resetAt", "exhausted"])
            let c = try decoder.container(keyedBy: Keys.self)
            window = UsageWindow(
                id: try c.decode(String.self, forKey: Keys("id")),
                label: try c.decode(String.self, forKey: Keys("label")),
                category: try c.decode(WindowCategory.self, forKey: Keys("category")),
                usedPercent: try c.decodeIfPresent(Double.self, forKey: Keys("usedPercent")),
                periodSeconds: try c.decodeIfPresent(Int64.self, forKey: Keys("periodSeconds")),
                resetAt: try c.decodeIfPresent(Int64.self, forKey: Keys("resetAt")).map {
                    Date(timeIntervalSince1970: Double($0) / 1000)
                },
                exhausted: try c.decode(Bool.self, forKey: Keys("exhausted")))
        }
    }

    func testOldSerializedProviderWindowsKeepTheirIDsAndReadingsUntilAFreshRead() throws {
        for id in ["kimi-limit-0", "zai-tokens_limit-1"] {
            let stored = """
                {"accountID":"a","fetchedAt":0,"status":"ok","windows":[
                  {"id":"\(id)","label":"A","category":"fiveHour","usedPercent":25,
                   "periodSeconds":18000,"resetAt":0,"exhausted":false,"scope":"global"}]}
                """
            let snapshot = try JSONDecoder().decode(UsageSnapshot.self, from: Data(stored.utf8))
            XCTAssertEqual(snapshot.windows, [UsageWindow(id: id, label: "A", category: .fiveHour,
                usedPercent: 25, periodSeconds: 18_000, resetAt: Date(timeIntervalSinceReferenceDate: 0),
                exhausted: false)])
        }
    }
}
